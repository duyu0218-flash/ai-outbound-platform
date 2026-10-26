package com.duozhi.outbound.voiceai.service;

import com.duozhi.outbound.integration.support.Support;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** One bounded event queue per provider socket; no network thread executes business callbacks. */
final class VendorSocket implements WebSocket.Listener, AutoCloseable {
    interface Handler {
        void receive(boolean binary, byte[] data) throws Exception;
    }

    private final BlockingQueue<Object[]> incoming = new ArrayBlockingQueue<>(128);
    private final Handler handler;
    private final ByteArrayOutputStream binary = new ByteArrayOutputStream();
    private final StringBuilder text = new StringBuilder();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile WebSocket socket;
    private volatile Throwable failure;
    private final Thread receiver;

    VendorSocket(HttpClient http, URI uri, Map<String, String> headers, Handler handler)
            throws Exception {
        this(http, uri, headers, handler, null);
    }

    VendorSocket(
            HttpClient http,
            URI uri,
            Map<String, String> headers,
            Handler handler,
            ResponseScope scope)
            throws Exception {
        this.handler = handler;
        receiver = Thread.ofVirtual().name("voice-ai-provider-events").unstarted(this::consume);
        if (scope != null) scope.own(this);
        var builder = http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(8));
        headers.forEach(builder::header);
        var connecting = builder.buildAsync(uri, this);
        if (scope != null) scope.own(() -> connecting.cancel(true));
        connecting.thenAccept(
                ws -> {
                    if (closed.get()) ws.abort();
                });
        try {
            socket = connecting.get(8, TimeUnit.SECONDS);
            receiver.start();
        } catch (Exception error) {
            close();
            throw new IOException("provider connection failed");
        }
    }

    private void consume() {
        while (!closed.get()) {
            try {
                var message = incoming.take();
                if (message[1] == null) {
                    failure = new IOException("provider socket closed");
                    close();
                    return;
                }
                handler.receive((boolean) message[0], (byte[]) message[1]);
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception invalid) {
                failure = invalid;
                close();
            }
        }
    }

    public void check() throws IOException {
        if (failure != null || closed.get())
            throw new IOException("provider connection unavailable");
    }

    void json(Object value) throws Exception {
        send(false, Support.json(value));
    }

    void bytes(byte[] value) throws Exception {
        send(true, value);
    }

    private synchronized void send(boolean binary, byte[] value) throws Exception {
        check();
        if (binary) socket.sendBinary(ByteBuffer.wrap(value), true).get(5, TimeUnit.SECONDS);
        else
            socket.sendText(new String(value, StandardCharsets.UTF_8), true)
                    .get(5, TimeUnit.SECONDS);
    }

    @Override
    public void onOpen(WebSocket ws) {
        ws.request(1);
    }

    private void enqueue(WebSocket ws, boolean isBinary, byte[] value) {
        if (!incoming.offer(new Object[] {isBinary, value})) {
            failure = new IOException("provider event queue full");
            close();
        } else ws.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket ws, CharSequence part, boolean last) {
        if (text.length() + part.length() > 2 * 1024 * 1024) {
            failure = new IOException("provider text limit");
            close();
            return null;
        }
        text.append(part);
        if (last) {
            byte[] bytes = text.toString().getBytes(StandardCharsets.UTF_8);
            text.setLength(0);
            enqueue(ws, false, bytes);
        } else ws.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onBinary(WebSocket ws, ByteBuffer part, boolean last) {
        if (binary.size() + part.remaining() > 2 * 1024 * 1024) {
            failure = new IOException("provider binary limit");
            close();
            return null;
        }
        byte[] bytes = new byte[part.remaining()];
        part.get(bytes);
        binary.writeBytes(bytes);
        if (last) {
            enqueue(ws, true, binary.toByteArray());
            binary.reset();
        } else ws.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket ws, int status, String reason) {
        if (!incoming.offer(new Object[] {false, null})) {
            failure = new IOException("provider socket closed");
            close();
        }
        return null;
    }

    @Override
    public void onError(WebSocket ws, Throwable error) {
        failure = new IOException("provider socket error");
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        receiver.interrupt();
        incoming.clear();
        if (socket != null) socket.abort();
    }
}
