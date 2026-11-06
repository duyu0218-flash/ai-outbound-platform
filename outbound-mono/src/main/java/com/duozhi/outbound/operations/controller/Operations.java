package com.duozhi.outbound.operations.controller;

import com.duozhi.outbound.auth.domain.Identity;
import com.duozhi.outbound.common.dao.Store;
import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.common.service.Transactions;
import com.duozhi.outbound.common.support.Ascii;
import com.duozhi.outbound.common.support.JsonSupport;
import com.duozhi.outbound.operations.dao.OperationsStore;
import com.duozhi.outbound.operations.security.OperationsPhoneMask;
import com.duozhi.outbound.operations.service.OperationsAccess;
import com.duozhi.outbound.system.service.Audit;
import com.duozhi.outbound.system.service.Settings;
import jakarta.servlet.http.HttpServletRequest;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.apache.commons.csv.*;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** Operations workflows share one tenant/scope predicate across search, totals and export. */
@RestController
@RequestMapping("/api/v1/ops")
public class Operations {
    @org.springframework.beans.factory.annotation.Autowired
    private com.duozhi.outbound.auth.service.AccountSecurity accountSecurity;

    @org.springframework.beans.factory.annotation.Autowired
    private com.duozhi.outbound.workspace.service.WorkspaceDocuments workspaceDocuments;

    @org.springframework.beans.factory.annotation.Autowired
    private com.duozhi.outbound.operations.service.FormReviews formReviews;

    @org.springframework.beans.factory.annotation.Autowired
    private SeatBehavior seatBehavior;

    @org.springframework.beans.factory.annotation.Autowired
    private com.duozhi.outbound.operations.service.OutboundRules outboundRules;

    @org.springframework.beans.factory.annotation.Autowired
    private com.duozhi.outbound.operations.service.CompetitiveMetrics competitiveMetrics;

    @org.springframework.beans.factory.annotation.Autowired
    private com.duozhi.outbound.common.config.Config config;

    private final OperationsStore db;
    private final OperationsAccess access;
    private final Store store;
    private final Transactions tx;
    private final JsonSupport json;
    private final Audit audit;
    private final Settings settings;
    private final OperationsWorkforce workforce;

    public Operations(
            OperationsStore db,
            OperationsAccess access,
            Store store,
            Transactions tx,
            JsonSupport json,
            Audit audit,
            Settings settings,
            OperationsWorkforce workforce) {
        this.db = db;
        this.access = access;
        this.store = store;
        this.tx = tx;
        this.json = json;
        this.audit = audit;
        this.settings = settings;
        this.workforce = workforce;
    }

    private Identity actor(HttpServletRequest r) {
        Identity i = Identity.from(r);
        i.requireUser();
        return i;
    }

    private static long number(Object v, long min, long max) {
        try {
            long n = Long.parseLong(v.toString());
            if (n < min || n > max) throw new IllegalArgumentException();
            return n;
        } catch (Exception e) {
            throw new ApiError(422, "数值范围无效");
        }
    }

    private static String text(Object v, int max) {
        String s = Objects.toString(v, "").trim();
        if (s.length() > max) throw new ApiError(422, "内容过长");
        return s;
    }

    private static String choice(Object v, Set<String> allowed) {
        String s = Objects.toString(v, "");
        if (!allowed.contains(s)) throw new ApiError(422, "不支持的选项");
        return s;
    }

    static LocalDateTime time(Object v) {
        try {
            return OffsetDateTime.parse(v.toString())
                    .withOffsetSameInstant(ZoneOffset.UTC)
                    .toLocalDateTime();
        } catch (Exception e) {
            throw new ApiError(422, "时间须包含时区");
        }
    }

    private Map<String, Object> base(Identity i) {
        return access.scope(i);
    }

    private Map<String, Object> paged(Identity i, Map<String, String> q) {
        var p = base(i);
        int page = (int) number(q.getOrDefault("page", "1"), 1, 1000000),
                size = (int) number(q.getOrDefault("size", "20"), 1, 200);
        p.put("limit", size);
        p.put("offset", (page - 1) * size);
        return p;
    }

    private Map<String, Object> filters(Identity i, Map<String, String> q) {
        var p = paged(i, q);
        for (String k :
                List.of(
                        "contact_id",
                        "campaign_id",
                        "agent_id",
                        "owner_id",
                        "line_id",
                        "team_id",
                        "script_id"))
            if (q.containsKey(k) && !q.get(k).isBlank())
                p.put(k, number(q.get(k), 1, Integer.MAX_VALUE));
        for (String key : List.of("actor_id", "stat_team_id"))
            if (!q.getOrDefault(key, "").isBlank())
                p.put(key, number(q.get(key), 0, Integer.MAX_VALUE));
        for (String key : List.of("city", "line_group"))
            if (!q.getOrDefault(key, "").isBlank()) p.put(key, text(q.get(key), 100));
        if (!q.getOrDefault("task_status", "").isBlank())
            p.put(
                    "task_status",
                    choice(
                            q.get("task_status"),
                            Set.of(
                                    "draft",
                                    "running",
                                    "completed",
                                    "paused",
                                    "stopped",
                                    "failed",
                                    "prepared")));
        if (q.containsKey("flow_version_id") && !q.get("flow_version_id").isBlank())
            p.put("flow_version_id", number(q.get("flow_version_id"), 0, Integer.MAX_VALUE));
        for (String k :
                List.of(
                        "phone",
                        "intent",
                        "review_state",
                        "stage",
                        "tag",
                        "id",
                        "node",
                        "grade",
                        "hangup_reason",
                        "remark",
                        "gender",
                        "batch_id"))
            if (q.containsKey(k) && !q.get(k).isBlank()) p.put(k, text(q.get(k), 120));
        if (!q.getOrDefault("reviewer_id", "").isBlank())
            p.put("reviewer_id", number(q.get("reviewer_id"), 1, Integer.MAX_VALUE));
        if (!q.getOrDefault("pool", "").isBlank())
            p.put("pool", choice(q.get("pool"), Set.of("public", "assigned")));
        for (String key : List.of("entered", "taken", "successful", "lost", "manual", "answered"))
            if (!q.getOrDefault(key, "").isBlank())
                p.put(key, Boolean.parseBoolean(choice(q.get(key), Set.of("true", "false"))));
        if (q.containsKey("status") && !q.get("status").isBlank())
            p.put("status", store.encode("callsession", "status", q.get("status")));
        for (String k : List.of("from", "until"))
            if (q.containsKey(k) && !q.get(k).isBlank()) p.put(k, time(q.get(k)));
        if (p.get("from") != null
                && p.get("until") != null
                && ((LocalDateTime) p.get("from")).isAfter((LocalDateTime) p.get("until")))
            throw new ApiError(422, "开始时间不能晚于结束时间");
        for (String k : List.of("min_seconds", "max_seconds"))
            if (q.containsKey(k) && !q.get(k).isBlank()) p.put(k, number(q.get(k), 0, 86400));
        if (p.get("min_seconds") != null
                && p.get("max_seconds") != null
                && (Long) p.get("min_seconds") > (Long) p.get("max_seconds"))
            throw new ApiError(422, "通话时长范围无效");
        for (String key : List.of("total_ranges", "ai_ranges", "human_ranges")) {
            String raw = q.getOrDefault(key, "");
            if (raw.isBlank()) continue;
            String[] parts = raw.split(",", -1);
            if (parts.length > 8) throw new ApiError(422, "每种时长最多 8 个区间");
            List<Map<String, Object>> ranges = new ArrayList<>();
            for (String part : parts) {
                String[] bounds = part.split("-", -1);
                if (bounds.length != 2) throw new ApiError(422, "时长区间需要起止秒数");
                long min = number(bounds[0], 0, 86400), max = number(bounds[1], min, 86400);
                ranges.add(Map.of("min", min, "max", max));
            }
            p.put(key, ranges);
        }
        if (q.containsKey("handoff") && !q.get("handoff").isBlank())
            p.put(
                    "handoff",
                    Boolean.parseBoolean(choice(q.get("handoff"), Set.of("true", "false"))));
        return p;
    }

