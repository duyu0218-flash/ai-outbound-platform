package com.duozhi.outbound.messaging.service;

import com.duozhi.outbound.integration.support.Support;
import jakarta.annotation.PreDestroy;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.stereotype.Component;

/** Documented platform bridge v1; supplier-specific native APIs require their own adapter. */
@Component
public class HttpMessageProtocol implements ChannelProtocol {
    private final MessageChannels channels;
    private final PinnedWebhookTransport publicHttp;
    private final HttpClient localHttp =
            HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(2))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();

    public HttpMessageProtocol(MessageChannels channels, PinnedWebhookTransport publicHttp) {
        this.channels = channels;
        this.publicHttp = publicHttp;
    }

    public String protocol() {
        return "http";
    }

    private Map<String, Object> exchange(
            Map<String, Object> channel,
            Map<String, Object> credentials,
            Map<String, Object> request)
            throws Exception {
        String endpoint = Messaging.object(channel.get("config")).get("endpoint").toString();
        channels.validateHttp(endpoint, channel.get("environment").toString());
        URI uri = URI.create(endpoint);
        byte[] body = Support.json(request);
        var headers =
                Map.of(
                        "Authorization",
                        "Bearer " + credentials.get("api_key"),
                        "Idempotency-Key",
                        request.get("operation")
                                + ":"
                                + request.getOrDefault("message_id", channel.get("id")));
        int status;
        byte[] data;
        if ("http".equals(uri.getScheme())
                && channels.internalTest(channel.get("environment").toString())) {
            var builder =
                    HttpRequest.newBuilder(uri)
                            .timeout(Duration.ofSeconds(5))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofByteArray(body));
            headers.forEach(builder::header);
            var pending = localHttp.sendAsync(builder.build(), info -> new LimitedBody());
            try {
                var response = pending.get(5, TimeUnit.SECONDS);
                status = response.statusCode();
                data = response.body();
            } finally {
                pending.cancel(true);
            }
        } else {
            var response = publicHttp.request(uri, headers, body);
            status = response.status();
            data = response.body();
        }
        if (status != 200 && status != 202)
            throw new java.io.IOException("supplier did not acknowledge request");
        var response = Support.object(data);
        if (!"1".equals(Objects.toString(response.get("version"), "")))
            throw new java.io.IOException("supplier bridge version mismatch");
        return response;
    }

    public void probe(Map<String, Object> channel, Map<String, Object> credentials)
            throws Exception {
        var response = exchange(channel, credentials, Map.of("version", "1", "operation", "probe"));
        if (!"OK".equals(response.get("status"))
                || !(response.get("forms") instanceof List<?> forms)
                || !forms.containsAll((List<?>) channel.get("forms")))
            throw new java.io.IOException("supplier capability mismatch");
    }

    public Result submit(Map<String, Object> channel, Map<String, Object> credentials, Submission s)
            throws Exception {
        var request = new LinkedHashMap<String, Object>();
        request.put("version", "1");
        request.put("operation", "submit");
        request.put("message_id", s.messageId());
        request.put("form", s.form());
        request.put("to", s.recipient());
        request.put("sender_ref", s.senderRef());
        request.put("template_ref", s.templateRef());
        request.put("content", s.content());
        request.put("asset_refs", s.assetRefs());
        return result(s.messageId(), exchange(channel, credentials, request));
    }

    public Result query(
            Map<String, Object> channel,
            Map<String, Object> credentials,
            String messageId,
            String providerId)
            throws Exception {
        return result(
                messageId,
                exchange(
                        channel,
                        credentials,
                        Map.of(
                                "version",
                                "1",
                                "operation",
                                "query",
                                "message_id",
                                messageId,
                                "provider_id",
                                providerId)));
    }

    private Result result(String messageId, Map<String, Object> response) {
        if (!messageId.equals(response.get("message_id"))) return Result.unknown();
        String state = Objects.toString(response.get("status"), "UNKNOWN"),
                provider = Objects.toString(response.get("provider_id"), "");
        if (!Set.of("ACCEPTED", "DELIVERED", "REJECTED", "FAILED", "UNKNOWN").contains(state)
                || provider.length() > 128) return Result.unknown();
        if (Set.of("ACCEPTED", "DELIVERED", "FAILED").contains(state) && provider.isBlank())
            return Result.unknown();
        return new Result(state, provider, List.of(new Part(provider, state)));
    }

    @PreDestroy
    public void close() {
        localHttp.shutdownNow();
    }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;

        public CompletionStage<byte[]> getBody() {
            return body;
        }

        public void onSubscribe(Flow.Subscription s) {
            subscription = s;
            s.request(1);
        }

        public void onNext(List<ByteBuffer> buffers) {
            for (var buffer : buffers) {
                if (buffer.remaining() > 65536 - bytes.size()) {
                    subscription.cancel();
                    body.completeExceptionally(
                            new java.io.IOException("supplier response exceeds limit"));
                    return;
                }
                byte[] b = new byte[buffer.remaining()];
                buffer.get(b);
                bytes.writeBytes(b);
            }
            subscription.request(1);
        }

        public void onError(Throwable failure) {
            body.completeExceptionally(failure);
        }

        public void onComplete() {
            body.complete(bytes.toByteArray());
        }
    }
}
