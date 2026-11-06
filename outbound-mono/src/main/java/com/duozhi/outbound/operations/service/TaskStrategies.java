package com.duozhi.outbound.operations.service;

import static com.duozhi.outbound.workspace.service.WorkspaceDocuments.*;

import com.duozhi.outbound.auth.domain.Identity;
import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.workspace.service.WorkspaceDocuments;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

/**
 * Tenant-owned strategies. Admission reads current rules so disabling a rule takes effect
 * immediately.
 */
@Service
public class TaskStrategies {
    private final WorkspaceDocuments docs;
    @org.springframework.beans.factory.annotation.Autowired
    private WecomAutomation wecom;

    public TaskStrategies(WorkspaceDocuments docs) {
        this.docs = docs;
    }

    public String kind(String type) {
        return switch (type) {
            case "frequency" -> "frequency_rule";
            case "evaluation" -> "evaluation_rule";
            case "achievement" -> "achievement_rule";
            default -> throw new ApiError(404, "规则类型不存在");
        };
    }

    public Object list(long tenant, String type) {
        return docs.list(tenant, kind(type), null);
    }

    public Map<String, Object> save(Identity i, String type, String id, Map<String, Object> body) {
        if (!id.matches("[a-zA-Z0-9_-]{1,64}")) throw new ApiError(422, "规则编号无效");
        var v = new LinkedHashMap<>(map(body.get("config")));
        v.put("name", text(v, "name", 160, true));
        if (!(v.getOrDefault("enabled", true) instanceof Boolean))
            throw new ApiError(422, "规则状态无效");
        v.putIfAbsent("enabled", true);
        switch (type) {
            case "frequency" -> {
                v.put("hours", OutboundRules.integer(v.get("hours"), 1, 720));
                v.put("limit", OutboundRules.integer(v.get("limit"), 1, 1000));
                if (!Set.of("all", "answered", "unanswered").contains(v.get("condition")))
                    throw new ApiError(422, "拦截条件无效");
                if (!(v.getOrDefault("default_rule", false) instanceof Boolean))
                    throw new ApiError(422, "默认规则选项无效");
                v.putIfAbsent("default_rule", false);
            }
            case "evaluation" -> {
                if (!Set.of("connection", "intent", "success").contains(v.get("metric")))
                    throw new ApiError(422, "评估指标无效");
                v.put(
                        "minimum_connected",
                        OutboundRules.integer(v.get("minimum_connected"), 1, 1000000));
                v.put("recommend", OutboundRules.integer(v.get("recommend"), 1, 100));
                v.put("reject", OutboundRules.integer(v.get("reject"), 0, 99));
                if ((int) v.get("reject") >= (int) v.get("recommend"))
                    throw new ApiError(422, "不建议阈值须小于建议阈值");
            }
            case "achievement" -> {
                v.put(
                        "minimum_connected",
                        OutboundRules.integer(v.get("minimum_connected"), 1, 1000000));
                v.put("rate", OutboundRules.integer(v.get("rate"), 0, 100));
                long campaign = number(v.getOrDefault("campaign_id", 0));
                if (campaign > 0
                        && docs.one(
                                        "SELECT id FROM campaign WHERE tenant_id=? AND id=? AND status<>'deleted'",
                                        i.tenant(),
                                        campaign)
                                == null) throw new ApiError(422, "任务不存在");
                v.put("campaign_id", campaign);
            }
            default -> throw new ApiError(404, "规则类型不存在");
        }
        return docs.tx.serialized(
                () -> {
                    if (type.equals("frequency") && Boolean.TRUE.equals(v.get("default_rule"))) {
                        for (var other : docs.list(i.tenant(), kind(type), null)) {
                            if (id.equals(other.get("object_id"))) continue;
                            var c = new LinkedHashMap<>(map(other.get("draft")));
                            if (!Boolean.TRUE.equals(c.get("default_rule"))) continue;
                            c.put("default_rule", false);
                            var saved =
                                    docs.save(
                                            i,
                                            kind(type),
                                            other.get("object_id").toString(),
                                            "",
                                            c,
                                            number(other.get("revision")));
                            docs.publish(
                                    i,
                                    kind(type),
                                    other.get("object_id").toString(),
                                    number(saved.get("revision")),
                                    c);
                        }
                    }
                    var saved = docs.save(i, kind(type), id, "", v, number(body.get("revision")));
                    return docs.publish(i, kind(type), id, number(saved.get("revision")), v);
                });
    }