    private Object callOutput(Map<String, Object> row, Identity actor) {
        var r = new LinkedHashMap<>(row);
        for (String k : List.of("status", "mode"))
            if (r.get(k) != null) r.put(k, Ascii.lower(r.get(k).toString()));
        if (r.get("campaign_id") != null && r.get("tenant_id") instanceof Number tenant) {
            var snapshot =
                    workspaceDocuments.campaignSnapshot(tenant.longValue(), r.get("campaign_id"));
            if (snapshot != null
                    && !"admin".equals(actor.requireUser().get("role"))
                    && Boolean.TRUE.equals(
                            com.duozhi.outbound.ai.service.FlowSettings.from(
                                            com.duozhi.outbound.workspace.service.WorkspaceDocuments
                                                    .map(snapshot.getOrDefault("policy", Map.of())))
                                    .get("handoff_hide_dialogue")))
                r = new LinkedHashMap<>((Map<String, Object>) OperationsPhoneMask.scrubDialogue(r));
            if (snapshot != null
                    && Boolean.TRUE.equals(
                            com.duozhi.outbound.workspace.service.WorkspaceDocuments.map(
                                            snapshot.getOrDefault("task_options", Map.of()))
                                    .get("mask_numbers"))) return OperationsPhoneMask.mask(r);
        }
        return r;
    }

    @GetMapping("/me")
    public Map<String, Object> me(HttpServletRequest r) {
        Identity i = actor(r);
        return JsonSupport.map(
                "all_scope",
                access.all(i),
                "manager",
                access.manager(i),
                "administrator",
                "admin".equals(i.user().get("role")),
                "can_export",
                access.flag(i, "can_export"),
                "can_unmask",
                access.flag(i, "can_unmask"),
                "can_record",
                access.flag(i, "can_record"),
                "users",
                access.users(i));
    }

    @GetMapping("/lookups")
    public Map<String, Object> lookups(HttpServletRequest r) {
        Identity i = actor(r);
        var p = base(i);
        var calls = db.rows("lookupCalls", p);
        List<Map<String, Object>> campaigns, lines;
        if ("admin".equals(i.user().get("role"))) {
            campaigns =
                    store.query(
                            "campaign",
                            "list",
                            JsonSupport.map("tenantId", i.tenant(), "limit", 2000, "offset", 0));
            lines =
                    store.query(
                            "telephonyline",
                            "list",
                            JsonSupport.map("tenantId", i.tenant(), "limit", 2000, "offset", 0));
        } else {
            var c = new LinkedHashMap<Object, Map<String, Object>>();
            var l = new LinkedHashMap<Object, Map<String, Object>>();
            for (var row : calls) {
                if (row.get("campaign_id") != null)
                    c.put(
                            row.get("campaign_id"),
                            JsonSupport.map(
                                    "id",
                                    row.get("campaign_id"),
                                    "name",
                                    row.get("campaign_name")));
                if (row.get("telephony_line_id") != null)
                    l.put(
                            row.get("telephony_line_id"),
                            JsonSupport.map(
                                    "id",
                                    row.get("telephony_line_id"),
                                    "name",
                                    row.get("line_name")));
            }
            campaigns = new ArrayList<>(c.values());
            lines = new ArrayList<>(l.values());
        }
        return JsonSupport.map(
                "campaigns",
                campaigns.stream()
                        .map(x -> JsonSupport.map("id", x.get("id"), "name", x.get("name")))
                        .toList(),
                "lines",
                lines.stream()
                        .map(x -> JsonSupport.map("id", x.get("id"), "name", x.get("name")))
                        .toList(),
                "teams",
                db.rows("teams", p));
    }

    @GetMapping("/permissions/{user}")
    public Map<String, Object> permissionGet(@PathVariable
    long user, HttpServletRequest r) {
        Identity i = actor(r);
        access.admin(i);
        store.require("user", i.tenant(), user);
        var p = base(i);
        p.put("userId", user);
        var data = db.one("permissions", p);
        return data == null
                ? JsonSupport.map("can_export", false, "can_unmask", false, "can_record", true)
                : data;
    }

    @GetMapping("/workspace")
    public Map<String, Object> workspaceGet(HttpServletRequest r) {
        Identity i = actor(r);
        return workforce.effective(i.tenant(), i.userId());
    }

    @PostMapping("/workspace")
    public Map<String, Object> workspaceSave(
            @RequestBody
            Map<String, Object> b, HttpServletRequest r) {
        Identity i = actor(r);
        return tx.serialized(
                () -> {
                    var effective = workforce.effective(i.tenant(), i.userId());
                    if (!Boolean.TRUE.equals(effective.get("personal_allowed")))
                        throw new ApiError(403, "该组使用统一坐席配置");
                    var p = base(i);
                    p.put("kind", "workspace");
                    p.put("name", "user:" + i.userId());
                    var previous = db.one("configLatest", p);
                    long version =
                            previous == null ? 0 : ((Number) previous.get("revision")).longValue();
                    if (number(b.get("expected_revision"), 0, Integer.MAX_VALUE) != version)
                        throw new ApiError(409, "坐席配置已变化");
                    p.put("revision", version + 1);
                    p.put("config_json", json.json(workspace(b)));
                    db.write("configAdd", p);
                    return workforce.effective(i.tenant(), i.userId());
                });
    }

    @org.springframework.beans.factory.annotation.Autowired
    private com.duozhi.outbound.call.service.CallLifecycle callEvents;

    @PostMapping("/handoffs/{id}/screen")
    public Object screenShown(@PathVariable
    long id, HttpServletRequest request) {
        var i = actor(request);
        if (!"agent".equals(i.user().get("role"))) throw new ApiError(403, "仅坐席可上报弹屏");
        return tx.serialized(
                () -> {
                    var offer = store.require("handoffrequest", i.tenant(), id);
                    if (!Objects.equals(
                            Objects.toString(offer.get("assigned_agent_id"), ""),
                            i.userId().toString())) throw new ApiError(403, "不属于本坐席");
                    if (!"waiting".equals(offer.get("state"))) throw new ApiError(409, "转接已结束");
                    var call =
                            store.require("callsession", i.tenant(), offer.get("call_session_id"));
                    boolean duplicate =
                            callEvents.event(
                                    call,
                                    "agent_screen_shown",
                                    "agent_browser",
                                    JsonSupport.map(
                                            "event_id",
                                            "shown:" + id + ":" + i.userId(),
                                            "handoff_id",
                                            id,
                                            "agent_id",
                                            i.userId()));
                    return Map.of("received", true, "duplicate", duplicate);
                });
    }

