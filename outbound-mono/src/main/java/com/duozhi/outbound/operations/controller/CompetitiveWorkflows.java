package com.duozhi.outbound.operations.controller;

import static com.duozhi.outbound.workspace.service.WorkspaceDocuments.*;

import com.duozhi.outbound.ai.service.DialogueRules;
import com.duozhi.outbound.ai.service.ScriptIntents;
import com.duozhi.outbound.auth.domain.Identity;
import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.operations.service.OutboundRules;
import com.duozhi.outbound.workspace.service.WorkspaceDocuments;
import jakarta.servlet.http.HttpServletRequest;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.web.bind.annotation.*;

/** Product workflows with explicit tenant binding and revision checks. */
@RestController
@RequestMapping("/api/v1/ops/benchmark")
public class CompetitiveWorkflows {
    private final WorkspaceDocuments docs;
    private final OutboundRules rules;

    @org.springframework.beans.factory.annotation.Autowired
    private com.duozhi.outbound.operations.service.AccountResources accounts;

    @GetMapping("/accounts")
    public Object accounts(HttpServletRequest r) {
        return accounts.list(admin(r));
    }

    @PutMapping("/accounts/{id}")
    public Object account(
            @PathVariable
            long id, @RequestBody
            Map<String, Object> body, HttpServletRequest r) {
        return accounts.save(admin(r), id, body);
    }

    @PostMapping("/accounts/{id}/allocate")
    public Object allocate(
            @PathVariable
            long id, @RequestBody
            Map<String, Object> body, HttpServletRequest r) {
        return accounts.allocate(admin(r), id, body);
    }

    @GetMapping("/accounts/ledger")
    public Object accountLedger(HttpServletRequest r) {
        var i = admin(r);
        return docs.jdbc.queryForList(
                "SELECT t.id,t.user_id,u.full_name,t.amount,t.note,t.source_mode,t.created_at FROM ops_account_transfer t JOIN sys_user u ON u.tenant_id=t.tenant_id AND u.id=t.user_id WHERE t.tenant_id=? ORDER BY t.id DESC LIMIT 2000",
                i.tenant());
    }

    public CompetitiveWorkflows(WorkspaceDocuments docs, OutboundRules rules) {
        this.docs = docs;
        this.rules = rules;
    }

    private Identity admin(HttpServletRequest r) {
        var i = Identity.from(r);
        i.requireUser();
        i.admin("operations:write");
        return i;
    }

    @GetMapping("/task-settings")
    public Object settings(HttpServletRequest r) {
        return rules.read(admin(r).tenant());
    }

    @GetMapping("/task-settings/defaults")
    public Object defaults(HttpServletRequest r) {
        admin(r);
        return OutboundRules.defaults();
    }

    @GetMapping("/shares")
    public Object shares(HttpServletRequest r) {
        return docs.list(admin(r).tenant(), "script_share", null);
    }

    @GetMapping("/line-profiles")
    public Object lineProfiles(HttpServletRequest r) {
        return docs.list(admin(r).tenant(), "line_profile", null);
    }

    @PutMapping("/line-profiles/{id}")
    public Object lineProfile(
            @PathVariable
            long id, @RequestBody
            Map<String, Object> b, HttpServletRequest r) {
        var i = admin(r);
        if (docs.one("SELECT id FROM telephonyline WHERE tenant_id=? AND id=?", i.tenant(), id)
                == null) throw new ApiError(404, "线路不存在");
        var v = map(b.get("config"));
        v.put("name", "线路显示资料");
        for (String key : List.of("physical_id", "source")) v.put(key, text(v, key, 160, false));
        for (String key : List.of("reference_price", "unit_price")) {
            String raw = Objects.toString(v.get(key), "");
            if (raw.isBlank()) {
                v.put(key, null);
                continue;
            }
            try {
                var n = new java.math.BigDecimal(raw);
                if (n.signum() < 0
                        || n.scale() > 6
                        || n.compareTo(new java.math.BigDecimal("1000000")) > 0)
                    throw new NumberFormatException();
                v.put(key, n);
            } catch (NumberFormatException e) {
                throw new ApiError(422, "价格须为0到1000000，最多6位小数");
            }
        }
        return docs.save(i, "line_profile", "" + id, "", v, number(b.get("revision")));
    }

    @PutMapping("/task-settings")
    public Object saveSettings(@RequestBody
    Map<String, Object> b, HttpServletRequest r) {
        return rules.save(admin(r), b);
    }

    private static String kind(String name) {
        return switch (name) {
            case "filters" -> "filter_group";
            case "keywords" -> "keyword_scene";
            case "tags" -> "tag_group";
            case "forms" -> "form_assignment";
            default -> throw new ApiError(404, "配置类型不存在");
        };
    }

    @GetMapping("/catalog/{name}")
    public Object catalog(@PathVariable
    String name, HttpServletRequest r) {
        return docs.list(admin(r).tenant(), kind(name), null);
    }

