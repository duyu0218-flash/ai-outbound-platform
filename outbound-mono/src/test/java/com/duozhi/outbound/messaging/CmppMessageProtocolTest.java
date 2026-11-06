package com.duozhi.outbound.messaging;

import static org.assertj.core.api.Assertions.*;

import com.duozhi.outbound.common.config.Config;
import com.duozhi.outbound.messaging.service.*;
import com.zx.sms.codec.cmpp.msg.*;
import com.zx.sms.common.GlobalConstance;
import com.zx.sms.common.util.MsgId;
import com.zx.sms.connect.manager.EndpointManager;
import com.zx.sms.connect.manager.cmpp.*;
import com.zx.sms.handler.api.AbstractBusinessHandler;
import io.netty.channel.ChannelHandlerContext;
import java.net.ServerSocket;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class CmppMessageProtocolTest {
    @Test
    void realSmsGateCmppLoginLongMessageAndDeliveryReportWorkOnLoopback() throws Exception {
        int port;
        try (var socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        var received = new AtomicInteger();
        var server = new CMPPServerEndpointEntity();
        server.setId("sms-test-carrier");
        server.setHost("127.0.0.1");
        server.setPort(port);
        server.setValid(true);
        var child = new CMPPServerChildEndpointEntity();
        child.setId("sms-test-account");
        child.setUserName("test01");
        child.setPassword("test-password");
        child.setVersion((short) 0x30);
        child.setMaxChannels((short) 1);
        child.setValid(true);
        child.setBusinessHandlerSet(
                List.of(
                        new AbstractBusinessHandler() {
                            public String name() {
                                return "install-test-carrier";
                            }

                            @Override
                            public void handlerAdded(ChannelHandlerContext ctx) {
                                ctx.pipeline()
                                        .addAfter(
                                                GlobalConstance.sessionStateManager,
                                                "test-carrier",
                                                new io.netty.channel
                                                        .ChannelInboundHandlerAdapter() {
                                                    @Override
                                                    public void channelRead(
                                                            ChannelHandlerContext ctx,
                                                            Object message) {
                                                        if (!(message
                                                                instanceof
                                                                CmppSubmitRequestMessage request)) {
                                                            ctx.fireChannelRead(message);
                                                            return;
                                                        }
                                                        int n = received.incrementAndGet();
                                                        var response =
                                                                new CmppSubmitResponseMessage(
                                                                        request.getHeader()
                                                                                .getSequenceId());
                                                        response.setMsgId(new MsgId(n));
                                                        response.setResult(0);
                                                        ctx.writeAndFlush(response);
                                                        var report = new CmppReportRequestMessage();
                                                        report.setMsgId(response.getMsgId());
                                                        report.setStat("DELIVRD");
                                                        report.setDestterminalId(
                                                                request.getDestterminalId()[0]);
                                                        report.setSubmitTime("2611061200");
                                                        report.setDoneTime("2611061201");
                                                        var delivery =
                                                                new CmppDeliverRequestMessage();
                                                        delivery.setReportRequestMessage(report);
                                                        delivery.setDestId(request.getSrcId());
                                                        delivery.setSrcterminalId(
                                                                request.getDestterminalId()[0]);
                                                        ctx.writeAndFlush(delivery);
                                                    }
                                                });
                                ctx.pipeline().remove(this);
                            }
                        }));
        server.addchild(child);
        var config = new Config(new MockEnvironment().withProperty("ENV", "internal-test"));
        var channels = new MessageChannels(null, null, null, config);
        var delivery = org.mockito.Mockito.mock(MessageDelivery.class);
        var reports = new LinkedBlockingQueue<ChannelProtocol.Part>();
        org.mockito.Mockito.doAnswer(
                        inv -> {
                            reports.add(
                                    new ChannelProtocol.Part(
                                            inv.getArgument(1), inv.getArgument(2)));
                            return null;
                        })
                .when(delivery)
                .carrierReport(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString());
        var factory = new org.springframework.beans.factory.support.StaticListableBeanFactory();
        factory.addBean("delivery", delivery);
        var transport =
                new CmppMessageProtocol(channels, factory.getBeanProvider(MessageDelivery.class));
        var channel =
                Map.<String, Object>of(
                        "id",
                        "test-cmpp",
                        "environment",
                        "test",
                        "config",
                        Map.of(
                                "host",
                                "127.0.0.1",
                                "port",
                                port,
                                "username",
                                "test01",
                                "sp_code",
                                "10690001",
                                "tps",
                                100));
        var credentials = Map.<String, Object>of("password", "test-password");
        try {
            EndpointManager.INS.openEndpoint(server);
            String text = "【测试签名】" + "短信分片测试".repeat(30);
            var parts =
                    transport
                            .submit(
                                    channel,
                                    credentials,
                                    new ChannelProtocol.Submission(
                                            "msg-test",
                                            "sms",
                                            "+8613800000000",
                                            Map.of("text", text),
                                            "sender",
                                            "template",
                                            Map.of()))
                            .parts();
            assertThat(parts).hasSize(MessageContent.smsUnits(text));
            assertThat(parts).hasSizeGreaterThan(1).allMatch(p -> p.state().equals("ACCEPTED"));
            assertThat(received.get()).isEqualTo(parts.size());
            var ids = new HashSet<String>();
            for (int i = 0; i < parts.size(); i++) {
                var report = reports.poll(5, TimeUnit.SECONDS);
                assertThat(report).isNotNull();
                assertThat(report.state()).isEqualTo("DELIVERED");
                ids.add(report.providerId());
            }
            assertThat(ids)
                    .containsExactlyInAnyOrderElementsOf(
                            parts.stream().map(ChannelProtocol.Part::providerId).toList());
        } finally {
            transport.close();
            EndpointManager.INS.close(server);
        }
    }
}
