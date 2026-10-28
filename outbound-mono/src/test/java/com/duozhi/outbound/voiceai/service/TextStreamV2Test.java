package com.duozhi.outbound.voiceai.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TextStreamV2Test {
    @Test
    void multibyteUtf8AcrossChunksAndMultilineEventsAreIncremental() throws Exception {
        String wire =
                ":heartbeat\r\ndata: {\"choices\":[{\"delta\":{\"content\":\"您好。\"},\n"
                        + "data: \"finish_reason\":null}]}\n\ndata: [DONE]\n\n";
        byte[] bytes = wire.getBytes(StandardCharsets.UTF_8);
        var reads = new AtomicInteger();
        var source =
                new ByteArrayInputStream(bytes) {
                    @Override
                    public synchronized int read(byte[] b, int off, int len) {
                        reads.incrementAndGet();
                        return super.read(b, off, Math.min(len, 2));
                    }
                };
        var deltas = new ArrayList<String>();
        try (var scope = new ResponseScope();
                var deadline = new TextStreamDeadline(scope, 5000, 5000, 10000)) {
            assertThat(SseTextReader.read(source, deltas::add, scope, deadline)).isEqualTo("您好。");
            assertThat(deltas).containsExactly("您好。");
            assertThat(reads.get()).isLessThan(bytes.length);
        }
    }

    @Test
    void truncatedInvalidUtf8ToolCallsAndOverlongLinesFailClosed() throws Exception {
        List<byte[]> invalid =
                List.of(
                        "data: {\"choices\":[{\"delta\":{\"content\":\"半句\"}}]}\n\n"
                                .getBytes(StandardCharsets.UTF_8),
                        "data: {\"choices\":[{\"delta\":{\"tool_calls\":[]}}]}\n\ndata: [DONE]\n\n"
                                .getBytes(StandardCharsets.UTF_8),
                        ("data: " + "x".repeat(65536) + "\n\n").getBytes(StandardCharsets.UTF_8),
                        new byte[] {(byte) 0xff, 10});
        for (byte[] bytes : invalid)
            try (var scope = new ResponseScope();
                    var deadline = new TextStreamDeadline(scope, 5000, 5000, 10000)) {
                assertThatThrownBy(
                                () ->
                                        SseTextReader.read(
                                                new ByteArrayInputStream(bytes),
                                                text -> {},
                                                scope,
                                                deadline))
                        .isInstanceOf(IOException.class);
            }
    }

    @Test
    void firstUsefulContentAndIdleDeadlinesIgnoreHeartbeatsAndCancelOnlyTheirScope()
            throws Exception {
        try (var scope = new ResponseScope();
                var unaffected = new ResponseScope();
                var deadline = new TextStreamDeadline(scope, 60, 60, 500)) {
            var closed = new CountDownLatch(1);
            scope.own(closed::countDown);
            assertThat(closed.await(2, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(deadline::check)
                    .isInstanceOf(SocketTimeoutException.class)
                    .hasMessageContaining("first_content");
            unaffected.check();
        }
        try (var scope = new ResponseScope();
                var deadline = new TextStreamDeadline(scope, 1000, 60, 1000)) {
            var closed = new CountDownLatch(1);
            scope.own(closed::countDown);
            deadline.content();
            assertThat(closed.await(2, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(deadline::check).hasMessageContaining("content_idle");
        }
    }

    @Test
    void blockedTransportCloseDoesNotBlockOtherDeadlines() throws Exception {
        var release = new CountDownLatch(1);
        var entered = new CountDownLatch(1);
        try (var first = new ResponseScope();
                var second = new ResponseScope();
                var d1 = new TextStreamDeadline(first, 40, 1000, 1000);
                var d2 = new TextStreamDeadline(second, 80, 1000, 1000)) {
            first.own(
                    () -> {
                        entered.countDown();
                        release.await(3, TimeUnit.SECONDS);
                    });
            var closed = new CountDownLatch(1);
            second.own(closed::countDown);
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(closed.await(1, TimeUnit.SECONDS)).isTrue();
        } finally {
            release.countDown();
        }
    }

    @Test
    void socketErrorAbortsTransportAndWakesReceiver() throws Exception {
        var client = mock(HttpClient.class);
        var builder = mock(WebSocket.Builder.class);
        var ws = mock(WebSocket.class);
        when(client.newWebSocketBuilder()).thenReturn(builder);
        when(builder.connectTimeout(any())).thenReturn(builder);
        when(builder.buildAsync(any(), any())).thenReturn(CompletableFuture.completedFuture(ws));
        try (var socket =
                new VendorSocket(
                        client, URI.create("wss://example.invalid"), Map.of(), (b, data) -> {})) {
            socket.onError(ws, new IOException("synthetic"));
            verify(ws).abort();
            assertThatThrownBy(socket::check).isInstanceOf(IOException.class);
        }
    }
}
