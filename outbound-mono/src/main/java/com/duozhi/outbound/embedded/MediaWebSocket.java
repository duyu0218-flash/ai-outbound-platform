package com.duozhi.outbound.embedded;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.*;
import org.springframework.web.socket.config.annotation.*;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

@Configuration
@EnableWebSocket
class MediaWebSocket implements WebSocketConfigurer {
    private final ObjectProvider<VoiceController> voice;

    MediaWebSocket(ObjectProvider<VoiceController> voice) { this.voice = voice; }
    private NativeMedia manager(String token) {
        var controller=voice.getIfAvailable();if(controller==null)return null;
        if(controller.gateway.media instanceof NativeMedia media)return media;
        if(controller.gateway.media instanceof MediaCluster cluster)return cluster.manager(token);
        return null;
    }
    private final java.util.Map<String,NativeMedia> connected=new java.util.concurrent.ConcurrentHashMap<>();
    @Override public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(new AbstractWebSocketHandler() {
            @Override public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                String path=session.getUri().getPath(),token=path.substring(path.lastIndexOf('/')+1);
                var manager=manager(token);
                if(manager==null||!token.matches("[A-Za-z0-9_-]{32,256}")){session.close(new CloseStatus(4404,"unknown media session"));return;}
                connected.put(session.getId(),manager);manager.connectAsync(session,token);

            }
            @Override protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws Exception {
                var manager=connected.get(session.getId());if(manager!=null)manager.enqueueAudio(session,message.getPayload());
            }
            @Override public void afterConnectionClosed(WebSocketSession session, CloseStatus status) { var manager=connected.remove(session.getId());if(manager!=null)manager.disconnected(session); }
            @Override public void handleTransportError(WebSocketSession session, Throwable error) throws Exception { var manager=connected.remove(session.getId());if(manager!=null)manager.disconnected(session); session.close(CloseStatus.SERVER_ERROR); }
        }, "/v1/pipecat/media/{token}");
    }
}
