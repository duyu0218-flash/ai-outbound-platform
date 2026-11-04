package com.duozhi.outbound.operations.controller;

import com.duozhi.outbound.auth.domain.Identity;
import com.duozhi.outbound.common.config.Config;
import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.common.support.JsonSupport;
import com.duozhi.outbound.workspace.service.WorkspaceDocuments;
import jakarta.servlet.http.HttpServletRequest;
import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.apache.commons.csv.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/ops/voice-statement")
public class VoiceStatements {
    private final WorkspaceDocuments docs;
    private final Config config;

    public VoiceStatements(WorkspaceDocuments docs, Config config) {
        this.docs = docs;
        this.config = config;
    }

    private String mode() {
        return config.production() ? "statement" : "simulation";
    }

    private Identity admin(HttpServletRequest r) {
        var i = Identity.from(r);
        i.requireUser();
        i.admin("operations:write");
        return i;
    }

    public Map<String, Object> balance(long tenant) {
        var result =
                docs.jdbc.queryForMap(
                        "SELECT COUNT(CASE WHEN kind='opening' THEN 1 END) AS openings,SUM(signed_amount) AS amount FROM voice_statement_entry WHERE tenant_id=? AND source_mode=?",
                        tenant,
                        mode());
        result.put("available", ((Number) result.get("openings")).intValue() == 1);
        result.put("mode", mode());
        result.put("currency", "CNY");
        return result;
    }

    @GetMapping
    public Object list(HttpServletRequest r) {
        var i = admin(r);
        return Map.of(
                "balance",
                balance(i.tenant()),
                "rows",
                docs.jdbc.queryForList(
                        "SELECT reference_no,kind,signed_amount,currency,call_id,attempt,bill_seconds,unit_seconds,unit_price,tariff_version,settlement_source,posted_at FROM voice_statement_entry WHERE tenant_id=? AND source_mode=? ORDER BY posted_at DESC,id DESC LIMIT 200",
                        i.tenant(),
                        mode()));
    }

    private static final List<String> headers =
            List.of(
                    "流水号", "类型", "金额", "通话ID", "拨打序号", "计费秒数", "计费单位秒", "每单位费率", "费率版本", "结算来源",
                    "入账时间UTC");

    private static BigDecimal decimal(String s) {
        try {
            BigDecimal v = new BigDecimal(s);
            if (v.scale() > 6 || v.abs().compareTo(new BigDecimal("9999999999")) > 0)
                throw new NumberFormatException();
            return v;
        } catch (NumberFormatException e) {
            throw new ApiError(422, "金额或费率格式无效");
        }
    }

    private static int integer(String s, int min, int max) {
        try {
            int n = Integer.parseInt(s);
            if (n < min || n > max) throw new NumberFormatException();
            return n;
        } catch (NumberFormatException e) {
            throw new ApiError(422, "计费单位、时长或拨打序号无效");
        }
    }

    @PostMapping(value = "/import", consumes = "multipart/form-data")
    public Object ingest(@RequestParam
    MultipartFile file, HttpServletRequest request)
            throws IOException {
        var i = admin(request);
        if (file.isEmpty() || file.getSize() > 1024 * 1024) throw new ApiError(422, "账单文件须小于1MB");
        String text = new String(file.getBytes(), StandardCharsets.UTF_8);
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        var records = new ArrayList<List<String>>();
        try (var csv = CSVFormat.DEFAULT.parse(new StringReader(text))) {
            var it = csv.iterator();
            if (!it.hasNext() || !it.next().toList().equals(headers))
                throw new ApiError(422, "请使用语音账单模板");
            while (it.hasNext()) {
                var row = it.next().toList();
                if (row.size() != headers.size() || records.size() >= 1000)
                    throw new ApiError(422, "行格式错误或超过1000行");
                records.add(row);
            }
        }
        if (records.isEmpty()) throw new ApiError(422, "账单没有明细");
        return docs.tx.serialized(
                () -> {
                    docs.jdbc.queryForObject(
                            "SELECT id FROM tenant WHERE id=? FOR UPDATE", Long.class, i.tenant());
                    int added = 0;
                    for (var v : records) {
                        String ref = v.get(0),
                                kind = v.get(1),
                                source = v.get(9),
                                tariff = v.get(8);
                        if (ref.isBlank()
                                || ref.length() > 128
                                || source.isBlank()
                                || source.length() > 160
                                || tariff.length() > 100
                                || !Set.of("opening", "credit", "charge", "adjust").contains(kind))
                            throw new ApiError(422, "流水号、结算来源或类型无效");
                        String hash = JsonSupport.sha(docs.json.json(v));
                        var old =
                                docs.one(
                                        "SELECT row_hash FROM voice_statement_entry WHERE tenant_id=? AND source_mode=? AND reference_no=?",
                                        i.tenant(),
                                        mode(),
                                        ref);
                        if (old != null) {
                            if (!hash.equals(old.get("row_hash")))
                                throw new ApiError(409, "相同流水号对应不同账单内容");
                            continue;
                        }
                        if (kind.equals("opening")
                                && Boolean.TRUE.equals(balance(i.tenant()).get("available")))
                            throw new ApiError(409, "已经录入期初余额");
                        BigDecimal amount = decimal(v.get(2)), price = null;
                        Integer attempt = null, seconds = null, unit = null;
                        String call = null;
                        if (kind.equals("charge")) {
                            call = v.get(3);
                            attempt = integer(v.get(4), 1, 10000);
                            seconds = integer(v.get(5), 0, 864000);
                            unit = integer(v.get(6), 1, 86400);
                            price = decimal(v.get(7));
                            if (amount.signum() > 0
                                    || price.signum() < 0
                                    || tariff.isBlank()
                                    || docs.one(
                                                    "SELECT id FROM callsession WHERE tenant_id=? AND id=? AND attempts>=?",
                                                    i.tenant(),
                                                    call,
                                                    attempt)
                                            == null)
                                throw new ApiError(422, "通话计费需匹配本租户的拨打尝试、非正扣费和费率版本");
                        } else if (kind.equals("credit") && amount.signum() <= 0)
                            throw new ApiError(422, "充值入账应为正数");
                        LocalDateTime at;
                        try {
                            at = LocalDateTime.parse(v.get(10));
                        } catch (DateTimeException e) {
                            throw new ApiError(422, "入账时间需使用 UTC 日期时间");
                        }
                        docs.jdbc.update(
                                "INSERT INTO voice_statement_entry(tenant_id,source_mode,reference_no,kind,currency,signed_amount,call_id,attempt,bill_seconds,unit_seconds,unit_price,tariff_version,settlement_source,posted_at,created_by,row_hash) VALUES(?,?,?,?,'CNY',?,?,?,?,?,?,?,?,?,?,?)",
                                i.tenant(),
                                mode(),
                                ref,
                                kind,
                                amount,
                                call,
                                attempt,
                                seconds,
                                unit,
                                price,
                                tariff,
                                source,
                                at,
                                i.userId(),
                                hash);
                        added++;
                    }
                    return Map.of(
                            "imported",
                            added,
                            "duplicates",
                            records.size() - added,
                            "balance",
                            balance(i.tenant()));
                });
    }
}