    @PutMapping("/catalog/{name}/{id}")
    public Object saveCatalog(
            @PathVariable
            String name,
            @PathVariable
            String id,
            @RequestBody
            Map<String, Object> body,
            HttpServletRequest r) {
        var i = admin(r);
        if (!id.matches("[a-zA-Z0-9_-]{1,64}")) throw new ApiError(422, "配置编号无效");
        var v = new LinkedHashMap<>(map(body.get("config")));
        text(v, "name", 160, true);
        switch (name) {
            case "filters" -> {
                String action = Objects.toString(v.get("action"), "intercept");
                if (!Set.of("blacklist", "intercept").contains(action))
                    throw new ApiError(422, "策略类型无效");
                v.put("action", action);
                if (!(v.get("enabled") instanceof Boolean)) throw new ApiError(422, "请选择生效状态");
                if (!(v.get("rules") instanceof List<?> list) || list.size() > 200)
                    throw new ApiError(422, "每组最多200条规则");
                var clean = new ArrayList<Map<String, Object>>();
                for (var item : list) {
                    var rule = map(item);
                    String type = text(rule, "type", 30, true),
                            value = text(rule, "value", 100, true);
                    if (!Set.of("phone", "prefix", "city", "frequency").contains(type))
                        throw new ApiError(422, "过滤类型无效");
                    if (Set.of("phone", "prefix").contains(type)
                            && !value.matches("\\+?[0-9]{1,32}"))
                        throw new ApiError(422, "号码或前缀无效");
                    if (type.equals("frequency")) OutboundRules.integer(value, 1, 20);
                    clean.add(Map.of("type", type, "value", value));
                }
                v.put("rules", clean);
                v.put("remark", text(v, "remark", 1000, false));
            }
            case "keywords" ->
                    v.put("rules", ScriptIntents.normalize(v.getOrDefault("rules", List.of())));
            case "tags" -> {
                if (!(v.get("tags") instanceof List<?> tags) || tags.size() > 100)
                    throw new ApiError(422, "最多100个标签");
                v.put(
                        "tags",
                        tags.stream()
                                .map(t -> text(Map.of("tag", t), "tag", 80, true))
                                .distinct()
                                .toList());
            }
            case "forms" -> {
                long form = number(v.get("form_id"));
                if (docs.one(
                                "SELECT id FROM ops_config WHERE tenant_id=? AND id=? AND kind='form'",
                                i.tenant(),
                                form)
                        == null) throw new ApiError(422, "表单不存在");
                if (!(v.get("user_ids") instanceof List<?> users) || users.size() > 2000)
                    throw new ApiError(422, "表单账号无效");
                for (Object user : users)
                    if (docs.one(
                                    "SELECT id FROM sys_user WHERE tenant_id=? AND id=? AND enabled=1",
                                    i.tenant(),
                                    number(user))
                            == null) throw new ApiError(422, "分配账号不存在或已冻结");
                if (!(v.getOrDefault("reviewer_ids", List.of()) instanceof List<?> reviewers)
                        || reviewers.size() > 2000) throw new ApiError(422, "质检人员列表无效");
                for (var reviewer : (List<?>) v.getOrDefault("reviewer_ids", List.of()))
                    if (docs.one(
                                    "SELECT id FROM sys_user WHERE tenant_id=? AND id=? AND enabled=1",
                                    i.tenant(),
                                    number(reviewer))
                            == null) throw new ApiError(422, "质检人员不存在或已冻结");
                v.putIfAbsent("reviewer_ids", List.of());
                if (!(v.getOrDefault("enabled", true) instanceof Boolean))
                    throw new ApiError(422, "表单分配状态无效");
                v.putIfAbsent("enabled", true);
                v.put("review_mode", "round_robin");
            }
            default -> throw new ApiError(404, "配置类型不存在");
        }
        return docs.tx.serialized(
                () -> {
                    if (name.equals("tags"))
                        for (Object tag : (List<?>) v.get("tags")) {
                            var existing =
                                    docs.one(
                                            "SELECT id FROM ops_config WHERE tenant_id=? AND kind='tag' AND name=? LIMIT 1",
                                            i.tenant(),
                                            tag);
                            if (existing == null)
                                docs.jdbc.update(
                                        "INSERT INTO ops_config(tenant_id,kind,name,revision,config_json) VALUES(?,'tag',?,1,?)",
                                        i.tenant(),
                                        tag,
                                        docs.json.json(
                                                Map.of(
                                                        "target",
                                                        "contact",
                                                        "group",
                                                        v.get("name"))));
                        }
                    var saved = docs.save(i, kind(name), id, "", v, number(body.get("revision")));
                    return docs.publish(i, kind(name), id, number(saved.get("revision")), v);
                });
    }

    @PostMapping("/keywords/{id}/apply/{script}")
    public Object applyKeywords(
            @PathVariable
            String id,
            @PathVariable
            String script,
            @RequestBody
            Map<String, Object> body,
            HttpServletRequest r) {
        var i = admin(r);
        return docs.tx.serialized(
                () -> {
                    var scene = docs.require(i.tenant(), "keyword_scene", id);
                    var doc = docs.require(i.tenant(), "script", script);
                    var draft = new LinkedHashMap<>(map(doc.get("draft")));
                    var policy = new LinkedHashMap<>(map(draft.get("policy")));
                    var all =
                            new ArrayList<Object>(
                                    (List<?>) policy.getOrDefault("intent_rules", List.of()));
                    all.addAll(
                            (List<?>) map(scene.get("published")).getOrDefault("rules", List.of()));
                    policy.put("intent_rules", ScriptIntents.normalize(all));
                    draft.put("policy", policy);
                    return docs.save(i, "script", script, "", draft, number(body.get("revision")));
                });
    }

