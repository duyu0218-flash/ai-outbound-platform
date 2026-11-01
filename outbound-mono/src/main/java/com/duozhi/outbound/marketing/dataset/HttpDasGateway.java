package com.duozhi.outbound.marketing.dataset;

import com.duozhi.outbound.common.config.Config;
import com.duozhi.outbound.common.support.JsonSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.springframework.stereotype.Component;

/**
 * DAS over HTTP. The JVM has no AntChain SDK, so the call goes to DAS_GATEWAY_URL: the AntChain
 * OpenAPI gateway relay that performs {@code query_application_unifiedentrance_ex} with the DAS_*
 * credentials (the same request ant-platform's DasGateway sends: product_instance_id, data_set_id,
 * user_authed=true, file_index=authorization ref, params={"phone","topN"}). The body is signed with
 * HMAC-SHA256 of DAS_ACCESS_KEY_SECRET. Without the credentials and the URL the adapter reports
 * itself unconfigured and no request is ever made. One attempt only, never retried.
 */
@Component
public class HttpDasGateway implements DasGateway {
    private final Config config;
    private final ObjectMapper mapper;
    private final HttpClient client =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public HttpDasGateway(Config config, ObjectMapper mapper) {
        this.config = config;
        this.mapper = mapper;
    }

    private String value(String key, String fallback) {
        return config.get(key, fallback).trim();
    }

    @Override
    public boolean configured() {
        return !value("DAS_ACCESS_KEY_ID", "").isEmpty()
                && !value("DAS_ACCESS_KEY_SECRET", "").isEmpty()
                && !value("DAS_DATASET_ID", "service-6b60f3a18l").isEmpty()
                && value("DAS_GATEWAY_URL", "").startsWith("https://");
    }

    @Override
    public Response query(String phone, int topN, String authorizationRef) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(
                "product_instance_id",
                value("DAS_PRODUCT_INSTANCE_ID", "das-application-flow-prod"));
        body.put("data_set_id", value("DAS_DATASET_ID", "service-6b60f3a18l"));
        body.put("user_authed", true);
        body.put(
                "file_index",
                authorizationRef == null || authorizationRef.isEmpty() ? null : authorizationRef);
        body.put(
                "params",
                mapper.writeValueAsString(Map.of("phone", phone, "topN", String.valueOf(topN))));
        byte[] payload = mapper.writeValueAsBytes(body);
        String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
        String signature =
                JsonSupport.hmac(
                        value("DAS_ACCESS_KEY_SECRET", ""),
                        (timestamp + "\n" + new String(payload, StandardCharsets.UTF_8))
                                .getBytes(StandardCharsets.UTF_8));
        var request =
                HttpRequest.newBuilder(URI.create(value("DAS_GATEWAY_URL", "")))
                        .timeout(Duration.ofSeconds(15))
                        .header("Content-Type", "application/json")
                        .header("X-Das-Access-Key", value("DAS_ACCESS_KEY_ID", ""))
                        .header("X-Das-Timestamp", timestamp)
                        .header("X-Das-Signature", signature)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                        .build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode node = mapper.readTree(response.body());
        String code = node.path("result_code").asText("");
        if (code.isEmpty() && response.statusCode() >= 400)
            throw new DasException("", "gateway status " + response.statusCode());
        return new Response(
                code,
                node.path("req_msg_id").asText(""),
                node.path("data").isTextual()
                        ? node.path("data").asText()
                        : node.path("data").toString());
    }
}
