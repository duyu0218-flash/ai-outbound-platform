package com.duozhi.outbound;

import java.util.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.io.*;
import java.nio.charset.StandardCharsets;
import javax.servlet.http.HttpServletRequest;
import org.apache.commons.csv.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/** Tenant-scoped operational reports using the Python baseline's counting rules. */
@RestController
@RequestMapping("/api/v1")
public class Reports {
    private static final Set<String> REACHED = Values.set("answered", "in_ai", "waiting_human", "handoff_transferring", "in_human", "completed");
    private static final Set<String> LOSS = Values.set("failed", "no_answer", "busy", "voicemail");
    private static final String[] COUNTS = {"calls", "reached", "handoff", "completed", "failed", "no_answer", "loss"};
    private final Store store;
    private final Audit audit;
    private final Contract contract;
    public Reports(Store store, Audit audit, Contract contract) { this.store = store; this.audit = audit; this.contract = contract; }
    private Identity admin(HttpServletRequest request) { Identity i = Identity.from(request); i.requireUser(); i.admin(); return i; }
    private LocalDateTime since(int days) { if (days < 1 || days > 3650) throw new ApiError(422, "invalid days"); return Store.now().minusDays(days); }
    private void dimension(String dimension) { if (!Values.set("campaign", "agent", "line").contains(dimension)) throw new ApiError(400, "unsupported dimension"); }
    private List<Map<String,Object>> calls(long tenant, LocalDateTime since) {
        return store.list("callsession", "WHERE tenant_id=? AND created_at>=? ORDER BY created_at,id", tenant, store.encode("callsession", "created_at", since));
    }
    private Map<String,Object> window(int days, LocalDateTime since) { return JsonSupport.map("days", days, "start", since.toString(), "end", Store.now().toString()); }
    private String key(Map<String,Object> call, String dimension) { return Objects.toString(call.get("campaign".equals(dimension) ? "campaign_id" : "agent".equals(dimension) ? "human_agent_id" : "telephony_line_id"), "0"); }
    private Map<String,String> labels(long tenant, String dimension) {
        Map<String,String> result = new HashMap<>();
        String table = "campaign".equals(dimension) ? "campaign" : "agent".equals(dimension) ? "user" : "telephonyline";
        for (Map<String,Object> row : store.list(table, "WHERE tenant_id=?", tenant)) result.put(row.get("id").toString(), Objects.toString(row.get("agent".equals(dimension) ? "full_name" : "name"), ""));
        result.put("0", "campaign".equals(dimension) ? "未绑定任务" : "agent".equals(dimension) ? "未分配座席" : "未绑定线路");
        return result;
    }
    private String label(String key, String dimension, Map<String,String> labels) { return labels.getOrDefault(key, ("campaign".equals(dimension) ? "Campaign " : "agent".equals(dimension) ? "Agent " : "Line ") + key); }
    private Map<String,Object> empty(String key, String label) {
        Map<String,Object> row = JsonSupport.map("key", key, "label", label);
        for (String name : COUNTS) row.put(name, 0L);
        return row;
    }
    private void increment(Map<String,Object> row, String name) { row.put(name, ((Number)row.getOrDefault(name, 0L)).longValue() + 1); }
    private void add(Map<String,Object> row, String name, double value) { row.put(name, ((Number)row.getOrDefault(name, 0.0)).doubleValue() + value); }
    private void count(Map<String,Object> row, Map<String,Object> call) {
        increment(row, "calls"); Object status = call.get("status");
        if (REACHED.contains(status)) increment(row, "reached");
        if (!Objects.toString(call.get("handoff_reason"), "").isEmpty()) increment(row, "handoff");
        if (Values.set("completed", "failed", "no_answer").contains(status)) increment(row, status.toString());
        if (LOSS.contains(status)) increment(row, "loss");
    }
    private List<Map<String,Object>> sorted(Collection<Map<String,Object>> values) {
        List<Map<String,Object>> rows = new ArrayList<>(values);
        rows.sort((a,b) -> Long.compare(((Number)b.get("calls")).longValue(), ((Number)a.get("calls")).longValue()));
        return rows;
    }
    @GetMapping("/admin/call-reports")
    public Map<String,Object> report(@RequestParam(defaultValue="campaign") String dimension, @RequestParam(defaultValue="day") String granularity, @RequestParam(defaultValue="30") int days, HttpServletRequest request) {
        long tenant = admin(request).tenant(); dimension(dimension);
        if (!Values.set("day", "hour").contains(granularity)) throw new ApiError(400, "unsupported granularity");
        LocalDateTime since = since(days); Map<String,String> labels = labels(tenant, dimension);
        Map<String,Map<String,Object>> groups = new LinkedHashMap<>(), trend = new TreeMap<>(); Map<String,Object> summary = empty("summary", "全部");
        DateTimeFormatter format = DateTimeFormatter.ofPattern("hour".equals(granularity) ? "yyyy-MM-dd HH:00" : "yyyy-MM-dd");
        for (Map<String,Object> call : calls(tenant, since)) {
            String key = key(call, dimension), bucket = LocalDateTime.parse(call.get("created_at").toString()).format(format);
            count(groups.computeIfAbsent(key, k -> empty(k, label(k, dimension, labels))), call); count(summary, call);
            Map<String,Object> point = trend.computeIfAbsent(bucket, k -> empty(k, k)); point.put("bucket", bucket); count(point, call);
        }
        List<Map<String,Object>> points = new ArrayList<>();
        for (Map<String,Object> point : trend.values()) points.add(contract.output("AdminReportTrendPoint", point));
        Map<String,Object> window = window(days, since); window.put("granularity", granularity);
        return JsonSupport.map("dimension", dimension, "window", window, "summary", summary, "rows", sorted(groups.values()), "trend", points);
    }
    private String group(Object tags) { for (String part : Objects.toString(tags, "").split("[;,，]")) if (!part.trim().isEmpty()) return part.trim(); return "未分组"; }
    private Map<String,Object> emptyGroup(String key) { Map<String,Object> row = empty(key, key); row.put("contacts", 0L); row.put("dnc_contacts", 0L); return row; }
    @GetMapping("/admin/contact-groups")
    public Map<String,Object> groups(@RequestParam(defaultValue="30") int days, HttpServletRequest request) {
        long tenant = admin(request).tenant(); LocalDateTime since = since(days);
        Map<String,String> contactGroups = new HashMap<>(); Map<String,Map<String,Object>> groups = new LinkedHashMap<>();
        Map<String,Object> summary = emptyGroup("summary"); summary.put("label", "全部");
        for (Map<String,Object> contact : store.list("contact", "WHERE tenant_id=?", tenant)) {
            String key = group(contact.get("tags")); contactGroups.put(contact.get("id").toString(), key);
            Map<String,Object> row = groups.computeIfAbsent(key, this::emptyGroup); increment(row, "contacts"); increment(summary, "contacts");
            if (Boolean.TRUE.equals(contact.get("dnc"))) { increment(row, "dnc_contacts"); increment(summary, "dnc_contacts"); }
        }
        groups.computeIfAbsent("未分组", this::emptyGroup);
        for (Map<String,Object> call : calls(tenant, since)) { count(groups.get(contactGroups.getOrDefault(Objects.toString(call.get("contact_id"), ""), "未分组")), call); count(summary, call); }
        return JsonSupport.map("window", window(days, since), "summary", summary, "rows", sorted(groups.values()));
    }
    private Map<String,Object> emptyBill(String key, String label) {
        Map<String,Object> row = empty(key, label);
        for (String field : Arrays.asList("billable_calls", "sms_count", "missing_duration_count", "missing_ai_duration_count", "estimated_duration_count")) row.put(field, 0L);
        for (String field : Arrays.asList("telephony_minutes", "ai_minutes", "estimated_cost")) row.put(field, 0.0);
        return row;
    }
    private void round(Map<String,Object> row) {
        for (String field : Arrays.asList("telephony_minutes", "ai_minutes", "estimated_cost")) row.put(field, BigDecimal.valueOf(((Number)row.get(field)).doubleValue()).setScale(4, RoundingMode.HALF_EVEN).doubleValue());
    }
    private Map<String,Object> usage(Map<String,Object> call, List<Map<String,Object>> records) {
        Map<String,Object> row = emptyBill("", ""); Set<Object> attempts = new HashSet<>();
        for (Map<String,Object> u : records) {
            attempts.add(u.get("attempt"));
            if (u.get("ended_at") == null) { increment(row, "missing_duration_count"); increment(row, "missing_ai_duration_count"); continue; }
            if (u.get("telephony_seconds") == null) increment(row, "missing_duration_count"); else add(row, "telephony_minutes", ((Number)u.get("telephony_seconds")).doubleValue()/60);
            if (u.get("ai_seconds") == null) increment(row, "missing_ai_duration_count"); else add(row, "ai_minutes", ((Number)u.get("ai_seconds")).doubleValue()/60);
            if ("observed_estimate".equals(u.get("duration_source"))) increment(row, "estimated_duration_count");
        }
        int missing = Math.max(0, CallLifecycle.number(call, "attempts") - attempts.size());
        if (records.isEmpty()) missing = Values.set("no_answer", "busy").contains(call.get("status")) ? Math.max(0, CallLifecycle.number(call, "attempts") - 1) : (CallLifecycle.number(call, "attempts") > 0 ? CallLifecycle.number(call, "attempts") : (Values.set("created", "queued", "failed").contains(call.get("status")) ? 0 : 1));
        row.put("missing_duration_count", ((Number)row.get("missing_duration_count")).longValue() + missing);
        if (!"human_only".equals(call.get("mode"))) row.put("missing_ai_duration_count", ((Number)row.get("missing_ai_duration_count")).longValue() + missing);
        return row;
    }
    @GetMapping("/admin/billing")
    public Map<String,Object> billing(@RequestParam(defaultValue="campaign") String dimension, @RequestParam(defaultValue="30") int days,
            @RequestParam(defaultValue="0") double ai_unit_price_per_minute, @RequestParam(defaultValue="0") double telephony_unit_price_per_minute, @RequestParam(defaultValue="0") double sms_unit_price, HttpServletRequest request) {
        long tenant = admin(request).tenant(); dimension(dimension); LocalDateTime since = since(days);
        for (double price : new double[]{ai_unit_price_per_minute, telephony_unit_price_per_minute, sms_unit_price}) if (!Double.isFinite(price) || price < 0) throw new ApiError(422, "invalid unit price");
        Map<String,List<Map<String,Object>>> usage = new HashMap<>();
        for (Map<String,Object> row : store.list("callusage", "WHERE tenant_id=? AND call_session_id IN (SELECT id FROM callsession WHERE tenant_id=? AND created_at>=?)", tenant, tenant, store.encode("callsession", "created_at", since))) usage.computeIfAbsent(row.get("call_session_id").toString(), k -> new ArrayList<>()).add(row);
        Map<String,Long> sms = new HashMap<>();
        for (Map<String,Object> row : store.jdbc().queryForList("SELECT call_session_id,COUNT(*) AS n FROM smslog WHERE tenant_id=? AND created_at>=? AND call_session_id IS NOT NULL GROUP BY call_session_id", tenant, store.encode("smslog", "created_at", since))) sms.put(row.get("call_session_id").toString().replace("-", ""), ((Number)row.get("n")).longValue());
        Map<String,String> labels = labels(tenant, dimension); Map<String,Map<String,Object>> groups = new LinkedHashMap<>(); Map<String,Object> summary = emptyBill("summary", "全部");
        for (Map<String,Object> call : calls(tenant, since)) {
            String key = key(call, dimension); Map<String,Object> row = groups.computeIfAbsent(key, k -> emptyBill(k, label(k, dimension, labels)));
            Map<String,Object> duration = usage(call, usage.getOrDefault(call.get("id").toString(), Collections.emptyList())); long messages = sms.getOrDefault(call.get("id").toString().replace("-", ""), 0L);
            double cost = ((Number)duration.get("telephony_minutes")).doubleValue()*telephony_unit_price_per_minute + ((Number)duration.get("ai_minutes")).doubleValue()*ai_unit_price_per_minute + messages*sms_unit_price;
            for (Map<String,Object> target : Arrays.asList(row, summary)) {
                count(target, call); if (REACHED.contains(call.get("status"))) increment(target, "billable_calls");
                for (String field : Arrays.asList("telephony_minutes", "ai_minutes")) add(target, field, ((Number)duration.get(field)).doubleValue());
                for (String field : Arrays.asList("missing_duration_count", "missing_ai_duration_count", "estimated_duration_count")) target.put(field, ((Number)target.get(field)).longValue() + ((Number)duration.get(field)).longValue());
                target.put("sms_count", ((Number)target.get("sms_count")).longValue() + messages); add(target, "estimated_cost", cost);
            }
            summary.put("estimated_cost", BigDecimal.valueOf(((Number)summary.get("estimated_cost")).doubleValue()).setScale(4, RoundingMode.HALF_EVEN).doubleValue());
        }
        for (Map<String,Object> row : groups.values()) round(row);
        Map<String,Object> rates = JsonSupport.map("ai_unit_price_per_minute", ai_unit_price_per_minute, "telephony_unit_price_per_minute", telephony_unit_price_per_minute, "sms_unit_price", sms_unit_price); summary.putAll(rates);
        return JsonSupport.map("dimension", dimension, "window", window(days, since), "rates", rates, "summary", contract.output("AdminBillingSummary", summary), "rows", sorted(groups.values()));
    }
    @GetMapping("/quality/reviews")
    public List<Map<String,Object>> reviews(@RequestParam(required=false) String review_state, @RequestParam(required=false) Integer max_score,
            @RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="50") int size, HttpServletRequest request) {
        Identity identity = Identity.from(request); if (identity.user() != null && !"admin".equals(identity.user().get("role")) && !Boolean.TRUE.equals(identity.user().get("is_supervisor"))) throw new ApiError(403, "supervisor permission required");
        int offset = Contract.page(page, size); if (review_state != null && !Values.set("auto", "reviewed").contains(review_state) || max_score != null && (max_score < 0 || max_score > 100)) throw new ApiError(422, "invalid review filter");
        String where = "WHERE tenant_id=? AND call_session_id IN (SELECT id FROM callsession WHERE tenant_id=?)"; List<Object> args = new ArrayList<>(Arrays.asList(identity.tenant(), identity.tenant()));
        if (review_state != null) { if ("auto".equals(review_state)) { where += " AND (review_state='auto' OR needs_review=?)"; args.add(true); } else { where += " AND review_state=?"; args.add(review_state); } }
        if (max_score != null) { where += " AND qa_score<=?"; args.add(max_score); } args.add(size); args.add(offset);
        List<Map<String,Object>> result = new ArrayList<>();
        for (Map<String,Object> row : store.list("callanalysis", where + " ORDER BY review_state,qa_score,updated_at DESC LIMIT ? OFFSET ?", args.toArray())) {
            Map<String,Object> call = store.require("callsession", identity.tenant(), row.get("call_session_id"));
            Map<String,Object> campaign = call.get("campaign_id") == null ? null : store.one("campaign", "WHERE tenant_id=? AND id=?", identity.tenant(), call.get("campaign_id"));
            row.putAll(JsonSupport.map("call_id", call.get("id"), "phone", call.get("phone"), "call_status", call.get("status"), "campaign_id", call.get("campaign_id"), "campaign_name", campaign == null ? null : campaign.get("name")));
            if (Boolean.TRUE.equals(row.get("needs_review"))) row.put("review_state", "needs_review");
            result.add(contract.output("QualityReviewQueueItemOut", row));
        }
        return result;
    }
    private String joined(List<Map<String,Object>> rows, String key) { List<String> values = new ArrayList<>(); for (Map<String,Object> row : rows) if (row.get(key) != null) values.add(row.get(key).toString()); return String.join(" | ", values); }
    @GetMapping("/admin/calls/export")
    public ResponseEntity<StreamingResponseBody> export(@RequestParam(defaultValue="30") int days, @RequestParam(value="status",required=false) String status, HttpServletRequest request) {
        Identity identity = admin(request); LocalDateTime since = since(days); Object encodedStatus = null;
        if (status != null) { try { encodedStatus = store.encode("callsession", "status", status); } catch (ApiError e) { throw new ApiError(400, "invalid call status"); } }
        final Object filter = encodedStatus; long tenant = identity.tenant(); audit.record(identity, "export", "call_evidence", null, "days=" + days + ", status=" + Objects.toString(status, "all"));
        StreamingResponseBody stream = output -> {
            CSVPrinter csv = new CSVPrinter(new OutputStreamWriter(output, StandardCharsets.UTF_8), CSVFormat.DEFAULT);
            String[] headers = {"call_id","phone","status","mode","campaign_id","contact_phone","attempts","max_attempts","started_at","finished_at","created_at","updated_at","duration_ms","metric_count","metric_total_duration_ms","speech_turn_count","final_speech_turn_count","result_code","intent","sentiment","qa_score","qa_flags","analysis_summary","human_agent_id","handoff_reason","last_error","recording_urls","recording_storage_uris","recording_states","recording_retention_untils"}; csv.printRecord((Object[])headers);
            Object lastId = null, lastCreated = null;
            while (true) {
                String query = "WHERE tenant_id=? AND created_at>=?"; List<Object> args = new ArrayList<>(Arrays.asList(tenant, store.encode("callsession", "created_at", since)));
                if (filter != null) { query += " AND status=?"; args.add(filter); }
                if (lastId != null) { query += " AND (created_at<? OR (created_at=? AND id<?))"; args.add(lastCreated); args.add(lastCreated); args.add(lastId); }
                List<Map<String,Object>> batch = store.list("callsession", query + " ORDER BY created_at DESC,id DESC LIMIT 250", args.toArray()); if (batch.isEmpty()) break;
                for (Map<String,Object> call : batch) {
                    Object id = store.encode("callsession", "id", call.get("id")); Map<String,Object> row = new LinkedHashMap<>(call); row.put("call_id", call.get("id"));
                    Map<String,Object> contact = call.get("contact_id") == null ? null : store.one("contact", "WHERE tenant_id=? AND id=?", tenant, call.get("contact_id")); row.put("contact_phone", contact == null ? "" : contact.get("phone"));
                    if (call.get("started_at") != null && call.get("finished_at") != null) row.put("duration_ms", Duration.between(LocalDateTime.parse(call.get("started_at").toString()), LocalDateTime.parse(call.get("finished_at").toString())).toMillis());
                    Map<String,Object> metrics = store.jdbc().queryForMap("SELECT COUNT(*) AS n,COALESCE(SUM(duration_ms),0) AS duration FROM callmetric WHERE tenant_id=? AND call_session_id=?", tenant, id); row.put("metric_count", metrics.get("n")); row.put("metric_total_duration_ms", metrics.get("duration"));
                    row.put("speech_turn_count", store.count("speechturn", "WHERE tenant_id=? AND call_session_id=?", tenant, id)); row.put("final_speech_turn_count", store.count("speechturn", "WHERE tenant_id=? AND call_session_id=? AND is_final=?", tenant, id, true));
                    Map<String,Object> analysis = store.one("callanalysis", "WHERE tenant_id=? AND call_session_id=?", tenant, id);
                    if (analysis != null) { for (String field : Arrays.asList("result_code", "intent", "sentiment", "qa_score")) row.put(field, analysis.get(field)); row.put("qa_flags", analysis.get("qa_flags_json")); row.put("analysis_summary", analysis.get("summary")); }
                    List<Map<String,Object>> assets = store.list("recordingasset", "WHERE tenant_id=? AND call_session_id=? ORDER BY created_at,id", tenant, id);
                    row.put("recording_urls", joined(assets, "provider_url")); row.put("recording_storage_uris", joined(assets, "storage_uri")); row.put("recording_states", joined(assets, "state")); row.put("recording_retention_untils", joined(assets, "retention_until"));
                    List<String> cells = new ArrayList<>(); for (String header : headers) cells.add(ContactFiles.safe(row.get(header))); csv.printRecord(cells);
                }
                Map<String,Object> last = batch.get(batch.size()-1); lastId = store.encode("callsession", "id", last.get("id")); lastCreated = store.encode("callsession", "created_at", last.get("created_at")); csv.flush();
            }
            csv.flush();
        };
        return ResponseEntity.ok().header("Content-Type", "text/csv;charset=UTF-8").header("Content-Disposition", "attachment; filename=call-evidence-" + tenant + ".csv").body(stream);
    }
}