    @GetMapping("/inspection")
    public Object inspection(@RequestParam
    Map<String, String> q, HttpServletRequest r) {
        var i = admin(r);
        int page = OutboundRules.integer(q.getOrDefault("page", "1"), 1, 100000);
        int size = OutboundRules.integer(q.getOrDefault("size", "20"), 1, 200);
        String state = q.getOrDefault("state", "pending");
        if (!Set.of("pending", "adopted", "dismissed", "all").contains(state))
            throw new ApiError(422, "巡检状态无效");
        var args = new ArrayList<Object>();
        args.add(i.tenant());
        args.add(state);
        args.add(state);
        String where = " k.tenant_id=? AND (?='all' OR k.state=?)";
        for (String key :
                List.of(
                        "phone",
                        "script_id",
                        "campaign_id",
                        "node",
                        "batch",
                        "stage",
                        "intent",
                        "from",
                        "until")) {
            var value = q.getOrDefault(key, "");
            if (value.isBlank()) continue;
            where +=
                    switch (key) {
                        case "phone" -> " AND c.phone LIKE ?";
                        case "script_id" -> " AND g.script_template_id=?";
                        case "campaign_id" -> " AND k.campaign_id=?";
                        case "stage" -> " AND COALESCE(l.stage,'open')=?";
                        case "intent" -> " AND a.intent=?";
                        case "node" ->
                                " AND JSON_UNQUOTE(JSON_EXTRACT(k.context_json,'$.node_id'))=?";
                        case "batch" ->
                                " AND EXISTS(SELECT 1 FROM platform_contact_batch_member bm WHERE bm.tenant_id=c.tenant_id AND bm.contact_id=c.contact_id AND bm.batch_id=?)";
                        case "from" -> " AND k.created_at>=?";
                        default -> " AND k.created_at<=?";
                    };
            args.add(
                    Set.of("from", "until").contains(key)
                            ? Operations.time(value)
                            : key.equals("phone") ? "%" + value + "%" : value);
        }
        String join =
                " FROM platform_knowledge_gap k LEFT JOIN callsession c ON c.tenant_id=k.tenant_id AND c.id=k.source_id LEFT JOIN campaign g ON g.tenant_id=k.tenant_id AND g.id=k.campaign_id LEFT JOIN ops_lead l ON l.tenant_id=c.tenant_id AND l.contact_id=c.contact_id LEFT JOIN callanalysis a ON a.tenant_id=c.tenant_id AND a.call_session_id=c.id WHERE "
                        + where;
        long total = docs.jdbc.queryForObject("SELECT COUNT(*)" + join, Long.class, args.toArray());
        args.add(size);
        args.add((page - 1) * size);
        var rows =
                docs.jdbc.queryForList(
                        "SELECT (SELECT GROUP_CONCAT(b.batch_id) FROM platform_contact_batch_member b WHERE b.tenant_id=c.tenant_id AND b.contact_id=c.contact_id) AS batch,k.id,k.source_id AS call_id,k.question,k.state,k.revision,k.created_at,k.context_json,c.phone,g.script_template_id,a.intent,COALESCE(l.stage,'open') AS stage"
                                + join
                                + " ORDER BY k.created_at DESC,k.id LIMIT ? OFFSET ?",
                        args.toArray());
        for (var row : rows) {
            var context = docs.json.object(row.remove("context_json").toString());
            row.put(
                    "node",
                    context.getOrDefault("node_id", context.getOrDefault("current_node_id", "")));
            String question = row.get("question").toString();
            row.put("customer_intent", DialogueRules.classify(question));
            row.put("keywords", question.substring(0, Math.min(80, question.length())));
            row.put("regex", Pattern.quote(question.substring(0, Math.min(80, question.length()))));
            row.put(
                    "similar_reply",
                    context.getOrDefault("reply", context.getOrDefault("answer", "")));
            row.put("source_type", context.getOrDefault("source_type", "knowledge_miss"));
        }
        return Map.of(
                "rows", rows, "total", total, "suggestion_source", "基于未匹配原文和现有规则生成候选，采用后需调试并发布话术");
    }

    @PostMapping("/inspection/{id}/adopt")
    public Object adoptInspection(
            @PathVariable
            String id, @RequestBody
            Map<String, Object> body, HttpServletRequest r) {
        var i = admin(r);
        return docs.tx.serialized(
                () -> {
                    var gap =
                            docs.one(
                                    "SELECT * FROM platform_knowledge_gap WHERE tenant_id=? AND id=? FOR UPDATE",
                                    i.tenant(),
                                    id);
                    if (gap == null) throw new ApiError(404, "巡检问题不存在");
                    String scene = "gap-" + id;
                    if ("adopted".equals(gap.get("state"))
                            && "keyword_scene".equals(gap.get("resolution")))
                        return docs.require(i.tenant(), "keyword_scene", scene);
                    if (!"pending".equals(gap.get("state"))
                            || number(body.get("revision")) != number(gap.get("revision")))
                        throw new ApiError(409, "巡检问题状态已变化，请刷新");
                    String question = gap.get("question").toString();
                    question = question.substring(0, Math.min(80, question.length()));
                    var rules =
                            ScriptIntents.normalize(
                                    List.of(
                                            Map.of(
                                                    "name",
                                                    "客户提问",
                                                    "target",
                                                    "question",
                                                    "keywords",
                                                    question,
                                                    "regex",
                                                    Pattern.quote(question))));
                    var config =
                            Map.<String, Object>of(
                                    "name",
                                    "巡检 " + id.substring(0, Math.min(8, id.length())),
                                    "rules",
                                    rules);
                    var saved = docs.save(i, "keyword_scene", scene, "", config, 0);
                    var result =
                            docs.publish(
                                    i,
                                    "keyword_scene",
                                    scene,
                                    number(saved.get("revision")),
                                    config);
                    docs.jdbc.update(
                            "UPDATE platform_knowledge_gap SET state='adopted',revision=revision+1,resolution='keyword_scene',item_id=?,resolved_by=?,updated_at=UTC_TIMESTAMP(3) WHERE tenant_id=? AND id=?",
                            scene,
                            i.userId(),
                            i.tenant(),
                            id);
                    return result;
                });
    }