    public Object delete(Identity i, String type, String id, int revision) {
        return docs.tx.serialized(
                () -> {
                    var doc = docs.require(i.tenant(), kind(type), id);
                    if (number(doc.get("revision")) != revision)
                        throw new ApiError(409, "规则已被修改，请刷新");
                    docs.jdbc.update(
                            "DELETE FROM platform_document WHERE tenant_id=? AND kind=? AND object_id=?",
                            i.tenant(),
                            kind(type),
                            id);
                    return Map.of("deleted", true);
                });
    }

    private List<Map<String, Object>> admissionRules(long tenant, String type) {
        var batch = com.duozhi.outbound.dial.domain.DialBatchScope.current();
        if (batch == null) return docs.list(tenant, kind(type), null);
        String key = "task-strategies:" + tenant + ":" + type;
        var cached =
                batch.facts.computeIfAbsent(
                        key, ignored -> Map.of("rules", docs.list(tenant, kind(type), null)));
        return (List<Map<String, Object>>) cached.get("rules");
    }

    public Map<String, Object> selected(long tenant, String type, Object id) {
        if (id == null || id.toString().isBlank()) return Map.of();
        var doc =
                com.duozhi.outbound.dial.domain.DialBatchScope.current() == null
                        ? docs.find(tenant, kind(type), id.toString())
                        : admissionRules(tenant, type).stream()
                                .filter(d -> id.toString().equals(d.get("object_id")))
                                .findFirst()
                                .orElse(null);
        return doc == null || !Boolean.TRUE.equals(map(doc.get("published")).get("enabled"))
                ? Map.of()
                : map(doc.get("published"));
    }

    public Map<String, Object> options(long tenant, Object raw) {
        var v = new LinkedHashMap<>(map(raw == null ? Map.of() : raw));
        for (String key : List.of("frequency_rule_id", "evaluation_rule_id")) {
            String id = Objects.toString(v.get(key), "");
            if (!id.isBlank()
                    && selected(
                                    tenant,
                                    key.startsWith("frequency") ? "frequency" : "evaluation",
                                    id)
                            .isEmpty()) throw new ApiError(422, "请选择本租户启用的规则");
            v.put(key, id);
        }
        String order = Objects.toString(v.get("call_order"), "sequential");
        if (!Set.of("sequential", "random").contains(order)) throw new ApiError(422, "呼叫顺序无效");
        v.put("call_order", order);
        var filters = v.getOrDefault("filter_group_ids", List.of());
        if (!(filters instanceof List<?> ids) || ids.size() > 100)
            throw new ApiError(422, "过滤策略无效");
        for (var id : (List<?>) filters) docs.require(tenant, "filter_group", id.toString());
        v.put("filter_group_ids", filters);
        var windows =
                OutboundRules.normalize(
                                Map.of(
                                        "windows",
                                        v.getOrDefault(
                                                "windows",
                                                List.of(Map.of("start", "00:00", "end", "24:00")))))
                        .get("windows");
        v.put("windows", windows);
        long owner = number(v.getOrDefault("owner_id", 0));
        if (owner > 0
                && docs.one(
                                "SELECT id FROM sys_user WHERE tenant_id=? AND id=? AND enabled=1",
                                tenant,
                                owner)
                        == null) throw new ApiError(422, "任务所属账号无效");
        v.put("owner_id", owner);
        for (String key : List.of("mask_numbers", "block_successful", "auto_wecom")) {
            if (!(v.getOrDefault(key, false) instanceof Boolean)) throw new ApiError(422, "任务选项无效");
            v.putIfAbsent(key, false);
        }
        wecom.validate(tenant, Boolean.TRUE.equals(v.get("auto_wecom")));
        return v;
    }

