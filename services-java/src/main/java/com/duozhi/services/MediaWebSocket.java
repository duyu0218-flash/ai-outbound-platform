package com.duozhi.services;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.*;
import org.springframework.web.socket.config.annotation.*;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

@Configuration
@EnableWebSocket
class MediaWebSocket implements WebSocketConfigurer {
    private final ObjectProvider<VoiceController> voice;
    private final ObjectProvider<MediaWorkerController> worker;
    MediaWebSocket(ObjectProvider<VoiceController> voice, ObjectProvider<MediaWorkerController> worker) { this.voice = voice; this.worker = worker; }
    private NativeMedia manager() {
        var mediaWorker = worker.getIfAvailable(); if (mediaWorker != null) return mediaWorker.manager;
        var controller = voice.getIfAvailable(); return controller != null && controller.gateway.media instanceof NativeMedia nativeMedia ? nativeMedia : null;
    }
    @Override public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(new AbstractWebSocketHandler() {
            @Override public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                var manager = manager(); if (manager == null) { session.close(new CloseStatus(4404, "connect to the assigned media runtime")); return; }
                String path = session.getUri().getPath(), token = path.substring(path.lastIndexOf('/') + 1);
                if (!token.matches("[A-Za-z0-9_-]{32,256}")) { session.close(new CloseStatus(4404, "unknown media session")); return; } manager.connect(session, token);
            }
            @Override protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws Exception {
                var manager = manager(); if (manager == null) return; byte[] bytes = new byte[message.getPayloadLength()]; message.getPayload().get(bytes); manager.audio(session, bytes);
            }
            @Override public void afterConnectionClosed(WebSocketSession session, CloseStatus status) { var manager = manager(); if (manager != null) manager.disconnected(session); }
            @Override public void handleTransportError(WebSocketSession session, Throwable error) throws Exception { var manager = manager(); if (manager != null) manager.disconnected(session); session.close(CloseStatus.SERVER_ERROR); }
        }, "/v1/pipecat/media/{token}");
    }
}
