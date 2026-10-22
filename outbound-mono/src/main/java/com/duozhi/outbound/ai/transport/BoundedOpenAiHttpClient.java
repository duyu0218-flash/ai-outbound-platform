package com.duozhi.outbound.ai.transport;

import com.duozhi.outbound.ai.service.AccountQuota;
import com.duozhi.outbound.integration.support.Support;
import com.openai.core.RequestOptions;
import com.openai.core.http.Headers;
import com.openai.core.http.HttpRequest;
import com.openai.core.http.HttpResponse;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/** Uses the shared JDK transport and the validated endpoint for Spring AI 2's OpenAI SDK. */
public final class BoundedOpenAiHttpClient implements com.openai.core.http.HttpClient {
    private final HttpClient client;
    private final URI endpoint;
    private final String apiKey;
    private final long timeout;
    private final AccountQuota quota;

    public BoundedOpenAiHttpClient(
            HttpClient client, URI endpoint, String apiKey, long timeout, AccountQuota quota) {
        this.client = client;
        this.endpoint = endpoint;
        this.apiKey = apiKey;
        this.timeout = timeout;
        this.quota = quota;
    }

    @Override
    public HttpResponse execute(HttpRequest request, RequestOptions options) {
        try {
            var output = new ByteArrayOutputStream();
            request.body().writeTo(output);
            var wire =
                    java.net.http.HttpRequest.newBuilder(endpoint)
                            .timeout(Duration.ofMillis(timeout))
                            .header("Authorization", "Bearer " + apiKey)
                            .header("Content-Type", "application/json")
                            .POST(
                                    java.net.http.HttpRequest.BodyPublishers.ofByteArray(
                                            output.toByteArray()))
                            .build();
            var response =
                    client.send(wire, java.net.http.HttpResponse.BodyHandlers.ofInputStream());
            byte[] bytes;
            try (var body = Support.timed(response.body(), timeout)) {
                if (response.statusCode() == 429 || response.statusCode() == 503) {
                    double seconds;
                    try {
                        seconds =
                                Double.parseDouble(
                                        response.headers().firstValue("Retry-After").orElse("5"));
                    } catch (NumberFormatException invalid) {
                        seconds = 5;
                    }
                    if (!Double.isFinite(seconds)) seconds = 5;
                    quota.block(Math.max(1, Math.min(300, seconds)));
                }
                if (response.statusCode() < 200 || response.statusCode() >= 300)
                    throw new Support.Failure(502, "external LLM request failed");
                bytes = body.readNBytes(1048577);
                if (bytes.length > 1048576)
                    throw new Support.Failure(502, "response exceeds maximum size");
            }
            var headers = Headers.builder().putAll(response.headers().map()).build();
            return new HttpResponse() {
                private final ByteArrayInputStream body = new ByteArrayInputStream(bytes);

                public int statusCode() {
                    return response.statusCode();
                }

                public Headers headers() {
                    return headers;
                }

                public java.io.InputStream body() {
                    return body;
                }

                public void close() {}
            };
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new Support.Failure(502, "external LLM request interrupted");
        } catch (Support.Failure failure) {
            throw failure;
        } catch (Exception failure) {
            throw new Support.Failure(502, "external LLM request failed");
        }
    }

    @Override
    public CompletableFuture<HttpResponse> executeAsync(
            HttpRequest request, RequestOptions options) {
        // This adapter implements the application's existing blocking chat contract only.
        return CompletableFuture.failedFuture(
                new UnsupportedOperationException("streaming chat transport is not configured"));
    }

    @Override
    public void close() {
        /* The application owns the shared JDK HTTP client. */
    }
}