    @GetMapping("/calls")
    public Map<String, Object> calls(@RequestParam
    Map<String, String> q, HttpServletRequest r) {
        return callsFor(q, actor(r));
    }

    public Map<String, Object> callsFor(Map<String, String> q, Identity i) {
        var p = filters(i, q);
        return JsonSupport.map(
                "total",
                db.count("callCount", p),
                "rows",
                db.rows("calls", p).stream().map(row -> callOutput(row, i)).toList());
    }

    @Transactional(rollbackFor = Exception.class)
    @GetMapping("/calls/export")
    public ResponseEntity<byte[]> export(@RequestParam
    Map<String, String> q, HttpServletRequest r)
            throws IOException {
        accountSecurity.export(
                actor(r), Objects.toString(r.getHeader("X-Export-Verification"), ""));
        return exportFor(q, actor(r));
    }

    public ResponseEntity<byte[]> exportFor(Map<String, String> q, Identity i) throws IOException {
        var target = new java.io.ByteArrayOutputStream();
        try (var writer =
                new java.io.OutputStreamWriter(
                        new com.duozhi.outbound.operations.service.ExportStorage.Budget(
                                        com.duozhi.outbound.operations.service.ExportStorage
                                                .MAX_BYTES)
                                .wrap(target),
                        StandardCharsets.UTF_8)) {
            writeCalls(q, i, writer, 100000);
        }
        notice(i, i.userId(), "export:" + UUID.randomUUID(), "话单导出已完成", "/operations/calls");
        return ResponseEntity.ok()
                .header("Content-Type", "text/csv;charset=UTF-8")
                .header("Content-Disposition", "attachment; filename=filtered-calls.csv")
                .body(target.toByteArray());
    }

