package com.duozhi.outbound.marketing.dataset;

import static org.assertj.core.api.Assertions.*;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

class DatasetImportIT extends DatasetTestSupport {
    @Test
    void gb18030CsvWithAliasHeaderIsNormalizedDeduplicatedAndFilteredWithoutClearText() {
        jdbc.update(
                "INSERT INTO phonesuppression(tenant_id,phone,reason,created_at) VALUES(?,?,?,NOW(3))",
                tenant,
                "13700000004",
                "test");
        String csv =
                "手机号码,地区,运营商,来源,备注\n"
                        + "13700000001,浙江,移动,官网,老客户\n"
                        + "137-0000-0002,江苏,联通,,\n"
                        + "+8613700000001,浙江,移动,官网,重复\n"
                        + "12345,上海,,,\n"
                        + ",北京,,,\n"
                        + "13700000004,广东,电信,,\n"
                        + "\"13700000005\",\"四川\",\"移动\",\"活动,秋季\",\"备用 13800000009\"\n";
        String ds = dataset("秋季存量");
        var v = upload(ds, csv, Charset.forName("GB18030"), null);
        assertThat(v.path("encoding").asText()).isEqualTo("GB18030");
        assertThat(v.path("state").asText()).isEqualTo("ready");
        assertThat(v.path("total_rows").asInt()).isEqualTo(7);
        assertThat(v.path("valid_count").asInt()).isEqualTo(4);
        assertThat(v.path("duplicate_count").asInt()).isEqualTo(1);
        assertThat(v.path("invalid_count").asInt()).isEqualTo(2);
        assertThat(v.path("blocked_count").asInt()).isEqualTo(1);
        String id = v.path("id").asText();

        var rows =
                admin(
                                HttpMethod.GET,
                                "/api/v1/marketing/dataset-versions/" + id + "/rows?size=50",
                                null)
                        .path("items");
        Map<Integer, com.fasterxml.jackson.databind.JsonNode> byLine = new HashMap<>();
        rows.forEach(r -> byLine.put(r.path("line_no").asInt(), r));
        assertThat(byLine.get(2).path("phone_mask").asText()).isEqualTo("137****0001");
        assertThat(byLine.get(2).path("region").asText()).isEqualTo("浙江");
        assertThat(byLine.get(2).path("carrier").asText()).isEqualTo("移动");
        assertThat(byLine.get(2).path("source").asText()).isEqualTo("官网");
        assertThat(byLine.get(2).path("attrs").asText()).contains("老客户");
        assertThat(byLine.get(4).path("status").asText()).isEqualTo("duplicate");
        assertThat(byLine.get(4).path("reason").asText()).contains("第 2 行");
        assertThat(byLine.get(5).path("reason").asText()).isEqualTo("号码格式无效");
        assertThat(byLine.get(6).path("reason").asText()).isEqualTo("号码为空");
        assertThat(byLine.get(7).path("blocked_reason").asText()).contains("通话黑名单");
        assertThat(byLine.get(8).path("source").asText()).isEqualTo("活动,秋季");
        assertThat(byLine.get(8).path("attrs").asText())
                .contains("138****0009")
                .doesNotContain("13800000009");

        // No clear-text number anywhere in the stored rows or the (cleared) staging chunks.
        var stored =
                jdbc.queryForList(
                        "SELECT CONCAT_WS('|',phone_hash,phone_cipher,phone_mask,attrs,reason) AS v FROM mk_dataset_row WHERE version_id=?",
                        id);
        assertThat(stored)
                .allSatisfy(
                        r ->
                                assertThat(r.get("v").toString())
                                        .doesNotContain("1370000000")
                                        .doesNotContain("13800000009"));
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM mk_dataset_chunk WHERE version_id=? AND cipher IS NOT NULL",
                                id))
                .isZero();
        assertThat(
                        admin(
                                        HttpMethod.GET,
                                        "/api/v1/marketing/dataset-versions/"
                                                + id
                                                + "/rows?status=blocked",
                                        null)
                                .path("total")
                                .asInt())
                .isEqualTo(1);
    }

    @Test
    void plainNumberListWithBomAndHeaderlessLayout() {
        String ds = dataset("号码清单");
        var v =
                upload(
                        ds,
                        "﻿13600000001\r\n13600000002\r\n\r\n13600000001\r\n",
                        StandardCharsets.UTF_8,
                        "AUTH-1");
        assertThat(v.path("encoding").asText()).isEqualTo("UTF-8-BOM");
        assertThat(v.path("valid_count").asInt()).isEqualTo(2);
        assertThat(v.path("duplicate_count").asInt()).isEqualTo(1);
        assertThat(v.path("authorization_ref").asText()).isEqualTo("AUTH-1");
        var second = upload(ds, "userTel\n13600000003\n", StandardCharsets.UTF_8, null);
        assertThat(second.path("version_no").asInt()).isEqualTo(2);
        assertThat(second.path("valid_count").asInt()).isEqualTo(1);
        assertThat(
                        req(
                                        admin,
                                        HttpMethod.POST,
                                        "/api/v1/marketing/datasets/" + ds + "/versions",
                                        Map.of())
                                .getStatusCode()
                                .value())
                .isNotEqualTo(200);
        assertThat(
                        req(agent, HttpMethod.GET, "/api/v1/marketing/datasets", null)
                                .getStatusCode()
                                .value())
                .isEqualTo(403);
    }

    @Test
    void chunkedImportResumesAfterInterruptionAndDeduplicatesAcrossChunks() {
        String ds = imports.createDataset(tenant, "大文件", "", 0).get("id").toString();
        StringBuilder csv = new StringBuilder("mobile\n");
        int total = DatasetImports.CHUNK_ROWS * 2 + 500;
        for (int i = 0; i < total - 1; i++) csv.append(13500000000L + i).append('\n');
        csv.append(13500000000L).append('\n'); // last row repeats the first: chunk 3 vs chunk 1
        var v =
                imports.upload(
                        tenant,
                        ds,
                        "big.csv",
                        csv.toString().getBytes(StandardCharsets.UTF_8),
                        "AUTH-9",
                        "",
                        0);
        String id = v.get("id").toString();
        assertThat(((Number) v.get("chunk_total")).intValue()).isEqualTo(3);
        assertThat(imports.processOne(id)).isTrue();
        var mid = imports.version(tenant, id);
        assertThat(mid.get("state")).isEqualTo("importing");
        assertThat(((Number) mid.get("chunk_done")).intValue()).isEqualTo(1);
        // "Restart": a fresh run continues with the remaining chunks only.
        assertThat(imports.process(id, 100)).isEqualTo(2);
        assertThat(imports.processOne(id)).isFalse();
        var done = imports.version(tenant, id);
        assertThat(done.get("state")).isEqualTo("ready");
        assertThat(((Number) done.get("valid_count")).intValue()).isEqualTo(total - 1);
        assertThat(((Number) done.get("duplicate_count")).intValue()).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM mk_dataset_row WHERE version_id=?", id))
                .isEqualTo(total);
    }

    @Test
    void versionWithoutAuthorizationCannotBeMatched() {
        String ds = dataset("无授权");
        var v = upload(ds, "phone\n13300000001\n", StandardCharsets.UTF_8, null);
        String partner = partner(MatchAdapter.MOCK);
        var refused =
                req(
                        admin,
                        HttpMethod.POST,
                        "/api/v1/marketing/match-jobs",
                        Map.of("partnerId", partner, "versionId", v.path("id").asText()));
        assertThat(refused.getStatusCode().value()).isEqualTo(409);
        assertThat(refused.getBody().toString()).contains("授权凭证");
        admin(
                HttpMethod.PUT,
                "/api/v1/marketing/dataset-versions/" + v.path("id").asText() + "/authorization",
                Map.of("authorizationRef", "CONTRACT/2026-11"));
        assertThat(submit(partner, v.path("id").asText()).path("total").asInt()).isEqualTo(1);
    }
}
