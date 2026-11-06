package com.duozhi.outbound.messaging;

import static org.assertj.core.api.Assertions.*;

import com.duozhi.outbound.common.config.Config;
import com.duozhi.outbound.integration.support.Support;
import com.duozhi.outbound.messaging.service.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.env.MockEnvironment;

class AustinSmsAdapterTest {
    HttpServer server;
    AustinSmsAdapter adapter;
    AtomicReference<Map<String, Object>> received = new AtomicReference<>();
    AtomicReference<String> authorization = new AtomicReference<>();
    AtomicReference<String> response = new AtomicReference<>();
    AtomicInteger code = new AtomicInteger(200);
    AtomicInteger count = new AtomicInteger();
    final String id = "msg_0123456789abcdef0123456789abcdef";

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/send",
                exchange -> {
                    count.incrementAndGet();
                    received.set(Support.object(exchange.getRequestBody().readAllBytes()));
                    authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                    byte[] bytes = response.get().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Location", "/other");
                    exchange.sendResponseHeaders(code.get(), bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
        server.start();
        response.set(
                "{\"code\":\"00000\",\"data\":[{\"bizId\":\""
                        + id
                        + "\",\"messageId\":\"austin-123\"}]}");
        adapter =
                new AustinSmsAdapter(
                        new Config(
                                new MockEnvironment()
                                        .withProperty("SMS_AUSTIN_ENABLED", "true")
                                        .withProperty("SMS_SMSGATE_ENABLED", "true")
                                        .withProperty(
                                                "SMS_BRIDGE_TOKEN",
                                                "test-bridge-token-not-for-production-1234")
                                        .withProperty("SMS_AUSTIN_API_TOKEN", "test-auth-token")
                                        .withProperty(
                                                "SMS_AUSTIN_SEND_URL",
                                                "http://127.0.0.1:"
                                                        + server.getAddress().getPort()
                                                        + "/send")));
    }

    @AfterEach
    void close() {
        adapter.close();
        server.stop(0);
    }

    MessageAdapter.Submission submission() {
        return new MessageAdapter.Submission(
                id,
                "sms",
                "+8613800000000",
                "您好，客户。",
                Map.of("austin_template_id", "9223372036854775806"));
    }

    @Test
    void usesAustinContractAndOnlyMarksAcceptance() throws Exception {
        var result = adapter.submit(submission());
        assertThat(result.state()).isEqualTo("ACCEPTED");
        assertThat(result.simulated()).isFalse();
        assertThat(result.providerId()).isEqualTo("austin-123");
        assertThat(authorization.get()).isEqualTo("Bearer test-auth-token");
        assertThat(received.get())
                .containsEntry("code", "send")
                .containsEntry("messageTemplateId", 9223372036854775806L);
        assertThat(Support.map(received.get().get("messageParam")))
                .containsEntry("bizId", id)
                .containsEntry("receiver", "13800000000")
                .containsEntry("variables", Map.of("content", "您好，客户。"));
    }

    @Test
    void malformedOrUncorrelatedSuccessAndHttpErrorsRemainUnknown() throws Exception {
        for (String body :
                List.of(
                        "{\"code\":\"00000\"}",
                        "{\"code\":\"B0001\"}",
                        "{\"code\":\"00000\",\"data\":[{\"bizId\":\"other\",\"messageId\":\"a1\"}]}")) {
            response.set(body);
            assertThat(adapter.submit(submission()).state()).isEqualTo("UNKNOWN");
        }
        code.set(503);
        assertThat(adapter.submit(submission()).state()).isEqualTo("UNKNOWN");
        assertThat(count.get()).isEqualTo(4);
    }

    @Test
    void neverFollowsRedirectsOrRetriesAmbiguousSubmission() throws Exception {
        code.set(302);
        assertThat(adapter.submit(submission()).state()).isEqualTo("UNKNOWN");
        assertThat(count.get()).isEqualTo(1);
    }
}
