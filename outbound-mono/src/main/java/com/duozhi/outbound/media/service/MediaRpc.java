package com.duozhi.outbound.media.service;

import com.duozhi.outbound.integration.support.Support;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;

/** Authenticated, bounded RPC shared by independent media workers and the controller. */
public final class MediaRpc {
    private static final HttpClient HTTP = Support.client();

    private MediaRpc() {}

    public static Map<String, Object> request(
            String endpoint, String path, Map<String, Object> body, String token, double seconds)
            throws Exception {
        URI base = Support.endpoint(endpoint);
        if (base.getQuery() != null
                || !java.util.Set.of("", "/").contains(base.getPath())
                || (!"https".equals(base.getScheme()) && !"127.0.0.1".equals(base.getHost())))
            throw new IllegalArgumentException("media RPC requires HTTPS outside loopback");
        if (token.length() < 32 || !Double.isFinite(seconds) || seconds < .1 || seconds > 60)
            throw new IllegalArgumentException("invalid media RPC credential or deadline");
        var request =
                HttpRequest.newBuilder(base.resolve(path))
                        .timeout(Duration.ofMillis((long) (seconds * 1000)))
                        .header("Authorization", "Bearer " + token);
        if (body == null) request.GET();
        else
            request.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(Support.json(body)));
        var response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        try (var input = Support.timed(response.body(), (long) (seconds * 1000))) {
            byte[] bytes = Support.read(input, 1024 * 1024);
            if (response.statusCode() != 200)
                throw new Support.Failure(response.statusCode(), "media RPC rejected");
            return Support.object(bytes);
        }
    }
}
