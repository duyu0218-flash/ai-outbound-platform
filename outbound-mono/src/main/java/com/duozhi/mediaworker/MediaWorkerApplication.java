package com.duozhi.mediaworker;

import com.duozhi.outbound.integration.config.Settings;
import com.duozhi.outbound.integration.support.Support;
import com.duozhi.outbound.media.service.*;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson2.autoconfigure.Jackson2AutoConfiguration;
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.socket.*;
import org.springframework.web.socket.config.annotation.*;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

/** Separate JVM role: imports only HTTP/WS infrastructure, with no business scan or database. */
@Configuration(proxyBeanMethods = false)
@Import({
    TomcatServletWebServerAutoConfiguration.class,
    DispatcherServletAutoConfiguration.class,
    WebMvcAutoConfiguration.class,
    HttpMessageConvertersAutoConfiguration.class,
    Jackson2AutoConfiguration.class
})
@EnableWebSocket
public class MediaWorkerApplication implements WebSocketConfigurer {
    private final MediaWorkerRuntime worker;

    public static void run(String[] args) {
        SpringApplication.run(MediaWorkerApplication.class, args);
    }

    public MediaWorkerApplication(Environment environment) {
        Settings settings =
                new Settings() {
                    @Override
                    public String get(String key, String fallback) {
                        return environment.getProperty(key, super.get(key, fallback)).strip();
                    }
                };
        if (settings.flag("VOICE_AI_PROFILES_ENABLED", false))
            throw new IllegalArgumentException("remote media profile authority is not installed");
        String endpoint = settings.get("MEDIA_CONTROL_ENDPOINT", "");
        // Reject invalid destinations at startup, before accepting media ownership.
        var uri = Support.endpoint(endpoint);
        if (uri.getQuery() != null
                || !java.util.Set.of("", "/").contains(uri.getPath())
                || !(uri.getScheme().equals("https") || uri.getHost().equals("127.0.0.1")))
            throw new IllegalArgumentException(
                    "media control endpoint requires HTTPS outside loopback");
        worker =
                new MediaWorkerRuntime(
                        settings,
                        (path, body) ->
                                MediaRpc.request(
                                        endpoint,
                                        path,
                                        body,
                                        settings.secret("MEDIA_RPC_TOKEN"),
                                        settings.decimal("MEDIA_RPC_TIMEOUT_SEC", 5, .1, 60)));
    }

    @Bean(destroyMethod = "close")
    MediaWorkerRuntime mediaWorkerRuntime() {
        return worker;
    }

    @RestController
    public static final class WorkerApi {
        private final MediaWorkerRuntime worker;

        WorkerApi(MediaWorkerRuntime worker) {
            this.worker = worker;
        }

        @GetMapping("/internal/state")
        public Map<String, Object> state(
                @RequestHeader(value = "Authorization", required = false)
                String auth) {
            return worker.state(auth);
        }

        @PostMapping(
                path = "/internal/command",
                consumes = {"application/json", "application/*+json"})
        public Map<String, Object> command(
                jakarta.servlet.http.HttpServletRequest request,
                @RequestHeader(value = "Authorization", required = false)
                String auth)
                throws Exception {
            worker.authenticate(auth);
            int maximum = 1024 * 1024;
            if (request.getContentLengthLong() > maximum)
                throw new Support.Failure(413, "media command body exceeds limit");
            byte[] bytes = request.getInputStream().readNBytes(maximum + 1);
            if (bytes.length > maximum)
                throw new Support.Failure(413, "media command body exceeds limit");
            Map<String, Object> body;
            try {
                body = Support.object(bytes);
            } catch (Support.Failure invalid) {
                throw new Support.Failure(400, "invalid JSON object");
            }
            return worker.command(body, auth);
        }

        @ExceptionHandler(Support.Failure.class)
        public ResponseEntity<?> failure(Support.Failure failure) {
            return ResponseEntity.status(failure.status)
                    .body(Map.of("detail", failure.getMessage()));
        }
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(
                new AbstractWebSocketHandler() {
                    @Override
                    public void afterConnectionEstablished(WebSocketSession session)
                            throws Exception {
                        String path = session.getUri().getPath();
                        String token = path.substring(path.lastIndexOf('/') + 1);
                        if (!token.matches("[A-Za-z0-9_-]{32,256}") || !worker.owns(token)) {
                            session.close(new CloseStatus(4404, "unknown media session"));
                            return;
                        }
                        worker.manager().connectAsync(session, token);
                    }

                    @Override
                    protected void handleBinaryMessage(
                            WebSocketSession session, BinaryMessage message) throws Exception {
                        worker.manager().enqueueAudio(session, message.getPayload());
                    }

                    @Override
                    public void afterConnectionClosed(
                            WebSocketSession session, CloseStatus status) {
                        worker.manager().disconnected(session);
                    }

                    @Override
                    public void handleTransportError(WebSocketSession session, Throwable failure)
                            throws Exception {
                        worker.manager().disconnected(session);
                        session.close(CloseStatus.SERVER_ERROR);
                    }
                },
                "/v1/pipecat/media/{token}");
    }
}
