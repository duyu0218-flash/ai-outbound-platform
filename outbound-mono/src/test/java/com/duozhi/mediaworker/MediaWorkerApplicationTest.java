package com.duozhi.mediaworker;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.duozhi.outbound.integration.config.Settings;
import com.duozhi.outbound.integration.dao.LocalLedger;
import com.duozhi.outbound.integration.support.Support;
import com.duozhi.outbound.media.service.MediaCluster;
import com.duozhi.outbound.media.service.MediaRpc;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;

class MediaWorkerApplicationTest {
    private static final String TOKEN = "isolated-media-worker-rpc-fixture-secret-000000";

    @Test
    void isolatedWorkerHasNoDatabaseAndFencesEpochAndClosedSessionsOverHttp() throws Exception {
        var control =
                com.sun.net.httpserver.HttpServer.create(
                        new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        var callbacks = new java.util.concurrent.atomic.AtomicInteger();
        control.createContext(
                "/v1/internal/media-events",
                exchange -> {
                    boolean authorized =
                            ("Bearer " + TOKEN)
                                    .equals(exchange.getRequestHeaders().getFirst("Authorization"));
                    exchange.getRequestBody().readAllBytes();
                    if (authorized) callbacks.incrementAndGet();
                    byte[] reply = Support.json(Map.of("accepted", authorized));
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(authorized ? 200 : 401, reply.length);
                    exchange.getResponseBody().write(reply);
                    exchange.close();
                });
        control.start();
        try (var context =
                SpringApplication.run(
                        MediaWorkerApplication.class,
                        "--server.port=0",
                        "--server.address=127.0.0.1",
                        "--MEDIA_WORKER_ID=test-worker",
                        "--MEDIA_WORKER_CAPACITY=2",
                        "--MEDIA_RPC_TOKEN=" + TOKEN,
                        "--MEDIA_CONTROL_ENDPOINT=http://127.0.0.1:"
                                + control.getAddress().getPort(),
                        "--PIPECAT_OPENAI_API_KEY=synthetic-not-used",
                        "--logging.level.root=ERROR")) {
            assertThat(context.containsBean("dataSource")).isFalse();
            assertThat(context.containsBean("taskWorker")).isFalse();
            String endpoint =
                    "http://127.0.0.1:"
                            + ((ServletWebServerApplicationContext) context)
                                    .getWebServer()
                                    .getPort();
            var denied =
                    HttpClient.newHttpClient()
                            .send(
                                    HttpRequest.newBuilder(URI.create(endpoint + "/internal/state"))
                                            .GET()
                                            .build(),
                                    HttpResponse.BodyHandlers.ofString());
            assertThat(denied.statusCode()).isEqualTo(401);
            var state = MediaRpc.request(endpoint, "/internal/state", null, TOKEN, 5);
            assertThat(state)
                    .containsEntry("worker_id", "test-worker")
                    .containsEntry("ready", true);
            var ledger = mock(LocalLedger.class);
            doReturn(List.of()).when(ledger).read(any());
            var roster =
                    new Settings(
                            Map.of(
                                    "MEDIA_WORKER_TRANSPORT",
                                    "remote",
                                    "MEDIA_RPC_TOKEN",
                                    TOKEN,
                                    "PIPECAT_MAX_ACTIVE_SESSIONS",
                                    "2",
                                    "MEDIA_WORKERS_JSON",
                                    new String(
                                            Support.json(
                                                    List.of(
                                                            Map.of(
                                                                    "id",
                                                                    "test-worker",
                                                                    "endpoint",
                                                                    endpoint,
                                                                    "ws_base",
                                                                    endpoint.replace(
                                                                                    "http://",
                                                                                    "ws://")
                                                                            + "/v1/pipecat/media",
                                                                    "capacity",
                                                                    2))),
                                            java.nio.charset.StandardCharsets.UTF_8)));
            try (var cluster = new MediaCluster(roster, ledger, (url, payload) -> {})) {
                var owned =
                        cluster.create(
                                "cluster-call",
                                "http://127.0.0.1:1/speech",
                                "http://127.0.0.1:1/media",
                                Map.of("attempt", 1));
                assertThat(cluster.url(owned))
                        .startsWith(endpoint.replace("http://", "ws://") + "/v1/pipecat/media/");
                assertThat(cluster.manager("a".repeat(64))).isNull();
            }
            String call = UUID.randomUUID().toString(), session = UUID.randomUUID().toString();
            var command = new LinkedHashMap<String, Object>();
            command.putAll(
                    Map.of(
                            "action",
                            "create",
                            "epoch",
                            state.get("epoch"),
                            "call_id",
                            call,
                            "session_id",
                            session,
                            "attempt",
                            1,
                            "expires_at",
                            System.currentTimeMillis() / 1000.0 + 30,
                            "session",
                            Map.of(
                                    "call_id",
                                    call,
                                    "session_id",
                                    session,
                                    "token",
                                    "a".repeat(64),
                                    "speech_webhook_url",
                                    "http://127.0.0.1:1/speech",
                                    "media_webhook_url",
                                    "http://127.0.0.1:1/media",
                                    "metadata",
                                    Map.of("attempt", 1))));
            assertThat(MediaRpc.request(endpoint, "/internal/command", command, TOKEN, 5))
                    .containsEntry("created", true);
            assertThat(MediaRpc.request(endpoint, "/internal/command", command, TOKEN, 5))
                    .containsEntry("created", true);
            command.put("epoch", "old-epoch");
            assertThatThrownBy(
                            () ->
                                    MediaRpc.request(
                                            endpoint, "/internal/command", command, TOKEN, 5))
                    .isInstanceOfSatisfying(
                            Support.Failure.class, e -> assertThat(e.status).isEqualTo(409));
            command.put("epoch", state.get("epoch"));
            command.put("action", "close");
            assertThat(MediaRpc.request(endpoint, "/internal/command", command, TOKEN, 5))
                    .containsEntry("closed", true);
            assertThat(callbacks.get()).isPositive();
            command.put("action", "create");
            assertThatThrownBy(
                            () ->
                                    MediaRpc.request(
                                            endpoint, "/internal/command", command, TOKEN, 5))
                    .isInstanceOfSatisfying(
                            Support.Failure.class, e -> assertThat(e.status).isEqualTo(409));
        } finally {
            control.stop(0);
        }
    }
}