    public String frequency(long tenant, String phone, Object id, Instant now) {
        Map<String, Object> rule = selected(tenant, "frequency", id);
        if (rule.isEmpty() && (id == null || id.toString().isBlank())) {
            rule =
                    admissionRules(tenant, "frequency").stream()
                            .map(d -> map(d.get("published")))
                            .filter(
                                    d ->
                                            Boolean.TRUE.equals(d.get("enabled"))
                                                    && Boolean.TRUE.equals(d.get("default_rule")))
                            .findFirst()
                            .orElse(Map.of());
        }
        if (rule.isEmpty()) return "";
        String condition = rule.get("condition").toString();
        String answered =
                "EXISTS(SELECT 1 FROM callusage u WHERE u.tenant_id=c.tenant_id AND u.call_session_id=c.id AND u.attempt=JSON_EXTRACT(e.payload,'$.attempt') AND u.answered_at IS NOT NULL)";
        long count =
                docs.jdbc.queryForObject(
                        "SELECT COUNT(*) FROM callevent e JOIN callsession c ON c.id=e.call_session_id WHERE c.tenant_id=? AND c.phone=? AND e.event_type='dial_attempt_claimed' AND e.created_at>?"
                                + (condition.equals("all")
                                        ? ""
                                        : " AND "
                                                + (condition.equals("unanswered") ? "NOT " : "")
                                                + answered),
                        Long.class,
                        tenant,
                        phone,
                        LocalDateTime.ofInstant(
                                now.minusSeconds(3600L * ((Number) rule.get("hours")).longValue()),
                                ZoneOffset.UTC));
        return count >= ((Number) rule.get("limit")).longValue() ? "frequency_rule_limit" : "";
    }

    public Map<String, Object> stats(long tenant, Object campaign) {
        return docs.jdbc.queryForMap(
                "SELECT COUNT(*) AS total,COALESCE(SUM(c.attempts),0) AS attempts,COALESCE(SUM(EXISTS(SELECT 1 FROM callusage u WHERE u.tenant_id=c.tenant_id AND u.call_session_id=c.id AND u.answered_at IS NOT NULL)),0) AS connected,COALESCE(SUM(a.result_code IN ('qualified_lead','interested','callback_confirmed')),0) AS interested,COALESCE(SUM(a.result_code='qualified_lead'),0) AS successful FROM callsession c LEFT JOIN callanalysis a ON a.tenant_id=c.tenant_id AND a.call_session_id=c.id WHERE c.tenant_id=? AND c.campaign_id=? AND c.status IN ('COMPLETED','FAILED','BUSY','NO_ANSWER','CANCELED') AND a.id IS NOT NULL",
                tenant,
                campaign);
    }

    public Map<String, Object> assessContacts(
            long tenant, Object ruleId, Collection<Long> contacts) {
        var rule = selected(tenant, "evaluation", ruleId);
        if (rule.isEmpty()) return Map.of("advice", "none", "label", "未使用评估规则");
        var counts = new LinkedHashMap<String, Object>();
        for (String k : List.of("connected", "attempts", "interested", "successful"))
            counts.put(k, 0L);
        var ids = new ArrayList<>(contacts);
        for (int offset = 0; offset < ids.size(); offset += 500) {
            var chunk = ids.subList(offset, Math.min(offset + 500, ids.size()));
            var params = new ArrayList<Object>();
            params.add(tenant);
            params.addAll(chunk);
            var row =
                    docs.jdbc.queryForMap(
                            "SELECT COALESCE(SUM(c.attempts),0) AS attempts,COALESCE(SUM(EXISTS(SELECT 1 FROM callusage u WHERE u.tenant_id=c.tenant_id AND u.call_session_id=c.id AND u.answered_at IS NOT NULL)),0) AS connected,COALESCE(SUM(a.result_code IN ('qualified_lead','interested','callback_confirmed')),0) AS interested,COALESCE(SUM(a.result_code='qualified_lead'),0) AS successful FROM callsession c LEFT JOIN callanalysis a ON a.tenant_id=c.tenant_id AND a.call_session_id=c.id WHERE c.tenant_id=? AND c.contact_id IN ("
                                    + String.join(",", Collections.nCopies(chunk.size(), "?"))
                                    + ")",
                            params.toArray());
            counts.replaceAll(
                    (key, value) ->
                            ((Number) value).longValue() + ((Number) row.get(key)).longValue());
        }
        return assess(rule, counts);
    }