    public void writeCalls(Map<String, String> q, Identity i, java.io.Writer writer, int maximum)
            throws IOException {
        if (!access.flag(i, "can_export")) throw new ApiError(403, "没有导出权限");
        var p = filters(i, q);
        long total = db.count("callCount", p);
        if (total > maximum) throw new ApiError(422, "导出数量超过限制，请缩小查询范围");
        writer.write('\ufeff');
        var csv = new CSVPrinter(writer, CSVFormat.DEFAULT);
        var keys =
                List.of(
                        "id",
                        "phone",
                        "campaign_name",
                        "status",
                        "mode",
                        "intent",
                        "qa_score",
                        "review_state",
                        "duration_seconds",
                        "owner_id",
                        "stage",
                        "created_at",
                        "call_cost",
                        "follow_at",
                        "remark",
                        "assigned_at",
                        "last_error",
                        "gender",
                        "grade",
                        "result_code",
                        "reviewed_by");
        csv.printRecord(keys);
        boolean hideSuccessful =
                !Boolean.TRUE.equals(
                        seatBehavior.effective(i.tenant(), i.userId()).get("success_phone"));
        p.put("limit", 250);
        for (int offset = 0; offset < total; offset += 250) {
            if (!com.duozhi.outbound.common.runtime.ScheduledWork.withinBudget())
                throw new ApiError(422, "导出超时，请缩小范围");
            p.put("offset", offset);
            for (var raw : db.rows("calls", p)) {
                @SuppressWarnings("unchecked")
                Map<String, Object> row = (Map<String, Object>) callOutput(raw, i);
                if (!access.flag(i, "can_unmask")
                        || hideSuccessful && "completed".equals(row.get("stage"))) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> masked =
                            (Map<String, Object>) OperationsPhoneMask.mask(row);
                    row = masked;
                }
                var values = new ArrayList<Object>();
                for (String k : keys) values.add(csvCell(row.get(k)));
                csv.printRecord(values);
            }
        }
        audit.record(i, "export", "ops_calls", null, "matched=" + total);
        csv.flush();
    }

    public static String csvCell(Object value) {
        String s = Objects.toString(value, "");
        return s.matches("^[=+@\\-\\t\\r].*") ? "'" + s : s;
    }

    public void authorizeExportCalls(Identity i, List<String> ids) {
        if (access.all(i)) return;
        var distinct = new ArrayList<>(new LinkedHashSet<>(ids));
        for (int at = 0; at < distinct.size(); at += 250) {
            var p = base(i);
            var chunk = distinct.subList(at, Math.min(at + 250, distinct.size()));
            p.put("ids", chunk);
            if (db.count("exportVisibleCount", p) != chunk.size())
                throw new ApiError(403, "部分话单已不在当前数据范围，请重新导出");
        }
    }

    @GetMapping("/reports")
    public Map<String, Object> reports(@RequestParam
    Map<String, String> q, HttpServletRequest r) {
        return reportsFor(q, actor(r));
    }

    public Map<String, Object> reportsFor(Map<String, String> q, Identity i) {
        var p = filters(i, q);
        p.put("statement_mode", config.production() ? "statement" : "simulation");
        p.put(
                "dimension",
                choice(
                        q.getOrDefault("dimension", "campaign"),
                        Set.of(
                                "all",
                                "script",
                                "account",
                                "group",
                                "line_group",
                                "city",
                                "campaign",
                                "agent",
                                "line",
                                "owner",
                                "flow_version")));
        p.put(
                "granularity",
                choice(
                        q.getOrDefault("granularity", "all"),
                        Set.of("all", "day", "hour", "minute", "month")));
        for (String key : List.of("min_intent_rate", "max_intent_rate")) {
            if (q.getOrDefault(key, "").isBlank()) continue;
            try {
                var rate = new java.math.BigDecimal(q.get(key));
                if (rate.signum() < 0 || rate.compareTo(java.math.BigDecimal.valueOf(100)) > 0)
                    throw new NumberFormatException();
                p.put(key, rate);
            } catch (NumberFormatException e) {
                throw new ApiError(422, "意向率范围只能为 0 到 100");
            }
        }
        if (p.containsKey("min_intent_rate")
                && p.containsKey("max_intent_rate")
                && ((java.math.BigDecimal) p.get("min_intent_rate"))
                                .compareTo((java.math.BigDecimal) p.get("max_intent_rate"))
                        > 0) throw new ApiError(422, "意向率下限不能大于上限");
        if (!p.containsKey("from")) p.put("from", Store.now().minusDays(30));
        return JsonSupport.map(
                "rows",
                competitiveMetrics.enrich(i.tenant(), db.rows("report", p), p),
                "definition",
                "首呼、复呼按会话内拨打序号及接通回执统计，归入会话创建时段；时长只累计有记录的秒数。会话按当前状态；尝试为会话累计attempts；合格仅统计质检结果qualified_lead；组内号码去重，跨组不能相加。进线按产生转接请求的会话去重，接管按已接受或完成的转接统计。接通率按接通回执次数除以拨打次数。进线率、接通成功率和意向率以接通会话为分母，接管成功率以接管会话为分母。成功为合格线索，意向含表达兴趣、合格线索和确认回拨。分母为零显示空值。",
                "filters",
                p);
    }

    @GetMapping("/reports/daily")
    public Object dailyBreakdown(@RequestParam
    Map<String, String> q, HttpServletRequest request) {
        var p = filters(actor(request), q);
        var until = (LocalDateTime) p.getOrDefault("until", Store.now());
        var from = (LocalDateTime) p.getOrDefault("from", until.minusDays(30));
        if (java.time.Duration.between(from, until).toDays() > 366)
            throw new ApiError(422, "单次最多统计366天");
        p.put("from", from);
        p.put("until", until);
        return db.rows("dailyBreakdown", p);
    }

    @GetMapping("/reports/intents")
    public Object intents(@RequestParam
    Map<String, String> q, HttpServletRequest r) {
        return intentsFor(q, actor(r));
    }

    public Object intentsFor(Map<String, String> q, Identity i) {
        var p = filters(i, q);
        p.put(
                "dimension",
                choice(q.getOrDefault("dimension", "agent"), Set.of("agent", "campaign")));
        p.putIfAbsent("from", Store.now().minusDays(30));
        return db.rows("intentDistribution", p);
    }

    @GetMapping("/reports/durations")
    public Object durations(@RequestParam
    Map<String, String> q, HttpServletRequest request) {
        var p = filters(actor(request), q);
        p.put(
                "durationKind",
                choice(q.getOrDefault("kind", "total"), Set.of("ai", "human", "total")));
        p.putIfAbsent("from", Store.now().minusDays(30));
        return db.rows("durations", p);
    }

    @GetMapping("/reports/export")
    public ResponseEntity<byte[]> reportExport(
            @RequestParam
            Map<String, String> q, HttpServletRequest r) throws IOException {
        accountSecurity.export(
                actor(r), Objects.toString(r.getHeader("X-Export-Verification"), ""));
        return reportExportFor(q, actor(r));
    }

    public ResponseEntity<byte[]> reportExportFor(Map<String, String> q, Identity i)
            throws IOException {
        var target = new java.io.ByteArrayOutputStream();
        try (var writer =
                new java.io.OutputStreamWriter(
                        new com.duozhi.outbound.operations.service.ExportStorage.Budget(
                                        com.duozhi.outbound.operations.service.ExportStorage
                                                .MAX_BYTES)
                                .wrap(target),
                        StandardCharsets.UTF_8)) {
            writeReport(q, i, writer);
        }
        return ResponseEntity.ok()
                .header("Content-Type", "text/csv;charset=UTF-8")
                .header("Content-Disposition", "attachment; filename=operations-report.csv")
                .body(target.toByteArray());
    }

    public void writeReport(Map<String, String> q, Identity i, java.io.Writer writer)
            throws IOException {
        if (!access.flag(i, "can_export")) throw new ApiError(403, "没有导出权限");
        var data = reportsFor(q, i);
        writer.write('\ufeff');
        var csv = new CSVPrinter(writer, CSVFormat.DEFAULT);
        List<String> fields =
                List.of(
                        "dimension_key",
                        "bucket",
                        "sessions",
                        "attempts",
                        "phones",
                        "reached_sessions",
                        "reached_phones",
                        "handoff_requests",
                        "handoff_received",
                        "qualified_phones",
                        "failed_sessions",
                        "first_answered_attempts",
                        "repeat_answered_attempts",
                        "answered_attempts",
                        "telephony_seconds",
                        "ai_seconds",
                        "answer_rate",
                        "entry_rate",
                        "takeover_rate",
                        "not_taken",
                        "successful_calls",
                        "successful_takeovers",
                        "success_per_answer",
                        "success_per_takeover",
                        "intent_rate",
                        "human_seconds",
                        "followed",
                        "online_seconds",
                        "online_people",
                        "gender_ratio",
                        "loss_count",
                        "loss_rate",
                        "average_intervention",
                        "interested_sessions",
                        "intent_cost",
                        "success_cost",
                        "reference_cost",
                        "reference_intent_cost",
                        "reference_success_cost",
                        "statement_cost",
                        "bill_seconds",
                        "active_agents",
                        "per_agent_takeover",
                        "per_agent_cost",
                        "weekday");
        csv.printRecord(fields);
        for (Object raw : list(data.get("rows"))) {
            var row = object(raw);
            csv.printRecord(fields.stream().map(k -> csvCell(row.get(k))).toList());
        }
        audit.record(
                i,
                "export",
                "ops_report",
                null,
                "dimension=" + q.getOrDefault("dimension", "campaign"));
        csv.flush();
    }

    @GetMapping("/campaigns")
    public List<Map<String, Object>> campaigns(
            @RequestParam(required = false)
            Long campaign_id, HttpServletRequest r) {
        Identity i = actor(r);
        access.admin(i);
        var p = base(i);
        p.put("campaign_id", campaign_id);
        return db.rows("campaignProgress", p);
    }

    @org.springframework.beans.factory.annotation.Autowired
    private com.duozhi.outbound.dial.service.TenantDialPolicy tenantDial;

    @org.springframework.beans.factory.annotation.Autowired
    private VoiceStatements statements;

    @GetMapping("/reports/business-counters")
    public Object businessCounters(
            @RequestParam
            String from, @RequestParam
            String until, HttpServletRequest r) {
        var i = actor(r);
        access.admin(i);
        var a = time(from);
        var b = time(until);
        if (a.isAfter(b) || Duration.between(a, b).toDays() > 366)
            throw new ApiError(422, "请选择一年内的有效时间段");
        return db.rows("businessCounters", Map.of("tenantId", i.tenant(), "from", a, "until", b));
    }

    @GetMapping("/dial-control")
    public Object dialControl(HttpServletRequest request) {
        var i = actor(request);
        if (!i.isOperator()) access.admin(i);
        var state = new LinkedHashMap<>(tenantDial.read(i));
        state.put("voice_balance", statements.balance(i.tenant()));
        return state;
    }

    @PutMapping("/dial-control")
    public Object changeDialControl(
            @RequestBody
            Map<String, Object> b, HttpServletRequest request) {
        var i = actor(request);
        access.admin(i);
        if (!(b.get("enabled") instanceof Boolean enabled)) throw new ApiError(422, "呼叫状态无效");
        return tenantDial.update(i, enabled, (int) number(b.get("revision"), 0, Integer.MAX_VALUE));
    }

    @GetMapping("/lines/groups")
    public Object lineGroups(HttpServletRequest r) {
        var i = actor(r);
        access.admin(i);
        return db.rows("lineGroups", base(i));
    }

    @PutMapping("/lines/{id}/group")
    @Transactional
    public Object saveLineGroup(
            @PathVariable
            long id, @RequestBody
            Map<String, Object> body, HttpServletRequest r) {
        var i = actor(r);
        access.admin(i);
        store.require("telephonyline", i.tenant(), id);
        var p = base(i);
        p.put("line", id);
        p.put("group", text(body.get("group_name"), 100));
        db.write("setLineGroup", p);
        audit.record(i, "group", "telephonyline", id, "更新线路组");
        return Map.of("group_name", p.get("group"));
    }

    @GetMapping("/users/{id}/dial-limit")
    public Object dialLimit(@PathVariable
    long id, HttpServletRequest r) {
        var i = actor(r);
        access.admin(i);
        return tenantDial.limit(i, id);
    }

    @PutMapping("/users/{id}/dial-limit")
    public Object setDialLimit(
            @PathVariable
            long id, @RequestBody
            Map<String, Object> b, HttpServletRequest r) {
        var i = actor(r);
        access.admin(i);
        int limit = (int) number(b.get("limit"), 0, 100000);
        return tenantDial.saveLimit(
                i, id, limit, (int) number(b.get("revision"), 0, Integer.MAX_VALUE));
    }

    @org.springframework.beans.factory.annotation.Autowired
    private com.duozhi.outbound.auth.service.AuthService staffAuth;

    @PostMapping("/seats/offline")
    public Object offlineSeats(@RequestBody
    Map<String, Object> b, HttpServletRequest r) {
        var i = actor(r);
        access.admin(i);
        if (!Boolean.TRUE.equals(b.get("confirmed"))) throw new ApiError(422, "请先确认下线范围");
        return tx.serialized(
                () -> {
                    tx.platform();
                    tx.tenant(i.tenant());
                    var ids = db.rows("offlineCandidates", Map.of("tenantId", i.tenant()));
                    int count = 0;
                    for (var u : ids) {
                        if (((Number) u.get("active")).longValue() > 0) continue;
                        staffAuth.patch(
                                ((Number) u.get("id")).longValue(),
                                (int) i.tenant(),
                                JsonSupport.map(
                                        "agent_status",
                                        "offline",
                                        "last_seen_at",
                                        Store.now(),
                                        "token_version",
                                        ((Number) u.get("token_version")).intValue() + 1));
                        count++;
                    }
                    audit.record(
                            i,
                            "offline",
                            "tenant_seats",
                            i.tenant(),
                            "offline=" + count + ", skipped=" + (ids.size() - count));
                    return Map.of("offline", count, "skipped", ids.size() - count);
                });
    }

    @GetMapping("/seats/monitor")
    public Object seatMonitor(HttpServletRequest request) {
        var i = actor(request);
        access.admin(i);
        return db.rows(
                "seatMonitor",
                JsonSupport.map(
                        "tenantId",
                        i.tenant(),
                        "ttl",
                        Math.max(30, config.number("AGENT_PRESENCE_TIMEOUT_SEC", 90)),
                        "since",
                        Store.now()
                                .minusSeconds(
                                        Math.max(
                                                30,
                                                config.number("AGENT_PRESENCE_TIMEOUT_SEC", 90)))));
    }

    @GetMapping("/teams")
    public List<Map<String, Object>> teams(HttpServletRequest r) {
        Identity i = actor(r);
        var rows = db.rows("teams", base(i));
        for (var t : rows)
            t.put(
                    "members",
                    db.rows(
                            "members",
                            JsonSupport.map("tenantId", i.tenant(), "team_id", t.get("id"))));
        return rows;
    }

    @PostMapping("/teams")
    public Map<String, Object> team(@RequestBody
    Map<String, Object> b, HttpServletRequest r) {
        Identity i = actor(r);
        access.admin(i);
        return tx.serialized(
                () -> {
                    var p = base(i);
                    String name = text(b.get("name"), 120);
                    if (name.isEmpty()) throw new ApiError(422, "请填写组名");
                    p.put("name", name);
                    var teamSettings = object(b.getOrDefault("settings", Map.of()));
                    var normalized = workspace(teamSettings);
                    Object skills = teamSettings.getOrDefault("routing_skills", List.of());
                    if (!(skills instanceof List<?> list) || list.size() > 100)
                        throw new ApiError(422, "invalid routing skills");
                    var cleaned = new ArrayList<String>();
                    for (Object skill : (List<?>) skills) {
                        String value = text(skill, 80);
                        if (value.isBlank()) throw new ApiError(422, "invalid routing skill");
                        cleaned.add(value);
                    }
                    normalized.put("routing_skills", cleaned.stream().distinct().toList());
                    p.put("settings_json", json.json(normalized));
                    if (b.get("id") == null) {
                        db.write("teamCreate", p);
                    } else {
                        p.put("team_id", number(b.get("id"), 1, Long.MAX_VALUE));
                        p.put("revision", number(b.get("revision"), 0, Integer.MAX_VALUE));
                        p.put("enabled", !Boolean.FALSE.equals(b.get("enabled")));
                        if (db.write("teamUpdate", p) != 1) throw new ApiError(409, "组配置已变化，请刷新");
                    }
                    List<?> members = list(b.getOrDefault("members", List.of()));
                    if (members.size() > 1000) throw new ApiError(422, "组成员过多");
                    Set<Long> seen = new HashSet<>();
                    db.write("memberClear", p);
                    for (Object item : members) {
                        var member = object(item);
                        long id = number(member.get("user_id"), 1, Integer.MAX_VALUE);
                        if (!seen.add(id)) throw new ApiError(422, "组成员重复");
                        var user = store.require("user", i.tenant(), id);
                        if (!Boolean.TRUE.equals(user.get("enabled")))
                            throw new ApiError(422, "组成员已禁用");
                        p.put("member_id", id);
                        p.put("is_lead", Boolean.TRUE.equals(member.get("is_lead")));
                        db.write("memberAdd", p);
                        db.write("permissionEnroll", p);
                    }
                    audit.record(
                            i, "save", "ops_team", p.get("team_id"), "members=" + members.size());
                    return JsonSupport.map("id", p.get("team_id"));
                });
    }

    @PutMapping("/permissions/{user}")
    public Map<String, Object> permissions(
            @PathVariable
            long user, @RequestBody
            Map<String, Object> b, HttpServletRequest r) {
        Identity i = actor(r);
        access.admin(i);
        store.require("user", i.tenant(), user);
        var p = base(i);
        p.put("member_id", user);
        for (String key : List.of("can_export", "can_unmask", "can_record")) {
            if (!(b.get(key) instanceof Boolean)) throw new ApiError(422, "权限必须为布尔值");
            p.put(key, b.get(key));
        }
        db.write("permissionSave", p);
        audit.record(i, "permissions", "ops_user", user, "operations permissions changed");
        return Map.of("saved", true);
    }

    @PostMapping("/calls/{id}/lead")
    public Object convertCall(@PathVariable
    String id, HttpServletRequest request) {
        Identity i = actor(request);
        return tx.serialized(
                () -> {
                    access.call(i, id);
                    var call = store.require("callsession", i.tenant(), id);
                    if (!"completed".equals(call.get("status")) || call.get("contact_id") == null)
                        throw new ApiError(409, "只能从已完成且关联联系人的通话生成线索");
                    long contact = ((Number) call.get("contact_id")).longValue();
                    var params = base(i);
                    params.put("contact_id", contact);
                    db.write("leadSeed", params);
                    var lead = db.one("leadLock", params);
                    if (lead.get("owner_id") == null)
                        changeLead(
                                i,
                                contact,
                                JsonSupport.map(
                                        "action",
                                        "claim",
                                        "event_key",
                                        "call-lead:" + id,
                                        "revision",
                                        lead.get("revision"),
                                        "note",
                                        "来源通话 " + id));
                    return leadDetail(contact, request);
                });
    }

    @GetMapping("/leads")
    public Map<String, Object> leads(@RequestParam
    Map<String, String> q, HttpServletRequest r) {
        Identity i = actor(r);
        var p = paged(i, q);
        p.put("pool", "pool".equals(q.get("view")));
        p.put("mine", "mine".equals(q.get("view")));
        p.put("overdue", "true".equals(q.get("overdue")));
        for (String key : List.of("keyword", "stage", "tag"))
            if (q.containsKey(key) && !q.get(key).isBlank()) p.put(key, text(q.get(key), 120));
        if (q.containsKey("contact_id"))
            p.put("contact_id", number(q.get("contact_id"), 1, Integer.MAX_VALUE));
        return JsonSupport.map("total", db.count("leadCount", p), "rows", db.rows("leads", p));
    }

    private Map<String, Object> lead(Identity i, long id) {
        store.require("contact", i.tenant(), id);
        var p = base(i);
        p.put("contact_id", id);
        p.put("pool", false);
        p.put("limit", 1);
        p.put("offset", 0);
        var rows = db.rows("leads", p);
        if (rows.isEmpty()) throw new ApiError(403, "线索不在授权范围");
        return rows.get(0);
    }

    @GetMapping("/leads/{id}")
    public Map<String, Object> leadDetail(@PathVariable
    long id, HttpServletRequest r) {
        Identity i = actor(r);
        var row = lead(i, id);
        row.put(
                "history",
                db.rows("history", JsonSupport.map("tenantId", i.tenant(), "contact_id", id)));
        return row;
    }

    @PostMapping("/leads/batch")
    public Map<String, Object> batch(@RequestBody
    Map<String, Object> b, HttpServletRequest r) {
        Identity i = actor(r);
        if (!access.manager(i)) throw new ApiError(403, "需要分配权限");
        List<?> items = list(b.get("items"));
        if (items.isEmpty() || items.size() > 200) throw new ApiError(422, "每批1至200条");
        return tx.serialized(
                () -> {
                    int count = 0;
                    for (Object raw : items) {
                        var item = new LinkedHashMap<>(object(raw));
                        item.put("action", "assign");
                        item.put("owner_id", b.get("owner_id"));
                        item.put("note", text(b.get("note"), 2000));
                        changeLead(
                                i, number(item.remove("contact_id"), 1, Integer.MAX_VALUE), item);
                        count++;
                    }
                    return Map.of("updated", count);
                });
    }

    @PatchMapping("/leads/{id}")
    public Map<String, Object> change(
            @PathVariable
            long id, @RequestBody
            Map<String, Object> b, HttpServletRequest r) {
        Identity i = actor(r);
        return tx.serialized(() -> changeLead(i, id, b));
    }

    private Map<String, Object> changeLead(Identity i, long id, Map<String, Object> b) {
        store.require("contact", i.tenant(), id);
        String action =
                choice(
                        b.get("action"),
                        Set.of("claim", "assign", "follow", "form", "tags", "wecom"));
        String key = text(b.get("event_key"), 80);
        if (key.isBlank()) throw new ApiError(422, "需要操作唯一标识");
        var p = base(i);
        p.put("contact_id", id);
        p.put("event_key", key);
        String hash = JsonSupport.sha(json.canonical(b, false));
        var prior = db.one("historyEvent", p);
        if (prior != null) {
            if (!prior.get("contact_id").toString().equals("" + id)
                    || !prior.get("actor_id").toString().equals(i.userId().toString())
                    || !hash.equals(prior.get("payload_hash")))
                throw new ApiError(409, "操作标识已被其他内容使用");
            lead(i, id);
            return Map.of("replayed", true);
        }
        db.write("leadSeed", p);
        var current = db.one("leadLock", p);
        Object previousOwner = current.get("owner_id");
        long revision = number(b.get("revision"), 0, Integer.MAX_VALUE);
        if (revision != ((Number) current.get("revision")).longValue())
            throw new ApiError(409, "线索已变化，请刷新后再操作");
        if (action.equals("claim")) {
            if (current.get("owner_id") != null) throw new ApiError(409, "线索已被领取");
            current.put("owner_id", i.userId());
        } else {
            if (!(action.equals("assign") && current.get("owner_id") == null && access.manager(i)))
                lead(i, id);
            if (action.equals("assign")) {
                if (!access.manager(i)) throw new ApiError(403, "需要分配权限");
                Object owner = b.get("owner_id");
                if (owner != null && !access.target(i, owner))
                    throw new ApiError(422, "负责人不在授权范围或已禁用");
                current.put("owner_id", owner == null ? null : number(owner, 1, Integer.MAX_VALUE));
            }
        }
        String note = text(b.get("note"), 2000);
        String wecomStatus = null;
        if (action.equals("wecom")) {
            if (current.get("owner_id") == null) throw new ApiError(409, "请先领取线索");
            wecomStatus = choice(b.get("wecom_status"), Set.of("pending", "added", "declined"));
        }
        if (action.equals("follow")) {
            if (Set.of("completed", "invalid").contains(Objects.toString(b.get("stage"), ""))
                    && !Boolean.TRUE.equals(
                            seatBehavior.effective(i.tenant(), i.userId()).get("success_invalid")))
                throw new ApiError(403, "当前坐席配置不允许标记成功单或失效单");
            if (current.get("owner_id") == null) throw new ApiError(409, "请先领取或分配线索");
            current.put(
                    "stage",
                    choice(b.get("stage"), Set.of("open", "in_progress", "completed", "invalid")));
            if (note.isBlank()) throw new ApiError(422, "请填写跟进记录");
            current.put(
                    "next_at",
                    b.get("next_at") == null || b.get("next_at").toString().isBlank()
                            ? null
                            : time(b.get("next_at")));
            if (current.get("next_at") != null
                    && !((LocalDateTime) current.get("next_at")).isAfter(Store.now()))
                throw new ApiError(422, "下次跟进时间应晚于当前时间");
            if (Set.of("completed", "invalid").contains(current.get("stage")))
                current.put("next_at", null);
        }
        if (action.equals("form")) {
            var config =
                    db.one(
                            "config",
                            JsonSupport.map(
                                    "tenantId",
                                    i.tenant(),
                                    "id",
                                    number(b.get("form_id"), 1, Long.MAX_VALUE)));
            if (config == null || !"form".equals(config.get("kind")))
                throw new ApiError(404, "表单不存在");
            if (!competitiveMetrics.formAllowed(
                    i.tenant(), i.userId(), config.get("id"), "admin".equals(i.user().get("role"))))
                throw new ApiError(403, "该表单未分配给当前账号");
            var fields = object(b.get("fields"));
            validateForm(json.object(config.get("config_json").toString()), fields);
            current.put("fields_json", json.json(fields));
            current.put("form_id", config.get("id"));
            current.put("form_revision", config.get("revision"));
            formReviews.assign(i, config.get("id"), id, fields, key);
        }
        if (action.equals("tags")) {
            List<?> tags = list(b.get("tags"));
            if (tags.size() > 30) throw new ApiError(422, "最多30个标签");
            Set<String> allowed = new HashSet<>();
            for (var conf :
                    db.rows("configs", JsonSupport.map("tenantId", i.tenant(), "kind", "tag")))
                if ("contact".equals(json.object(conf.get("config_json").toString()).get("target")))
                    allowed.add(conf.get("name").toString());
            for (Object tag : tags)
                if (!allowed.contains(tag.toString())) throw new ApiError(422, "标签未在字典中定义");
            current.put("tags_json", json.json(tags));
        }
        current.putAll(p);
        current.put("revision", revision);
        if (db.write("leadUpdate", current) != 1) throw new ApiError(409, "线索已变化");
        p.put("payload_hash", hash);
        p.put("action", action);
        p.put(
                "detail_json",
                json.json(
                        JsonSupport.map(
                                "action",
                                action,
                                "owner_id",
                                current.get("owner_id"),
                                "wecom_status",
                                wecomStatus,
                                "previous_owner",
                                previousOwner,
                                "stage",
                                current.get("stage"),
                                "note",
                                note,
                                "next_at",
                                current.get("next_at"),
                                "fields",
                                action.equals("form") ? b.get("fields") : null,
                                "tags",
                                action.equals("tags") ? b.get("tags") : null)));
        db.write("historyAdd", p);
        audit.record(i, action, "ops_lead", id, "revision=" + (revision + 1));
        if (current.get("owner_id") != null && (action.equals("assign") || action.equals("claim")))
            notice(i, current.get("owner_id"), "assign:" + key, "有新的线索待跟进", "/operations/leads");
        return JsonSupport.map("revision", revision + 1, "saved", true);
    }

    void notice(Identity i, Object user, String key, String title, String path) {
        var p = base(i);
        p.putAll(
                JsonSupport.map("recipient", user, "event_key", key, "title", title, "path", path));
        db.write("noticeAdd", p);
    }

    @GetMapping("/notices")
    public List<Map<String, Object>> notices(HttpServletRequest r) {
        Identity i = actor(r);
        var p = base(i);
        db.write("noticeDue", p);
        db.write("noticeWork", p);
        db.write("noticeAppointment", p);
        db.write("noticeDelivery", p);
        if ("admin".equals(i.user().get("role"))) {
            var rule = outboundRules.read(i.tenant());
            var value = object(rule.get("draft"));
            int threshold = JsonSupport.integer(value.get("low_balance"), 0);
            if (threshold > 0) {
                var balance = statements.balance(i.tenant());
                if (Boolean.TRUE.equals(balance.get("available"))
                        && balance.get("amount") instanceof Number n
                        && n.doubleValue() <= threshold)
                    notice(
                            i,
                            i.userId(),
                            "low-balance:" + Store.now().toLocalDate() + ":" + rule.get("revision"),
                            "语音余额已低于提醒阈值",
                            "/account/recharge");
            }
        }
        return db.rows("notices", p);
    }

    @GetMapping("/notices/unread")
    public Map<String, Object> unread(HttpServletRequest r) {
        notices(r);
        return Map.of("count", db.count("unreadNotices", base(actor(r))));
    }

    @PostMapping("/notices/{id}/read")
    public Map<String, Object> read(@PathVariable
    long id, HttpServletRequest r) {
        var p = base(actor(r));
        p.put("id", id);
        if (db.write("noticeRead", p) != 1) throw new ApiError(404, "通知不存在");
        return Map.of("saved", true);
    }

    @GetMapping("/views")
    public List<Map<String, Object>> views(HttpServletRequest r) {
        return db.rows("views", base(actor(r)));
    }

    @PostMapping("/views")
    public Map<String, Object> view(@RequestBody
    Map<String, Object> b, HttpServletRequest r) {
        var p = base(actor(r));
        p.put("kind", choice(b.get("kind"), Set.of("calls", "reports")));
        String name = text(b.get("name"), 100);
        if (name.isBlank()) throw new ApiError(422, "请填写视图名");
        p.put("name", name);
        var conf = object(b.get("config"));
        if (json.json(conf).length() > 16000) throw new ApiError(422, "视图过大");
        p.put("config_json", json.json(conf));
        db.write("viewSave", p);
        return Map.of("saved", true);
    }

    @DeleteMapping("/views/{id}")
    public Map<String, Object> deleteView(@PathVariable
    long id, HttpServletRequest r) {
        var p = base(actor(r));
        p.put("id", id);
        if (db.write("viewDelete", p) != 1) throw new ApiError(404, "视图不存在");
        return Map.of("deleted", true);
    }

    @GetMapping("/configs")
    public List<Map<String, Object>> configs(@RequestParam
    String kind, HttpServletRequest r) {
        Identity i = actor(r);
        var p = base(i);
        p.put("kind", choice(kind, Set.of("form", "tag", "workspace")));
        return db.rows("configs", p).stream()
                .filter(
                        c ->
                                !kind.equals("form")
                                        || competitiveMetrics.formAllowed(
                                                i.tenant(),
                                                i.userId(),
                                                c.get("id"),
                                                "admin".equals(i.user().get("role"))))
                .toList();
    }

    @GetMapping("/tag-objects")
    public List<Map<String, Object>> tagObjects(@RequestParam
    String target, HttpServletRequest r) {
        Identity i = actor(r);
        access.admin(i);
        String kind = choice(target, Set.of("call", "campaign", "script"));
        List<Map<String, Object>> rows;
        if (kind.equals("call")) {
            var p = base(i);
            p.put("limit", 200);
            p.put("offset", 0);
            rows = db.rows("calls", p);
        } else
            rows =
                    store.query(
                            kind.equals("campaign") ? "campaign" : "scripttemplate",
                            "list",
                            JsonSupport.map("tenantId", i.tenant(), "limit", 200, "offset", 0));
        return rows.stream()
                .map(
                        v ->
                                JsonSupport.map(
                                        "id",
                                        v.get("id"),
                                        "name",
                                        kind.equals("call")
                                                ? v.get("phone") + " · " + v.get("created_at")
                                                : v.get("name")))
                .toList();
    }

    @GetMapping("/tags")
    public List<Map<String, Object>> tagList(
            @RequestParam
            String target, @RequestParam
            String object_id, HttpServletRequest r) {
        Identity i = actor(r);
        access.admin(i);
        validateTagTarget(i, target, object_id);
        return db.rows(
                "tagLinks",
                JsonSupport.map(
                        "tenantId", i.tenant(), "target_kind", target, "object_id", object_id));
    }

    private void validateTagTarget(Identity i, String target, String id) {
        String kind = choice(target, Set.of("call", "campaign", "script"));
        if (kind.equals("call")) {
            try {
                store.require("callsession", i.tenant(), UUID.fromString(id));
            } catch (IllegalArgumentException e) {
                throw new ApiError(422, "通话标识无效");
            }
        } else
            store.require(
                    kind.equals("campaign") ? "campaign" : "scripttemplate",
                    i.tenant(),
                    number(id, 1, Integer.MAX_VALUE));
    }

    @PostMapping("/tags")
    public Map<String, Object> applyTags(@RequestBody
    Map<String, Object> b, HttpServletRequest r) {
        Identity i = actor(r);
        access.admin(i);
        return tx.serialized(
                () -> {
                    String target = text(b.get("target"), 24), id = text(b.get("object_id"), 80);
                    validateTagTarget(i, target, id);
                    List<?> tags = list(b.get("tags"));
                    if (tags.size() > 30) throw new ApiError(422, "最多30个标签");
                    Set<String> allowed = new HashSet<>();
                    for (var t :
                            db.rows(
                                    "configs",
                                    JsonSupport.map("tenantId", i.tenant(), "kind", "tag")))
                        if (target.equals(
                                json.object(t.get("config_json").toString()).get("target")))
                            allowed.add(t.get("name").toString());
                    if (!allowed.containsAll(tags)) throw new ApiError(422, "标签作用对象不匹配");
                    var p =
                            JsonSupport.map(
                                    "tenantId", i.tenant(), "target_kind", target, "object_id", id);
                    db.write("tagClear", p);
                    for (Object tag : new LinkedHashSet<>(tags)) {
                        p.put("tag", tag);
                        db.write("tagAdd", p);
                    }
                    audit.record(i, "tags", "ops_" + target, id, "tags=" + tags.size());
                    return Map.of("saved", true);
                });
    }

    @PostMapping("/configs")
    public Map<String, Object> config(@RequestBody
    Map<String, Object> b, HttpServletRequest r) {
        Identity i = actor(r);
        access.admin(i);
        return tx.serialized(
                () -> {
                    var p = base(i);
                    String kind = choice(b.get("kind"), Set.of("form", "tag", "workspace")),
                            name = text(b.get("name"), 120);
                    if (name.isBlank()) throw new ApiError(422, "请填写名称");
                    var conf = object(b.get("config"));
                    if (kind.equals("form")) validateTemplate(conf);
                    else if (kind.equals("workspace")) conf = workspace(conf);
                    else {
                        choice(conf.get("target"), Set.of("contact", "call", "campaign", "script"));
                    }
                    if (json.json(conf).length() > 32000) throw new ApiError(422, "配置过大");
                    p.putAll(JsonSupport.map("kind", kind, "name", name));
                    var old = db.one("configLatest", p);
                    long
                            expected =
                                    number(
                                            b.getOrDefault("expected_revision", 0),
                                            0,
                                            Integer.MAX_VALUE),
                            revision = old == null ? 0 : ((Number) old.get("revision")).longValue();
                    if (expected != revision) throw new ApiError(409, "配置版本已更新，请刷新");
                    p.put("revision", revision + 1);
                    p.put("config_json", json.json(conf));
                    db.write("configAdd", p);
                    audit.record(i, "publish", "ops_" + kind, name, "revision=" + (revision + 1));
                    return Map.of("revision", revision + 1);
                });
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Object v) {
        if (!(v instanceof Map)) throw new ApiError(422, "需要对象");
        return (Map<String, Object>) v;
    }

    static List<?> list(Object v) {
        if (!(v instanceof List)) throw new ApiError(422, "需要列表");
        return (List<?>) v;
    }

    static Map<String, Object> workspace(Map<String, Object> b) {
        var out = new LinkedHashMap<String, Object>();
        out.put("after_call_seconds", number(b.getOrDefault("after_call_seconds", 0), 0, 600));
        out.put("personal_override", Boolean.TRUE.equals(b.get("personal_override")));
        out.put(
                "queue_timeout_seconds",
                number(b.getOrDefault("queue_timeout_seconds", 60), 5, 600));
        return out;
    }

    static void validateTemplate(Map<String, Object> config) {
        List<?> fields = list(config.get("fields"));
        if (fields.isEmpty() || fields.size() > 40) throw new ApiError(422, "表单需要1至40个字段");
        Set<String> keys = new HashSet<>();
        for (Object raw : fields) {
            var f = object(raw);
            String key = text(f.get("key"), 50);
            if (!key.matches("[a-z][a-z0-9_]{0,49}") || !keys.add(key))
                throw new ApiError(422, "字段标识无效或重复");
            if (text(f.get("label"), 100).isBlank()) throw new ApiError(422, "字段名称必填");
            String type = choice(f.get("type"), Set.of("text", "number", "choice", "boolean"));
            if (type.equals("choice")
                    && (list(f.get("choices")).isEmpty() || list(f.get("choices")).size() > 100))
                throw new ApiError(422, "请填写选项");
        }
    }

    static void validateForm(Map<String, Object> template, Map<String, Object> values) {
        Set<String> keys = new HashSet<>();
        for (Object raw : list(template.get("fields"))) {
            var f = object(raw);
            String k = f.get("key").toString();
            keys.add(k);
            Object v = values.get(k);
            if (v == null || v.toString().isBlank()) {
                if (Boolean.TRUE.equals(f.get("required")))
                    throw new ApiError(422, "请填写必填字段：" + f.get("label"));
                continue;
            }
            switch (f.get("type").toString()) {
                case "number" -> {
                    if (!(v instanceof Number)) throw new ApiError(422, "数字字段无效");
                }
                case "boolean" -> {
                    if (!(v instanceof Boolean)) throw new ApiError(422, "布尔字段无效");
                }
                case "choice" -> {
                    if (!list(f.get("choices")).contains(v)) throw new ApiError(422, "选项不匹配");
                }
                default -> text(v, 2000);
            }
        }
        if (!keys.containsAll(values.keySet())) throw new ApiError(422, "存在未定义字段");
    }

    @GetMapping("/work-items")
    public List<Map<String, Object>> work(HttpServletRequest r) {
        return db.rows("workItems", base(actor(r)));
    }

    @PatchMapping("/work-items/{id}")
    public Map<String, Object> workUpdate(
            @PathVariable
            UUID id, @RequestBody
            Map<String, Object> b, HttpServletRequest r) {
        Identity i = actor(r);
        return tx.serialized(
                () -> {
                    var row = store.require("productworkitem", i.tenant(), id);
                    if (!access.all(i)
                            && !Objects.toString(row.get("assigned_to"), "")
                                    .equals(i.userId().toString())
                            && !(access.manager(i)
                                    && row.get("assigned_to") != null
                                    && access.target(i, row.get("assigned_to"))))
                        throw new ApiError(403, "待办不在授权范围");
                    var changes =
                            JsonSupport.map(
                                    "state",
                                    choice(
                                            b.get("state"),
                                            Set.of("open", "in_progress", "completed")));
                    if (b.containsKey("assigned_to")) {
                        if (!access.manager(i)
                                || b.get("assigned_to") != null
                                        && !access.target(i, b.get("assigned_to")))
                            throw new ApiError(403, "负责人不在授权范围");
                        changes.put("assigned_to", b.get("assigned_to"));
                    }
                    store.update("productworkitem", i.tenant(), id, changes);
                    audit.record(
                            i, "follow", "productworkitem", id, "state=" + changes.get("state"));
                    return Map.of("saved", true);
                });
    }

    @GetMapping("/deliveries")
    public List<Map<String, Object>> deliveries(HttpServletRequest r) {
        Identity i = actor(r);
        access.admin(i);
        return db.rows("deliveries", base(i));
    }

    @PostMapping("/deliveries/{id}/retry")
    public Map<String, Object> retry(@PathVariable
    UUID id, HttpServletRequest r) {
        Identity i = actor(r);
        access.admin(i);
        return tx.serialized(
                () -> {
                    var row = store.require("taskoutbox", i.tenant(), id);
                    if (!"business_callback".equals(row.get("task_type"))
                            || !"failed".equals(row.get("state")))
                        throw new ApiError(409, "仅可重试失败的业务回调");
                    store.update(
                            "taskoutbox",
                            i.tenant(),
                            id,
                            JsonSupport.map(
                                    "state",
                                    "pending",
                                    "attempts",
                                    0,
                                    "available_at",
                                    Store.now(),
                                    "locked_at",
                                    null,
                                    "lease_token",
                                    null));
                    audit.record(i, "retry", "business_callback", id, "manual retry");
                    return Map.of("saved", true);
                });
    }
}