    @GetMapping("/dataset")
    public Object dataset(@RequestParam
    Map<String, String> q, HttpServletRequest r) {
        return datasetFor(q, admin(r));
    }

    public Object datasetFor(Map<String, String> q, Identity i) {
        i.admin("operations:write");
        int page = OutboundRules.integer(q.getOrDefault("page", "1"), 1, 100000);
        int size = OutboundRules.integer(q.getOrDefault("size", "20"), 1, 200);
        String phone = q.getOrDefault("phone", ""), batch = q.getOrDefault("batch", "");
        String where =
                " c.tenant_id=? AND c.phone LIKE ? AND (?='' OR EXISTS(SELECT 1 FROM platform_contact_batch_member b WHERE b.tenant_id=c.tenant_id AND b.contact_id=c.id AND b.batch_id=?))";
        Object[] args = {i.tenant(), "%" + phone + "%", batch, batch};
        var values = new ArrayList<>(Arrays.asList(args));
        values.add(size);
        values.add((page - 1) * size);
        var rows =
                docs.jdbc.queryForList(
                        "SELECT c.id,c.name,c.phone,c.created_at,(SELECT GROUP_CONCAT(b.batch_id) FROM platform_contact_batch_member b WHERE b.tenant_id=c.tenant_id AND b.contact_id=c.id) AS batches,s.attempts,s.connected,s.longest,s.total_seconds,s.last_call,lc.status,lc.last_error AS hangup_reason,a.intent,JSON_UNQUOTE(JSON_EXTRACT(CASE WHEN JSON_VALID(a.structured_json) THEN a.structured_json ELSE '{}' END,'$.rule_result.grade')) AS grade,d.city FROM contact c LEFT JOIN LATERAL(SELECT SUM(x.attempts) AS attempts,SUM((SELECT COUNT(*) FROM callusage u WHERE u.tenant_id=x.tenant_id AND u.call_session_id=x.id AND u.answered_at IS NOT NULL)) AS connected,MAX((SELECT MAX(u.telephony_seconds) FROM callusage u WHERE u.tenant_id=x.tenant_id AND u.call_session_id=x.id)) AS longest,SUM((SELECT SUM(u.telephony_seconds) FROM callusage u WHERE u.tenant_id=x.tenant_id AND u.call_session_id=x.id)) AS total_seconds,MAX(x.started_at) AS last_call FROM callsession x WHERE x.tenant_id=c.tenant_id AND x.contact_id=c.id) s ON TRUE LEFT JOIN LATERAL(SELECT x.* FROM callsession x WHERE x.tenant_id=c.tenant_id AND x.contact_id=c.id ORDER BY x.created_at DESC,x.id DESC LIMIT 1) lc ON TRUE LEFT JOIN callanalysis a ON a.tenant_id=lc.tenant_id AND a.call_session_id=lc.id LEFT JOIN ops_call_dimension d ON d.tenant_id=lc.tenant_id AND d.call_id=lc.id WHERE"
                                + where
                                + " ORDER BY c.id DESC LIMIT ? OFFSET ?",
                        values.toArray());
        return Map.of(
                "rows",
                rows,
                "total",
                docs.jdbc.queryForObject(
                        "SELECT COUNT(*) FROM contact c WHERE" + where, Long.class, args));
    }

    @GetMapping("/reports/intercept/details")
    public Object interceptionDetails(@RequestParam
    Map<String, String> q, HttpServletRequest r) {
        var i = admin(r);
        var args = new ArrayList<Object>();
        args.add(i.tenant());
        String where = "x.tenant_id=?";
        for (String key : List.of("from", "until"))
            if (!q.getOrDefault(key, "").isBlank()) {
                where += " AND x.created_at" + (key.equals("from") ? ">=?" : "<=?");
                args.add(Operations.time(q.get(key)));
            }
        if (!q.getOrDefault("reason", "").isBlank()) {
            where += " AND x.reason=?";
            args.add(q.get("reason"));
        }
        return docs.jdbc.queryForList(
                "SELECT x.*,c.campaign_id,IF(x.reason LIKE 'during:%','during','before') AS phase FROM ops_interception x LEFT JOIN callsession c ON c.tenant_id=x.tenant_id AND c.id=x.call_id WHERE "
                        + where
                        + " ORDER BY x.created_at DESC,x.id DESC LIMIT 1000",
                args.toArray());
    }

    @GetMapping("/reports/{kind}")
    public Object report(
            @PathVariable
            String kind, @RequestParam
            Map<String, String> q, HttpServletRequest r) {
        return reportFor(kind, q, admin(r));
    }