    public static Map<String, Object> assess(Map<String, Object> rule, Map<String, Object> stats) {
        long connected = number(stats.get("connected"));
        if (connected < number(rule.get("minimum_connected")))
            return Map.of("advice", "insufficient", "label", "样本不足", "connected", connected);
        String metric = Objects.toString(rule.get("metric"), "connection");
        long denominator = metric.equals("connection") ? number(stats.get("attempts")) : connected;
        long numerator =
                metric.equals("connection")
                        ? connected
                        : number(stats.get(metric.equals("intent") ? "interested" : "successful"));
        double rate = denominator == 0 ? 0 : Math.min(100, 100.0 * numerator / denominator);
        String advice =
                rate >= number(rule.get("recommend"))
                        ? "recommend"
                        : rate <= number(rule.get("reject")) ? "reject" : "caution";
        return Map.of(
                "advice",
                advice,
                "label",
                Map.of("recommend", "建议外呼", "reject", "不建议外呼", "caution", "谨慎外呼").get(advice),
                "rate",
                rate,
                "connected",
                connected);
    }

    public boolean guard(Map<String, Object> call) {
        if (call.get("campaign_id") == null) return true;
        long tenant = number(call.get("tenant_id"));
        var rules = admissionRules(tenant, "achievement");
        if (rules.isEmpty()) return true;
        Map<String, Object> stats = null;
        for (var d : rules) {
            var r = map(d.get("published"));
            if (!Boolean.TRUE.equals(r.get("enabled"))
                    || number(r.get("campaign_id")) > 0
                            && number(r.get("campaign_id")) != number(call.get("campaign_id")))
                continue;
            if (stats == null) stats = stats(tenant, call.get("campaign_id"));
            long connected = number(stats.get("connected"));
            if (connected < number(r.get("minimum_connected"))
                    || connected == 0
                    || 100.0 * number(stats.get("interested")) / connected >= number(r.get("rate")))
                continue;
            docs.jdbc.update(
                    "UPDATE campaign SET status='paused' WHERE tenant_id=? AND id=? AND status='running'",
                    tenant,
                    call.get("campaign_id"));
            docs.jdbc.update(
                    "INSERT IGNORE INTO ops_notice(tenant_id,user_id,event_key,title,path) SELECT tenant_id,created_by,?,?,? FROM campaign WHERE tenant_id=? AND id=? AND created_by IS NOT NULL",
                    "intent-guard:" + call.get("campaign_id") + ":" + d.get("revision"),
                    "任务意向达成率低于阈值，已自动暂停",
                    "/tasks/current",
                    tenant,
                    call.get("campaign_id"));
            return false;
        }
        return true;
    }

    public boolean successful(long tenant, String phone) {
        return docs.jdbc.queryForObject(
                        "SELECT COUNT(*) FROM ops_lead l JOIN contact c ON c.tenant_id=l.tenant_id AND c.id=l.contact_id WHERE l.tenant_id=? AND c.phone=? AND l.stage='completed'",
                        Long.class,
                        tenant,
                        phone)
                > 0;
    }
}
