package com.duozhi.outbound.marketing.dataset;

import static org.assertj.core.api.Assertions.*;

import com.duozhi.outbound.marketing.MarketingTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.Charset;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.util.LinkedMultiValueMap;

/** Helpers shared by the P1 tests: uploads, partners, jobs. */
abstract class DatasetTestSupport extends MarketingTestBase {
    @Autowired
    DatasetImports imports;
    @Autowired
    Matching matching;
    @Autowired
    Segments segments;
    @Autowired
    SendTasks sends;

    ResponseEntity<JsonNode> multipart(
            String path, byte[] content, String name, Map<String, String> fields) {
        var form = new LinkedMultiValueMap<String, Object>();
        form.add(
                "file",
                new ByteArrayResource(content) {
                    @Override
                    public String getFilename() {
                        return name;
                    }
                });
        fields.forEach(form::add);
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(admin);
        h.setContentType(MediaType.MULTIPART_FORM_DATA);
        return http.exchange(path, HttpMethod.POST, new HttpEntity<>(form, h), JsonNode.class);
    }

    String dataset(String name) {
        return admin(HttpMethod.POST, "/api/v1/marketing/datasets", Map.of("name", name))
                .path("id")
                .asText();
    }

    JsonNode upload(String dataset, String text, Charset charset, String auth) {
        Map<String, String> fields = auth == null ? Map.of() : Map.of("authorizationRef", auth);
        return ok(
                multipart(
                        "/api/v1/marketing/datasets/" + dataset + "/versions",
                        text.getBytes(charset),
                        "data.csv",
                        fields));
    }

    /** A ready version with {@code count} distinct mainland numbers starting at {@code base}. */
    String version(long base, int count) {
        StringBuilder csv = new StringBuilder("phone,region\n");
        for (int i = 0; i < count; i++) csv.append(base + i).append(",浙江\n");
        var v =
                upload(
                        dataset("测试包"),
                        csv.toString(),
                        java.nio.charset.StandardCharsets.UTF_8,
                        "AUTH-" + tenant);
        assertThat(v.path("state").asText()).isEqualTo("ready");
        return v.path("id").asText();
    }

    String partner(String adapter) {
        return admin(
                        HttpMethod.POST,
                        "/api/v1/marketing/partners",
                        Map.of(
                                "code",
                                adapter + "-" + UUID.randomUUID().toString().substring(0, 8),
                                "name",
                                adapter + " 合作方",
                                "adapter",
                                adapter))
                .path("id")
                .asText();
    }

    JsonNode submit(String partner, String version) {
        return admin(
                HttpMethod.POST,
                "/api/v1/marketing/match-jobs",
                Map.of("partnerId", partner, "versionId", version));
    }

    void runToEnd(String job) {
        for (int i = 0; i < 200 && matching.step(job); i++) {}
    }

    long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }
}