    public Object reportFor(String kind, Map<String, String> q, Identity i) {
        if (kind.equals("routes")) return routeReport(q, i);
        i.admin("operations:write");
        LocalDateTime
                from =
                        q.containsKey("from")
                                ? Operations.time(q.get("from"))
                                : LocalDateTime.now(ZoneOffset.UTC).minusDays(30),
                until =
                        q.containsKey("until")
                                ? Operations.time(q.get("until"))
                                : LocalDateTime.now(ZoneOffset.UTC);
        if (from.isAfter(until) || Duration.between(from, until).toDays() > 366)
            throw new ApiError(422, "请选择一年内的有效日期");
        return switch (kind) {
            case "intercept" ->
                    docs.jdbc.queryForList(
                            "SELECT totals.*,ROUND(100*totals.blocked/NULLIF(totals.outbound,0),2) AS intercept_rate FROM (SELECT grouped.day_key AS date,grouped.blocked,grouped.reason,IF(grouped.reason LIKE 'during:%','during','before') AS phase,(SELECT COALESCE(SUM(c.attempts),0) FROM callsession c WHERE c.tenant_id=? AND DATE(TIMESTAMPADD(HOUR,8,c.created_at))=grouped.day_key)+(SELECT COUNT(*) FROM ops_interception y WHERE y.tenant_id=? AND DATE(TIMESTAMPADD(HOUR,8,y.created_at))=grouped.day_key AND y.reason NOT LIKE 'during:%') AS outbound FROM (SELECT DATE(TIMESTAMPADD(HOUR,8,created_at)) AS day_key,COUNT(*) AS blocked,reason FROM ops_interception WHERE tenant_id=? AND created_at BETWEEN ? AND ? GROUP BY day_key,reason) grouped) totals ORDER BY date DESC",
                            i.tenant(), i.tenant(), i.tenant(), from, until);
            case "follow" ->
                    docs.jdbc.queryForList(
                            "SELECT u.id,u.full_name AS agent,u.agent_status,COUNT(l.contact_id) AS total,SUM(l.stage='open') AS pending,SUM(l.stage='completed') AS successful,SUM(l.next_at IS NOT NULL AND l.stage NOT IN ('completed','invalid')) AS later,SUM(l.stage='invalid') AS abandoned FROM sys_user u LEFT JOIN ops_lead l ON l.tenant_id=u.tenant_id AND l.owner_id=u.id WHERE u.tenant_id=? GROUP BY u.id ORDER BY u.id",
                            i.tenant());
            case "sms" ->
                    docs.jdbc.queryForList(
                            "SELECT DATE(TIMESTAMPADD(HOUR,8,s.created_at)) AS date,c.campaign_id,MAX(g.name) AS task,COUNT(*) AS sent,SUM(s.state='delivered') AS successful,ROUND(100*SUM(s.state='delivered')/NULLIF(COUNT(*),0),2) AS success_rate,SUM(s.state='failed') AS failed FROM smslog s LEFT JOIN callsession c ON c.tenant_id=s.tenant_id AND c.id=s.call_session_id LEFT JOIN campaign g ON g.tenant_id=c.tenant_id AND g.id=c.campaign_id WHERE s.tenant_id=? AND s.created_at BETWEEN ? AND ? GROUP BY date,c.campaign_id ORDER BY date DESC",
                            i.tenant(),
                            from,
                            until);
            case "routes" ->
                    docs.jdbc.queryForList(
                            "SELECT c.id,c.phone,COALESCE(j.frozen_payload->>'$.call.telephony_line_id',c.telephony_line_id) AS line_id,c.campaign_id,COALESCE(j.attempt,c.attempts) AS attempt,j.state AS route_state,c.last_error,COALESCE(j.created_at,c.created_at) AS created_at FROM callsession c LEFT JOIN dial_command_journal j ON j.tenant_id=c.tenant_id AND j.call_id=c.id WHERE c.tenant_id=? AND COALESCE(j.created_at,c.created_at) BETWEEN ? AND ? ORDER BY created_at DESC LIMIT 2000",
                            i.tenant(),
                            from,
                            until);
            case "recharge" ->
                    docs.jdbc.queryForList(
                            "SELECT reference_no,kind,signed_amount,currency,settlement_source,posted_at FROM voice_statement_entry WHERE tenant_id=? AND kind IN ('opening','recharge','adjustment') AND posted_at BETWEEN ? AND ? ORDER BY posted_at DESC",
                            i.tenant(),
                            from,
                            until);
            default -> throw new ApiError(404, "报表类型不存在");
        };
    }

