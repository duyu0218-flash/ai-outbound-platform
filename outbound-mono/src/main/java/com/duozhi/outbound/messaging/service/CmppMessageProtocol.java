package com.duozhi.outbound.messaging.service;

import com.zx.sms.codec.cmpp.msg.*;
import com.zx.sms.common.util.ChannelUtil;
import com.zx.sms.connect.manager.EndpointManager;
import com.zx.sms.connect.manager.cmpp.CMPPClientEndpointEntity;
import com.zx.sms.handler.api.AbstractBusinessHandler;
import io.netty.channel.*;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** Real SMSGate CMPP 3.0 sessions, one isolated connection per immutable channel. */
@Component
public class CmppMessageProtocol implements ChannelProtocol {
    private final MessageChannels channels;
    private final ObjectProvider<MessageDelivery> deliveries;
    private final Map<String, CMPPClientEndpointEntity> endpoints = new HashMap<>();
    private final ThreadPoolExecutor reports =
            new ThreadPoolExecutor(
                    1,
                    1,
                    0,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(1000),
                    Thread.ofPlatform().daemon().name("message-cmpp-receipts").factory());

    public CmppMessageProtocol(
            MessageChannels channels, ObjectProvider<MessageDelivery> deliveries) {
        this.channels = channels;
        this.deliveries = deliveries;
    }

    public String protocol() {
        return "cmpp";
    }

    private synchronized CMPPClientEndpointEntity endpoint(
            Map<String, Object> channel, Map<String, Object> credentials) throws Exception {
        String id = channel.get("id").toString();
        if (endpoints.containsKey(id)) return endpoints.get(id);
        if (endpoints.size() >= 64)
            throw new java.io.IOException("CMPP connection budget exhausted");
        var cfg = Messaging.object(channel.get("config"));
        String host = cfg.get("host").toString();
        channels.validateCmpp(host, channel.get("environment").toString());
        var e = new CMPPClientEndpointEntity();
        e.setId("platform-" + id);
        e.setHost(host);
        e.setPort(((Number) cfg.get("port")).intValue());
        e.setUserName(cfg.get("username").toString());
        e.setPassword(credentials.get("password").toString());
        e.setSpCode(cfg.get("sp_code").toString());
        e.setVersion((short) 0x30);
        e.setChartset(StandardCharsets.UTF_8);
        e.setMaxChannels((short) 1);
        e.setConnectionTimeOut(5000);
        e.setIdleTimeSec((short) 30);
        e.setWindow(16);
        e.setWriteLimit(((Number) cfg.get("tps")).intValue());
        e.setReSendFailMsg(false);
        e.setOverSpeedSendCountLimit(0);
        e.setRetryWaitTimeSec((short) 10);
        e.setMaxRetryCnt((short) 0);
        e.setBusinessHandlerSet(List.of(new Inbound(id)));
        EndpointManager.INS.addEndpointEntity(e);
        EndpointManager.INS.openEndpoint(e);
        EndpointManager.INS.startConnectionCheckTask();
        endpoints.put(id, e);
        return e;
    }

    private CMPPClientEndpointEntity connected(
            Map<String, Object> channel, Map<String, Object> credentials) throws Exception {
        var e = endpoint(channel, credentials);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (e.getSingletonConnector().getConnectionNum() == 0 && System.nanoTime() < deadline)
            Thread.sleep(25);
        if (e.getSingletonConnector().getConnectionNum() == 0)
            throw new java.io.IOException("CMPP login not confirmed");
        return e;
    }

    public void probe(Map<String, Object> channel, Map<String, Object> credentials)
            throws Exception {
        connected(channel, credentials);
    }

    public Result submit(Map<String, Object> channel, Map<String, Object> credentials, Submission s)
            throws Exception {
        if (!s.form().equals("sms") || !s.recipient().matches("\\+861[3-9][0-9]{9}"))
            throw new java.io.IOException("CMPP requires mainland SMS recipient");
        String content = s.content().get("text").toString();
        MessageContent.smsUnits(content);
        var e = connected(channel, credentials);
        var cfg = Messaging.object(channel.get("config"));
        var request =
                CmppSubmitRequestMessage.create(s.recipient().substring(3), e.getSpCode(), content);
        request.setMsgContent(
                new com.chinamobile.cmos.sms.SmsTextMessage(
                        content, new com.chinamobile.cmos.sms.SmsDcs((byte) 8)));
        request.setMsgsrc(e.getUserName());
        request.setServiceId(Objects.toString(cfg.get("service_id"), ""));
        request.setRegisteredDelivery((short) 1);
        List<io.netty.util.concurrent.Promise<CmppSubmitResponseMessage>> responses =
                ChannelUtil.syncWriteLongMsgToEntity(e, request);
        if (responses == null || responses.isEmpty()) return Result.unknown();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        var parts = new ArrayList<Part>();
        for (var pending : responses) {
            long left = Math.max(0, deadline - System.nanoTime());
            if (pending == null
                    || !pending.await(left, TimeUnit.NANOSECONDS)
                    || !pending.isSuccess()) parts.add(new Part("", "UNKNOWN"));
            else {
                var response = pending.getNow();
                parts.add(
                        new Part(
                                response.getResult() == 0 ? response.getMsgId().toString() : "",
                                response.getResult() == 0 ? "ACCEPTED" : "REJECTED"));
            }
        }
        String state =
                parts.stream().allMatch(p -> p.state().equals("ACCEPTED"))
                        ? "ACCEPTED"
                        : parts.stream().allMatch(p -> p.state().equals("REJECTED"))
                                ? "REJECTED"
                                : "UNKNOWN";
        return new Result(state, "", parts);
    }

    @ChannelHandler.Sharable
    private final class Inbound extends AbstractBusinessHandler {
        private final String channel;

        Inbound(String channel) {
            this.channel = channel;
        }

        public String name() {
            return "platform-receipt-" + channel;
        }

        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (!(msg instanceof CmppDeliverRequestMessage delivery)) {
                ctx.fireChannelRead(msg);
                return;
            }
            try {
                reports.execute(
                        () -> {
                            try {
                                if (delivery.isReport()) {
                                    var report = delivery.getReportRequestMessage();
                                    String status = report.getStat();
                                    String state =
                                            "DELIVRD".equals(status)
                                                    ? "DELIVERED"
                                                    : Set.of(
                                                                            "EXPIRED", "DELETED",
                                                                            "UNDELIV", "REJECTD")
                                                                    .contains(status)
                                                            ? "FAILED"
                                                            : "UNKNOWN";
                                    deliveries
                                            .getObject()
                                            .carrierReport(
                                                    channel, report.getMsgId().toString(), state);
                                } else {
                                    deliveries
                                            .getObject()
                                            .carrierReply(
                                                    channel,
                                                    delivery.getMsgId().toString(),
                                                    delivery.getSrcterminalId(),
                                                    delivery.getMsgContent());
                                }
                                var ack = new CmppDeliverResponseMessage(delivery.getHeader());
                                ack.setMsgId(delivery.getMsgId());
                                ack.setResult(0);
                                ctx.writeAndFlush(ack);
                            } catch (RuntimeException ignored) {
                                /* Do not ACK data that did not commit. No PII in protocol logs. */
                            }
                        });
            } catch (RejectedExecutionException ignored) {
                /* Bounded queue; carrier retains unacknowledged deliveries. */
            }
        }
    }

    @PreDestroy
    public synchronized void close() {
        reports.shutdownNow();
        for (var e : endpoints.values()) EndpointManager.INS.close(e);
        endpoints.clear();
    }
}
