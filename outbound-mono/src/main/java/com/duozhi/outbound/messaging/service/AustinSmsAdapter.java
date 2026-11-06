package com.duozhi.outbound.messaging.service;

import com.duozhi.outbound.common.config.Config;
import com.duozhi.outbound.common.exception.ApiError;
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

/** Austin orchestrates; OutboundSmsGateScript dispatches the original message via SMSGate. */
@Component
public class AustinSmsAdapter implements MessageAdapter {
    public static final String ID = "austin_smsgate";
    private final Config config;
    private final HttpClient http =
            HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(3))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();

    public AustinSmsAdapter(Config config) {
        this.config = config;
    }

    public String id() {
        return ID;
    }

    public Set<String> forms() {
        return Set.of("sms");
    }

    public static long templateId(Object value) {
        try {
            String raw = Objects.toString(value, "");
            if (!raw.matches("[1-9][0-9]{0,18}")) throw new NumberFormatException();
            return Long.parseLong(raw);
        } catch (NumberFormatException invalid) {
            throw new ApiError(422, "Austin 模板编号必须为正整数（最大 64 位）");
        }
    }

    public boolean configured() {
        return config.flag("SMS_AUSTIN_ENABLED", false)
                && config.flag("SMS_SMSGATE_ENABLED", false)
                && !config.get("SMS_AUSTIN_SEND_URL", "").isBlank()
                && config.get("SMS_BRIDGE_TOKEN", "").length() >= 32;
    }

    public Result submit(Submission s) throws Exception {
        if (!configured()) throw new ApiError(503, "Austin 短信链路尚未配置");
        if (!forms().contains(s.form())) throw new ApiError(422, "Austin＋SMSGate 仅支持短信");
        URI uri = URI.create(config.get("SMS_AUSTIN_SEND_URL", ""));
        if (uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getFragment() != null
                || !("https".equals(uri.getScheme())
                        || (!config.production() && "http".equals(uri.getScheme()))))
            throw new ApiError(503, "Austin 地址无效；生产环境要求 HTTPS");
        long template = templateId(s.options().get("austin_template_id"));
        var request =
                Map.of(
                        "code",
                        "send",
                        "messageTemplateId",
                        template,
                        "messageParam",
                        Map.of(
                                "bizId",
                                s.messageId(),
                                "receiver",
                                s.recipient().replaceFirst("^\\+86", ""),
                                "variables",
                                Map.of("content", s.content())));
        var builder =
                HttpRequest.newBuilder(uri)
                        .timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(Support.json(request)));
        String token = config.get("SMS_AUSTIN_API_TOKEN", "");
        if (!token.isBlank()) builder.header("Authorization", "Bearer " + token);
        var pending = http.sendAsync(builder.build(), info -> new LimitedBody());
        try {
            var response = pending.get(10, TimeUnit.SECONDS);
            byte[] data = response.body();
            if (response.statusCode() != 200) return new Result("UNKNOWN", "", 0, false);
            var body = Support.object(data);
            // Austin's success means queue acceptance, never handset delivery.
            if (!"00000".equals(body.get("code"))) return new Result("UNKNOWN", "", 0, false);
            if (!(body.get("data") instanceof List<?> tasks) || tasks.size() != 1)
                return new Result("UNKNOWN", "", 0, false);
            var task = Support.map(tasks.getFirst());
            String provider = Objects.toString(task.get("messageId"), "");
            if (!s.messageId().equals(task.get("bizId"))
                    || provider.isBlank()
                    || provider.length() > 128) return new Result("UNKNOWN", "", 0, false);
            return new Result("ACCEPTED", provider, 0, false);
        } finally {
            pending.cancel(true);
        }
    }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;

        public CompletionStage<byte[]> getBody() {
            return body;
        }

        public void onSubscribe(Flow.Subscription value) {
            subscription = value;
            value.request(1);
        }

        public void onNext(List<ByteBuffer> buffers) {
            for (var buffer : buffers) {
                if (buffer.remaining() > 65536 - bytes.size()) {
                    subscription.cancel();
                    body.completeExceptionally(
                            new java.io.IOException("Austin response exceeds limit"));
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }

        public void onError(Throwable error) {
            body.completeExceptionally(error);
        }

        public void onComplete() {
            body.complete(bytes.toByteArray());
        }
    }

    public Result query(String id) {
        return new Result("UNKNOWN", "", 0, false);
    }

    @PreDestroy
    public void close() {
        http.shutdownNow();
    }
}
