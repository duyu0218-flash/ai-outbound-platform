package com.duozhi.outbound.messaging.service;

import com.duozhi.outbound.common.config.Config;
import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.integration.support.Support;
import com.zx.sms.codec.cmpp.msg.*;
import com.zx.sms.common.util.ChannelUtil;
import com.zx.sms.connect.manager.EndpointManager;
import com.zx.sms.connect.manager.cmpp.CMPPClientEndpointEntity;
import com.zx.sms.handler.api.AbstractBusinessHandler;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

/** SMSGate CMPP 3.0 transport; no automatic resubmission or channel fallback. */
@Component
public class CmppSmsGateTransport implements SmsGateTransport {
    private final Config config;
    private final String accountNamespace;
    private volatile Consumer<Part> reports;
    private CMPPClientEndpointEntity endpoint;
    private final ThreadPoolExecutor receipts =
            new ThreadPoolExecutor(
                    1,
                    1,
                    0,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(1000),
                    Thread.ofPlatform().daemon().name("sms-receipts").factory());

    public CmppSmsGateTransport(Config config) {
        this.config = config;
        this.accountNamespace =
                Support.hex(
                                Support.sha(
                                        (config.get("SMS_CMPP_HOST", "")
                                                        + ":"
                                                        + config.get("SMS_CMPP_PORT", "7890")
                                                        + ":"
                                                        + config.get("SMS_CMPP_USERNAME", "")
                                                        + ":"
                                                        + config.get("SMS_CMPP_SP_CODE", ""))
                                                .getBytes(StandardCharsets.UTF_8)))
                        .substring(0, 16);
    }

    public void onReport(Consumer<Part> sink) {
        reports = sink;
    }

    private synchronized CMPPClientEndpointEntity endpoint() throws Exception {
        if (!config.flag("SMS_SMSGATE_ENABLED", false)) throw new ApiError(503, "SMSGate 尚未启用");
        if (endpoint != null) return endpoint;
        String host = config.get("SMS_CMPP_HOST", ""),
                user = config.get("SMS_CMPP_USERNAME", ""),
                password = config.get("SMS_CMPP_PASSWORD", ""),
                sp = config.get("SMS_CMPP_SP_CODE", "");
        if (host.isBlank() || user.isBlank() || password.isBlank() || sp.isBlank())
            throw new ApiError(503, "CMPP 连接配置不完整");
        var e = new CMPPClientEndpointEntity();
        e.setId("outbound-sms-cmpp");
        e.setHost(host);
        e.setPort(config.number("SMS_CMPP_PORT", 7890));
        e.setUserName(user);
        e.setPassword(password);
        e.setSpCode(sp);
        e.setVersion((short) 0x30);
        e.setChartset(StandardCharsets.UTF_8);
        e.setMaxChannels((short) 1);
        e.setReSendFailMsg(false);
        e.setOverSpeedSendCountLimit(0);
        e.setRetryWaitTimeSec((short) 10);
        e.setMaxRetryCnt((short) 0);
        e.setBusinessHandlerSet(List.of(new Reports()));
        EndpointManager.INS.addEndpointEntity(e);
        EndpointManager.INS.openEndpoint(e);
        EndpointManager.INS.startConnectionCheckTask();
        endpoint = e;
        return e;
    }

    public List<Part> submit(String phone, String content) throws Exception {
        var e = endpoint();
        long connectDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (e.getSingletonConnector().getConnectionNum() == 0
                && System.nanoTime() < connectDeadline) {
            Thread.sleep(25);
        }
        var request = CmppSubmitRequestMessage.create(phone, e.getSpCode(), content);
        request.setMsgsrc(e.getUserName());
        request.setServiceId(config.get("SMS_CMPP_SERVICE_ID", ""));
        request.setRegisteredDelivery((short) 1);
        List<io.netty.util.concurrent.Promise<CmppSubmitResponseMessage>> responses =
                ChannelUtil.syncWriteLongMsgToEntity(e, request);
        if (responses == null || responses.isEmpty()) return List.of(new Part("", "UNKNOWN"));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        var result = new ArrayList<Part>();
        for (var response : responses) {
            long remaining = Math.max(0, deadline - System.nanoTime());
            if (response == null
                    || !response.await(remaining, TimeUnit.NANOSECONDS)
                    || !response.isSuccess()) {
                result.add(new Part("", "UNKNOWN"));
            } else {
                var reply = response.getNow();
                result.add(
                        new Part(
                                reply.getResult() == 0
                                        ? accountNamespace + ":" + reply.getMsgId()
                                        : "",
                                reply.getResult() == 0 ? "ACCEPTED" : "REJECTED"));
            }
        }
        return result;
    }

    @ChannelHandler.Sharable
    private final class Reports extends AbstractBusinessHandler {
        @Override
        public String name() {
            return "outbound-sms-reports";
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (!(msg instanceof CmppDeliverRequestMessage delivery)) {
                ctx.fireChannelRead(msg);
                return;
            }
            if (!delivery.isReport()) {
                // MO is not silently acknowledged as processed. Retry remains with the carrier.
                return;
            }
            var report = delivery.getReportRequestMessage();
            String status = report.getStat();
            String state =
                    "DELIVRD".equals(status)
                            ? "DELIVERED"
                            : Set.of("EXPIRED", "DELETED", "UNDELIV", "REJECTD").contains(status)
                                    ? "FAILED"
                                    : "UNKNOWN";
            try {
                receipts.execute(
                        () -> {
                            try {
                                if (reports == null) return;
                                reports.accept(
                                        new Part(
                                                accountNamespace + ":" + report.getMsgId(), state));
                                var ack = new CmppDeliverResponseMessage(delivery.getHeader());
                                ack.setMsgId(delivery.getMsgId());
                                ack.setResult(0);
                                ctx.writeAndFlush(ack);
                            } catch (RuntimeException ignored) {
                                // No ACK until the report has committed. Do not log PII or
                                // credentials.
                            }
                        });
            } catch (RejectedExecutionException busy) {
                /* Carrier retries the unacknowledged report. */
            }
        }
    }

    @PreDestroy
    public void close() {
        receipts.shutdownNow();
        if (endpoint != null) EndpointManager.INS.close(endpoint);
    }
}
