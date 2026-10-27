package com.duozhi.outbound.voiceai.config;

import com.duozhi.outbound.integration.support.Support;
import com.duozhi.outbound.voiceai.service.BailianTrials;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.*;
import org.springframework.web.socket.config.annotation.*;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

@Configuration
@EnableWebSocket
public class BailianTrialSocket implements WebSocketConfigurer {
    private final BailianTrials trials;

    public BailianTrialSocket(BailianTrials trials) {
        this.trials = trials;
    }

    private final Map<String, Peer> peers = new ConcurrentHashMap<>();

    class Peer {
        final WebSocketSession socket;
        final BlockingQueue<Object> commands = new ArrayBlockingQueue<>(64);
        final Thread worker;
        volatile BailianTrials.Live live;
        volatile boolean closed;

        Peer(WebSocketSession raw) {
            socket = new ConcurrentWebSocketSessionDecorator(raw, 5000, 256000);
            worker =
                    Thread.ofVirtual()
                            .start(
                                    () -> {
                                        try {
                                            Object first = commands.poll(5, TimeUnit.SECONDS);
                                            if (!(first instanceof Map<?, ?>))
                                                throw new IllegalArgumentException();
                                            var auth = Support.map(first);
                                            if (!"attach".equals(auth.get("type")))
                                                throw new IllegalArgumentException();
                                            live =
                                                    trials.attach(
                                                            BailianTrials.text(auth, "id", 36),
                                                            BailianTrials.text(auth, "ticket", 100),
                                                            event -> {
                                                                try {
                                                                    socket.sendMessage(
                                                                            new TextMessage(
                                                                                    Support.json(
                                                                                            event)));
                                                                } catch (Exception e) {
                                                                    throw new IllegalStateException(
                                                                            "browser disconnected");
                                                                }
                                                            },
                                                            this::closeSocket);
                                            live.start();
                                            while (!closed) {
                                                Object next = commands.poll(1, TimeUnit.SECONDS);
                                                if (next instanceof byte[] pcm) live.audio(pcm);
                                                else if (next instanceof Map<?, ?>)
                                                    live.command(Support.map(next));
                                            }
                                        } catch (Exception e) {
                                            if (live != null)
                                                live.fail("试聊连接或指令失败，已停止；请查看配置并重新创建试聊");
                                        } finally {
                                            close();
                                        }
                                    });
        }

        void offer(Object data) {
            if (!commands.offer(data)) close();
        }

        void closeSocket() {
            closed = true;
            try {
                socket.close();
            } catch (Exception ignored) {
            }
            worker.interrupt();
        }

        void close() {
            closed = true;
            if (live != null) live.end("interrupted");
            closeSocket();
        }
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(
                new AbstractWebSocketHandler() {
                    @Override
                    public void afterConnectionEstablished(WebSocketSession session)
                            throws Exception {
                        if (peers.size() >= 16) {
                            session.close(CloseStatus.POLICY_VIOLATION);
                            return;
                        }
                        session.setTextMessageSizeLimit(16000);
                        session.setBinaryMessageSizeLimit(6400);
                        peers.put(session.getId(), new Peer(session));
                    }

                    @Override
                    protected void handleTextMessage(
                            WebSocketSession session, TextMessage message) {
                        var p = peers.get(session.getId());
                        try {
                            if (p != null) p.offer(Support.object(message.asBytes()));
                        } catch (Exception e) {
                            if (p != null) p.close();
                        }
                    }

                    @Override
                    protected void handleBinaryMessage(
                            WebSocketSession session, BinaryMessage message) {
                        var p = peers.get(session.getId());
                        if (p != null) {
                            byte[] data = new byte[message.getPayloadLength()];
                            message.getPayload().get(data);
                            p.offer(data);
                        }
                    }

                    @Override
                    public void afterConnectionClosed(
                            WebSocketSession session, CloseStatus status) {
                        var p = peers.remove(session.getId());
                        if (p != null) p.close();
                    }

                    @Override
                    public void handleTransportError(WebSocketSession session, Throwable error) {
                        var p = peers.remove(session.getId());
                        if (p != null) p.close();
                    }
                },
                "/api/v1/voice-trial/socket");
    }
}