    private Object routeReport(Map<String, String> q, Identity i) {
        i.admin("operations:write");
        String
                from =
                        q.getOrDefault(
                                "from",
                                java.time.Instant.now().minusSeconds(30 * 86400L).toString()),
                until = q.getOrDefault("until", java.time.Instant.now().toString());
        if (from.isBlank()) from = java.time.Instant.now().minusSeconds(30 * 86400L).toString();
        if (until.isBlank()) until = java.time.Instant.now().toString();
        com.duozhi.outbound.workspace.service.TaskSchedule.validate(from, until);
        var args =
                new ArrayList<Object>(
                        List.of(
                                i.tenant(),
                                java.time.LocalDateTime.ofInstant(
                                        java.time.Instant.parse(from), java.time.ZoneOffset.UTC),
                                java.time.LocalDateTime.ofInstant(
                                        java.time.Instant.parse(until), java.time.ZoneOffset.UTC)));
        String where =
                " WHERE c.tenant_id=? AND COALESCE(j.created_at,c.created_at) BETWEEN ? AND ?";
        String base =
                "SELECT c.id,c.phone,COALESCE(j.frozen_payload->>'$.call.telephony_line_id',c.telephony_line_id) AS line_id,c.campaign_id,COALESCE(j.attempt,c.attempts) AS attempt,j.state AS route_state,c.last_error,COALESCE(j.created_at,c.created_at) AS created_at,g.name AS task_name,script.name AS script_name,l.name AS line_name,COALESCE(dim.line_group,lg.group_name) AS line_group,EXISTS(SELECT 1 FROM callusage u WHERE u.tenant_id=c.tenant_id AND u.call_session_id=c.id AND u.attempt=COALESCE(j.attempt,c.attempts) AND u.answered_at IS NOT NULL) AS connected,(SELECT COALESCE(JSON_UNQUOTE(JSON_EXTRACT(e.payload,'$.sip_code')),JSON_UNQUOTE(JSON_EXTRACT(e.payload,'$.payload.sip_code')),JSON_UNQUOTE(JSON_EXTRACT(e.payload,'$.signaling_code'))) FROM callevent e WHERE e.call_session_id=c.id AND COALESCE(JSON_EXTRACT(e.payload,'$.attempt'),JSON_EXTRACT(e.payload,'$.payload.attempt'),c.attempts)=COALESCE(j.attempt,c.attempts) AND JSON_VALID(e.payload) AND COALESCE(JSON_EXTRACT(e.payload,'$.sip_code'),JSON_EXTRACT(e.payload,'$.payload.sip_code'),JSON_EXTRACT(e.payload,'$.signaling_code')) IS NOT NULL ORDER BY e.id DESC LIMIT 1) AS signaling_code,(SELECT COALESCE(JSON_UNQUOTE(JSON_EXTRACT(e.payload,'$.ringing')),JSON_UNQUOTE(JSON_EXTRACT(e.payload,'$.payload.ringing'))) FROM callevent e WHERE e.call_session_id=c.id AND COALESCE(JSON_EXTRACT(e.payload,'$.attempt'),JSON_EXTRACT(e.payload,'$.payload.attempt'),c.attempts)=COALESCE(j.attempt,c.attempts) AND JSON_VALID(e.payload) AND COALESCE(JSON_EXTRACT(e.payload,'$.ringing'),JSON_EXTRACT(e.payload,'$.payload.ringing')) IS NOT NULL ORDER BY e.id DESC LIMIT 1) AS ringing FROM callsession c LEFT JOIN dial_command_journal j ON j.tenant_id=c.tenant_id AND j.call_id=c.id LEFT JOIN campaign g ON g.tenant_id=c.tenant_id AND g.id=c.campaign_id LEFT JOIN scripttemplate script ON script.tenant_id=g.tenant_id AND script.id=g.script_template_id LEFT JOIN telephonyline l ON l.tenant_id=c.tenant_id AND l.id=COALESCE(j.frozen_payload->>'$.call.telephony_line_id',c.telephony_line_id) LEFT JOIN ops_line_group_assignment lg ON lg.tenant_id=l.tenant_id AND lg.line_id=l.id LEFT JOIN ops_call_dimension dim ON dim.tenant_id=c.tenant_id AND dim.call_id=c.id";
        for (String key : List.of("phone", "result_code", "connected", "ringing", "line_group")) {
            String value = q.getOrDefault(key, "");
            if (value.isBlank()) continue;
            if (value.length() > 120) throw new ApiError(422, "线路筛选条件过长");
            if (key.equals("phone")) {
                where += " AND c.phone LIKE ?";
                args.add("%" + value + "%");
            }
        }
        String outer = "SELECT * FROM (" + base + where + ") routes WHERE 1=1";
        for (String key : List.of("result_code", "connected", "ringing", "line_group")) {
            String value = q.getOrDefault(key, "");
            if (value.isBlank()) continue;
            if (Set.of("connected", "ringing").contains(key)
                    && !Set.of("true", "false").contains(value))
                throw new ApiError(422, "线路状态筛选无效");
            String column = key.equals("result_code") ? "signaling_code" : key;
            outer += " AND " + column + "=?";
            args.add(key.equals("connected") ? (value.equals("true") ? 1 : 0) : value);
        }
        return docs.jdbc.queryForList(
                outer + " ORDER BY created_at DESC LIMIT 2000", args.toArray());
    }

    @GetMapping("/line-groups")
    public Object lineGroups(HttpServletRequest r) {
        return docs.list(admin(r).tenant(), "line_group_policy", null);
    }

    @PutMapping("/line-groups/{id}")
    public Object saveLineGroup(
            @PathVariable
            String id, @RequestBody
            Map<String, Object> b, HttpServletRequest r) {
        var i = admin(r);
        if (!id.matches("[A-Za-z0-9_-]{1,64}")) throw new ApiError(422, "线路组编号无效");
        var v = map(b.get("config"));
        String name = text(v, "name", 100, true);
        if (!(v.get("line_ids") instanceof List<?> ids) || ids.isEmpty() || ids.size() > 100)
            throw new ApiError(422, "请选择1至100条线路");
        for (var line : (List<?>) v.get("line_ids"))
            if (docs.one(
                            "SELECT id FROM telephonyline WHERE tenant_id=? AND id=?",
                            i.tenant(),
                            number(line))
                    == null) throw new ApiError(422, "线路不存在");
        return docs.tx.serialized(
                () -> {
                    var old = docs.find(i.tenant(), "line_group_policy", id);
                    if (old != null)
                        docs.jdbc.update(
                                "DELETE FROM ops_line_group_assignment WHERE tenant_id=? AND group_name=?",
                                i.tenant(),
                                old.get("name"));
                    for (var line : (List<?>) v.get("line_ids"))
                        docs.jdbc.update(
                                "INSERT INTO ops_line_group_assignment(tenant_id,line_id,group_name) VALUES(?,?,?) ON DUPLICATE KEY UPDATE group_name=VALUES(group_name)",
                                i.tenant(),
                                number(line),
                                name);
                    return docs.save(
                            i,
                            "line_group_policy",
                            id,
                            "",
                            Map.of(
                                    "name",
                                    name,
                                    "line_ids",
                                    v.get("line_ids"),
                                    "strategy",
                                    "priority_weighted_capacity"),
                            number(b.get("revision")));
                });
    }

