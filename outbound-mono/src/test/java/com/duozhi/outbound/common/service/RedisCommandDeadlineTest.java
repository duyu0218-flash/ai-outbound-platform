package com.duozhi.outbound.common.service;

import static org.junit.jupiter.api.Assertions.*;

import com.duozhi.outbound.common.config.Config;
import com.duozhi.outbound.common.exception.ApiError;
import java.net.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class RedisCommandDeadlineTest {
    @Test
    void continuallyDrippingReplyCannotExtendCommandDeadlineAndSharedStateFailsClosed()
            throws Exception {
        try (var listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
                var workers = Executors.newFixedThreadPool(2)) {
            var peer =
                    workers.submit(
                            () -> {
                                try (var socket = listener.accept()) {
                                    var output = socket.getOutputStream();
                                    for (byte value :
                                            "+PONG\r\n"
                                                    .getBytes(
                                                            java.nio.charset.StandardCharsets
                                                                    .US_ASCII)) {
                                        output.write(value);
                                        output.flush();
                                        Thread.sleep(80);
                                    }
                                } catch (Exception expectedDeadlineClose) {
                                }
                            });
            var redis =
                    new ExpiringValues(
                            new Config(
                                    new MockEnvironment()
                                            .withProperty(
                                                    "REDIS_URL",
                                                    "redis://127.0.0.1:" + listener.getLocalPort())
                                            .withProperty("JAVA_REDIS_COMMAND_TIMEOUT_MS", "250")));
            long started = System.nanoTime();
            var command =
                    workers.submit(() -> assertThrows(ApiError.class, () -> redis.shared("PING")));
            assertEquals(503, command.get(2, TimeUnit.SECONDS).status);
            assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(2));
            peer.get(2, TimeUnit.SECONDS);
        }
    }
}