    @GetMapping("/tasks")
    public Object tasks(@RequestParam
    Map<String, String> q, HttpServletRequest r) {
        return tasksFor(q, admin(r));
    }

    public Object tasksFor(Map<String, String> q, Identity i) {
        i.admin("operations:write");
        var args = new ArrayList<Object>();
        args.add(i.tenant());
        String started =
                "(SELECT MIN(s.started_at) FROM ops_call_statistics s WHERE s.tenant_id=g.tenant_id AND s.campaign_id=g.id)";
        String ended =
                "(SELECT MAX(s.ended_at) FROM ops_call_statistics s WHERE s.tenant_id=g.tenant_id AND s.campaign_id=g.id)";
        String where = "g.tenant_id=? AND g.status<> 'deleted'";
        if (q.getOrDefault("history", "false").equals("true"))
            where += " AND g.status IN ('completed','stopped','failed')";
        else where += " AND g.status NOT IN ('completed','stopped','failed')";
        for (String key :
                List.of(
                        "name",
                        "script_id",
                        "owner_id",
                        "mode",
                        "line_id",
                        "from",
                        "until",
                        "min_seconds",
                        "max_seconds",
                        "retry_limit",
                        "team_id",
                        "evaluation_rule",
                        "operation_type")) {
            String v = q.getOrDefault(key, "");
            if (v.isBlank()) continue;
            where +=
                    switch (key) {
                        case "name" -> " AND g.name LIKE ?";
                        case "script_id" -> " AND g.script_template_id=?";
                        case "owner_id" -> " AND g.created_by=?";
                        case "mode" -> " AND g.mode=?";
                        case "line_id" ->
                                " AND EXISTS(SELECT 1 FROM callsession cc WHERE cc.tenant_id=g.tenant_id AND cc.campaign_id=g.id AND cc.telephony_line_id=?)";
                        case "from" -> " AND COALESCE(" + started + ",g.created_at)>=?";
                        case "until" -> " AND COALESCE(" + ended + ",g.updated_at)<=?";
                        case "min_seconds" -> " AND stats.seconds>=?";
                        case "max_seconds" -> " AND stats.seconds<=?";
                        case "team_id" ->
                                " AND EXISTS(SELECT 1 FROM ops_call_dimension d JOIN callsession cc ON cc.tenant_id=d.tenant_id AND cc.id=d.call_id WHERE cc.tenant_id=g.tenant_id AND cc.campaign_id=g.id AND d.team_id=?)";
                        case "evaluation_rule" ->
                                " AND EXISTS(SELECT 1 FROM platform_campaign_snapshot snap WHERE snap.tenant_id=g.tenant_id AND snap.campaign_id=g.id AND JSON_SEARCH(snap.snapshot_json,'one',?,NULL,'$.policy.hangup_rules[*].label','$.policy.hangup_rules[*].id') IS NOT NULL)";
                        case "operation_type" -> " AND g.status=?";
                        default -> " AND g.retry_limit=?";
                    };
            args.add(
                    key.equals("name")
                            ? "%" + v + "%"
                            : Set.of("from", "until").contains(key) ? Operations.time(v) : v);
        }
        int target = number(map(rules.read(i.tenant()).get("draft")).get("target_intent_rate"));
        String suggestion = q.getOrDefault("suggestion", "");
        if (!suggestion.isBlank()) {
            if (!Set.of("insufficient", "improve", "continue").contains(suggestion))
                throw new ApiError(422, "外呼建议无效");
            if (suggestion.equals("insufficient"))
                where += " AND COALESCE(stats.connected_sessions,0)=0";
            else {
                where +=
                        " AND stats.connected_sessions>0 AND 100*COALESCE(stats.interested,0)/stats.connected_sessions "
                                + (suggestion.equals("improve") ? "<" : ">=")
                                + " ?";
                args.add(target);
            }
        }
        int page = OutboundRules.integer(q.getOrDefault("page", "1"), 1, 100000);
        int size = OutboundRules.integer(q.getOrDefault("size", "20"), 1, 200);
        String join =
                " FROM campaign g LEFT JOIN scripttemplate script ON script.tenant_id=g.tenant_id AND script.id=g.script_template_id LEFT JOIN ops_campaign_statistics stats ON stats.tenant_id=g.tenant_id AND stats.campaign_id=g.id WHERE "
                        + where;
        long total = docs.jdbc.queryForObject("SELECT COUNT(*)" + join, Long.class, args.toArray());
        args.add(size);
        args.add((page - 1) * size);
        String fields =
                List.of(
                                "sessions",
                                "dialed_contacts",
                                "finished",
                                "dialed",
                                "answered",
                                "connected_sessions",
                                "successful",
                                "interested",
                                "seconds",
                                "entered",
                                "taken")
                        .stream()
                        .map(name -> "COALESCE(stats." + name + ",0) AS " + name)
                        .collect(java.util.stream.Collectors.joining(","));
        var rows =
                docs.jdbc.queryForList(
                        "SELECT g.*,script.name AS script_name,"
                                + fields
                                + ","
                                + started
                                + " AS started_at,"
                                + ended
                                + " AS ended_at,stats.updated_at AS statistics_updated_at"
                                + join
                                + " ORDER BY g.id DESC LIMIT ? OFFSET ?",
                        args.toArray());
        taskDetails(i.tenant(), rows);
        for (var row : rows) {
            double answered = row.get("answered") instanceof Number n ? n.doubleValue() : 0,
                    connected =
                            row.get("connected_sessions") instanceof Number n ? n.doubleValue() : 0,
                    attempts = row.get("dialed") instanceof Number n ? n.doubleValue() : 0,
                    entered = row.get("entered") instanceof Number n ? n.doubleValue() : 0,
                    intents = row.get("interested") instanceof Number n ? n.doubleValue() : 0;
            row.put("answer_rate", attempts > 0 ? 100 * answered / attempts : null);
            row.put("answer_rate_basis", "dial_attempt_v2");
            row.put("outcome_rate_basis", "connected_session_v1");
            row.put("entry_rate", connected > 0 ? 100 * entered / connected : null);
            row.put("takeover_rate", entered > 0 ? 100 * number(row.get("taken")) / entered : null);
            row.put(
                    "success_rate",
                    connected > 0 ? 100 * number(row.get("successful")) / connected : null);
            row.put("evaluation_rule", "目标意向率 " + target + "%");
            row.put(
                    "evaluation_result",
                    connected == 0
                            ? "数据不足"
                            : 100 * intents / connected >= target ? "已达目标" : "未达目标");
            row.put(
                    "suggestion",
                    connected == 0
                            ? "数据不足"
                            : 100 * intents / connected >= target ? "继续拨打" : "优化话术");
            long contacts = ((Number) row.getOrDefault("contacts", 0)).longValue();
            row.put("progress", contacts > 0 ? 100 * number(row.get("finished")) / contacts : null);
        }
        return Map.of("rows", rows, "total", total);
    }

    /** Aggregate only the selected page; count/filter queries never traverse detail ledgers. */
    private void taskDetails(long tenant, List<Map<String, Object>> rows) {
        if (rows.isEmpty()) return;
        var byId = new LinkedHashMap<Long, Map<String, Object>>();
        var args = new ArrayList<Object>();
        args.add(tenant);
        for (var row : rows) {
            long id = ((Number) row.get("id")).longValue();
            byId.put(id, row);
            args.add(id);
            row.put("contacts", 0L);
            row.put("sms", 0L);
            for (String key : List.of("line_names", "team_names", "bill_seconds", "cost"))
                row.put(key, null);
        }
        String ids = String.join(",", Collections.nCopies(rows.size(), "?"));
        List<String> queries =
                List.of(
                        "SELECT c.campaign_id,GROUP_CONCAT(DISTINCT l.name) AS line_names FROM callsession c JOIN telephonyline l ON l.tenant_id=c.tenant_id AND l.id=c.telephony_line_id WHERE c.tenant_id=? AND c.campaign_id IN ("
                                + ids
                                + ") GROUP BY c.campaign_id",
                        "SELECT c.campaign_id,GROUP_CONCAT(DISTINCT t.name) AS team_names FROM callsession c JOIN ops_call_dimension d ON d.tenant_id=c.tenant_id AND d.call_id=c.id JOIN ops_team t ON t.tenant_id=d.tenant_id AND t.id=d.team_id WHERE c.tenant_id=? AND c.campaign_id IN ("
                                + ids
                                + ") GROUP BY c.campaign_id",
                        "SELECT g.id AS campaign_id,COUNT(cc.id) AS contacts FROM campaign g JOIN campaigncontact cc ON cc.campaign_id=g.id WHERE g.tenant_id=? AND g.id IN ("
                                + ids
                                + ") GROUP BY g.id",
                        "SELECT c.campaign_id,COUNT(*) AS sms FROM callsession c JOIN smslog s ON s.tenant_id=c.tenant_id AND s.call_session_id=c.id WHERE c.tenant_id=? AND c.campaign_id IN ("
                                + ids
                                + ") GROUP BY c.campaign_id",
                        "SELECT c.campaign_id,SUM(v.bill_seconds) AS bill_seconds,SUM(CASE WHEN v.kind='charge' THEN -v.signed_amount END) AS cost FROM callsession c JOIN voice_statement_entry v ON v.tenant_id=c.tenant_id AND v.call_id=c.id WHERE c.tenant_id=? AND c.campaign_id IN ("
                                + ids
                                + ") GROUP BY c.campaign_id");
        for (String sql : queries)
            for (var detail : docs.jdbc.queryForList(sql, args.toArray())) {
                var row = byId.get(((Number) detail.remove("campaign_id")).longValue());
                if (row != null) row.putAll(detail);
            }
    }

    @PostMapping("/notices/{id}/delete")
    public Object deleteNotice(@PathVariable
    long id, HttpServletRequest r) {
        var i = Identity.from(r);
        i.requireUser();
        int n =
                docs.jdbc.update(
                        "UPDATE ops_notice SET deleted_at=UTC_TIMESTAMP(3) WHERE tenant_id=? AND user_id=? AND id=?",
                        i.tenant(),
                        i.userId(),
                        id);
        if (n == 0) throw new ApiError(404, "消息不存在");
        return Map.of("deleted", true);
    }
}
