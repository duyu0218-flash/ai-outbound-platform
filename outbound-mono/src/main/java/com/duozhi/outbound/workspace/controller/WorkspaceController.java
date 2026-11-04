package com.duozhi.outbound.workspace.controller;

import static com.duozhi.outbound.workspace.service.WorkspaceDocuments.*;

import com.duozhi.outbound.agent.service.HumanRouting;
import com.duozhi.outbound.ai.service.AiRuntime;
import com.duozhi.outbound.ai.service.ConversationPolicy;
import com.duozhi.outbound.auth.domain.*;
import com.duozhi.outbound.common.dao.Store;
import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.common.support.JsonSupport;
import com.duozhi.outbound.customer.controller.ContactFiles;
import com.duozhi.outbound.operations.controller.ProductOperations;
import com.duozhi.outbound.script.controller.Templates;
import com.duozhi.outbound.workspace.service.WorkspaceDocuments;
import jakarta.servlet.http.HttpServletRequest;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.apache.commons.csv.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/workspace")
@AdminPermission("product:write")
public class WorkspaceController {
    private final WorkspaceDocuments docs;
    private final Store store;
    private final Templates templates;
    private final ProductOperations product;
    private final ConversationPolicy policies;
    private final HumanRouting routing;
    private final AiRuntime runtime;

    @org.springframework.beans.factory.annotation.Autowired
    private com.duozhi.outbound.workspace.service.KnowledgeDrafts knowledgeDrafts;

    @org.springframework.beans.factory.annotation.Autowired
    private com.duozhi.outbound.workspace.service.KnowledgeDirectories knowledgeDirectories;

    public WorkspaceController(
            WorkspaceDocuments docs,
            Store store,
            Templates templates,
            ProductOperations product,
            ConversationPolicy policies,
            HumanRouting routing,
            AiRuntime runtime) {
        this.docs = docs;
        this.store = store;
        this.templates = templates;
        this.product = product;
        this.policies = policies;
        this.routing = routing;
        this.runtime = runtime;
    }

    private Identity actor(HttpServletRequest r) {
        var i = Identity.from(r);
        i.admin("product:write");
        return i;
    }

    private Map<String, Object> draft(Map<String, Object> doc) {
        return map(doc.get("draft"));
    }

    private Map<String, Object> published(Map<String, Object> doc) {
        if (doc.get("published") == null || "disabled".equals(doc.get("state")))
            throw new ApiError(409, "配置尚未发布或已停用");
        return map(doc.get("published"));
    }

    @AdminPermission(value = "product:write", operator = true)
    @GetMapping("/scripts")
    public Object scripts(
            @RequestParam(defaultValue = "")
            String keyword,
            @RequestParam(defaultValue = "1")
            int page,
            @RequestParam(defaultValue = "50")
            int size,
            @RequestParam(defaultValue = "false")
            boolean publishedOnly,
            HttpServletRequest r) {
        var i = Identity.from(r);
        if (!i.isOperator()) i.admin("product:write");
        int offset = com.duozhi.outbound.common.web.Contract.page(page, size);
        String search = "%" + keyword + "%";
        String scope =
                " FROM scripttemplate t LEFT JOIN platform_document sd ON sd.tenant_id=t.tenant_id AND sd.kind='script' AND sd.object_id=CAST(t.id AS CHAR) WHERE t.tenant_id=? AND COALESCE(sd.state,'draft')<>'archived' AND (COALESCE(sd.name,t.name) LIKE ? OR t.category LIKE ?)";
        if (publishedOnly)
            scope +=
                    " AND EXISTS (SELECT 1 FROM platform_document d WHERE d.tenant_id=t.tenant_id AND d.kind='script' AND d.object_id=CAST(t.id AS CHAR) AND d.published_json IS NOT NULL AND d.state<>'disabled')";
        var rows =
                docs.jdbc.queryForList(
                        "SELECT t.id"
                                + scope
                                + " ORDER BY t.updated_at DESC,t.id DESC LIMIT ? OFFSET ?",
                        i.tenant(),
                        search,
                        search,
                        size,
                        offset);
        long total =
                docs.jdbc.queryForObject(
                        "SELECT COUNT(*)" + scope, Long.class, i.tenant(), search, search);
        return JsonSupport.map(
                "rows",
                rows.stream().map(v -> script(v.get("id").toString(), r)).toList(),
                "total",
                total);
    }

    @AdminPermission(value = "product:write", operator = true)
    @GetMapping("/scripts/{id}")
    public Map<String, Object> script(@PathVariable
    String id, HttpServletRequest r) {
        var i = Identity.from(r);
        if (!i.isOperator()) i.admin("product:write");
        if (!id.matches("[1-9][0-9]{0,9}")) throw new ApiError(404, "话术不存在");
        var raw = store.require("scripttemplate", i.tenant(), Long.parseLong(id));
        var doc = docs.find(i.tenant(), "script", id);
        if (doc != null && "archived".equals(doc.get("state"))) throw new ApiError(404, "话术已删除");
        if (doc == null)
            doc =
                    JsonSupport.map(
                            "object_id",
                            id,
                            "kind",
                            "script",
                            "state",
                            "draft",
                            "revision",
                            0,
                            "published_version",
                            0,
                            "draft",
                            JsonSupport.map(
                                    "name",
                                    raw.get("name"),
                                    "category",
                                    raw.get("category"),
                                    "description",
                                    Objects.toString(raw.get("description"), ""),
                                    "content",
                                    raw.get("content"),
                                    "knowledge_library_ids",
                                    List.of(),
                                    "policy",
                                    policies.current(i.tenant(), null).get("policy")),
                            "published",
                            null);
        var result = new LinkedHashMap<>(doc);
        result.put("id", raw.get("id"));
        result.put("created_at", raw.get("created_at"));
        result.put("is_active", raw.get("is_active"));
        result.put(
                "references",
                docs.jdbc.queryForObject(
                        "SELECT COUNT(*) FROM campaign WHERE tenant_id=? AND script_template_id=? AND status<>'deleted'",
                        Long.class,
                        i.tenant(),
                        raw.get("id")));
        result.put("owner_name", ownerName(i.tenant(), draft(doc).get("owner_id")));
        result.putIfAbsent("updated_at", raw.get("updated_at"));
        return result;
    }

    @AdminPermission(value = "product:write", operator = true)
    @PostMapping("/scripts")
    public Object createScript(@RequestBody
    Map<String, Object> body, HttpServletRequest r) {
        var i = Identity.from(r);
        if (!i.isOperator()) i.admin("product:write");
        String name = text(body, "name", 160, true);
        return docs.tx.serialized(
                () -> {
                    var initial =
                            templates.create(
                                    JsonSupport.map(
                                            "name",
                                            name,
                                            "category",
                                            text(body, "category", 80, false).isEmpty()
                                                    ? "default"
                                                    : body.get("category"),
                                            "content",
                                            "请配置业务目标与对话步骤后发布。",
                                            "is_active",
                                            false),
                                    r);
                    String id = initial.get("id").toString();
                    var value = draft(script(id, r));
                    value.put("owner_id", i.userId());
                    value.put("content", "");
                    value.put("goal", text(body, "goal", 2000, false));
                    docs.save(i, "script", id, "", value, 0);
                    return script(id, r);
                });
    }

    private Object owner(Map<String, Object> value, long tenant) {
        Object id = value.get("owner_id");
        if (id == null) return null;
        if (!(id instanceof Number)) throw new ApiError(422, "负责人标识不正确");
        if (docs.one("SELECT id FROM sys_user WHERE tenant_id=? AND id=?", tenant, id) == null)
            throw new ApiError(422, "负责人不属于当前企业");
        return id;
    }

    private String ownerName(long tenant, Object id) {
        if (id == null) return "未指定";
        var user =
                docs.one("SELECT full_name FROM sys_user WHERE tenant_id=? AND id=?", tenant, id);
        return user == null ? "已移除账号" : user.get("full_name").toString();
    }

    @AdminPermission(value = "product:write", operator = true)
    @PostMapping("/scripts/{id}/archive")
    public Object archive(
            @PathVariable
            String id, @RequestBody
            Map<String, Object> body, HttpServletRequest r) {
        var i = Identity.from(r);
        if (!i.isOperator()) i.admin("product:write");
        return docs.tx.serialized(
                () -> {
                    docs.tx.platform();
                    docs.tx.tenant(i.tenant());
                    var doc = script(id, r);
                    if (number(doc.get("references")) > 0)
                        throw new ApiError(409, "话术仍有关联任务，请先解除引用");
                    int rev = number(body.get("expected_revision"));
                    if (rev != number(doc.get("revision"))) throw new ApiError(409, "话术已修改，请刷新");
                    if (rev == 0)
                        rev = number(docs.save(i, "script", id, "", draft(doc), 0).get("revision"));
                    templates.update(Long.parseLong(id), Map.of("is_active", false), r);
                    int changed =
                            docs.jdbc.update(
                                    "UPDATE platform_document SET state='archived',published_json=NULL,revision=revision+1,updated_at=?,updated_by=? WHERE tenant_id=? AND kind='script' AND object_id=? AND revision=?",
                                    Store.now(),
                                    i.userId(),
                                    i.tenant(),
                                    id,
                                    rev);
                    if (changed != 1) throw new ApiError(409, "话术已修改，请刷新");
                    docs.recordTransition(i, "script", id, "archived");
                    return Map.of("archived", true);
                });
    }

    @AdminPermission(value = "product:write", operator = true)
    @PostMapping("/scripts/{id}/copy")
    public Object copyScript(@PathVariable
    String id, HttpServletRequest r) {
        var i = Identity.from(r);
        if (!i.isOperator()) i.admin("product:write");
        return docs.tx.serialized(
                () -> {
                    var source = script(id, r);
                    var value = draft(source);
                    String name = value.get("name").toString();
                    name = name.substring(0, Math.min(150, name.length())) + "（副本）";
                    var created =
                            map(
                                    createScript(
                                            JsonSupport.map(
                                                    "name",
                                                    name,
                                                    "category",
                                                    value.get("category")),
                                            r));
                    String newId = created.get("id").toString();
                    value.put("name", name);
                    value.put("owner_id", i.userId());
                    docs.save(i, "script", newId, "", value, number(created.get("revision")));
                    var flow =
                            docs.one(
                                    "SELECT * FROM scriptflowversion WHERE tenant_id=? AND script_template_id=? ORDER BY version DESC LIMIT 1",
                                    i.tenant(),
                                    Long.parseLong(id));
                    if (flow != null)
                        store.insert(
                                "scriptflowversion",
                                JsonSupport.map(
                                        "tenant_id",
                                        i.tenant(),
                                        "script_template_id",
                                        Long.parseLong(newId),
                                        "version",
                                        1,
                                        "name",
                                        name + "画布",
                                        "description",
                                        "复制自话术 #" + id,
                                        "graph_json",
                                        flow.get("graph_json")));
                    return script(newId, r);
                });
    }

    @org.springframework.beans.factory.annotation.Autowired
    private com.duozhi.outbound.workspace.service.ScriptMessageDefaults messageDefaults;

    private Map<String, Object> scriptDraft(Map<String, Object> body, long tenant) {
        var value = new LinkedHashMap<String, Object>();
        if (body.containsKey("sms_defaults"))
            value.put("sms_defaults", messageDefaults.validate(tenant, body.get("sms_defaults")));
        value.put("owner_id", owner(body, tenant));
        for (String key :
                List.of(
                        "name",
                        "category",
                        "description",
                        "content",
                        "identity",
                        "goal",
                        "steps",
                        "opening",
                        "ending",
                        "limits"))
            value.put(
                    key, text(body, key, key.equals("content") ? 12000 : 4000, key.equals("name")));
        if (value.get("category").toString().isEmpty()) value.put("category", "default");
        var ids = body.getOrDefault("knowledge_library_ids", List.of());
        if (!(ids instanceof List<?> list) || list.size() > 20)
            throw new ApiError(422, "最多关联 20 个知识库");
        var libraries = new ArrayList<String>();
        for (Object id : (List<?>) ids) {
            String key = id.toString();
            docs.require(tenant, "library", key);
            if (!libraries.contains(key)) libraries.add(key);
        }
        value.put("knowledge_library_ids", libraries);
        value.put(
                "import_fields",
                com.duozhi.outbound.workspace.service.ContactVariables.normalize(
                        body.getOrDefault("import_fields", List.of())));
        value.put(
                "policy",
                product.normalize(
                        map(
                                body.getOrDefault(
                                        "policy", policies.current(tenant, null).get("policy")))));
        value.put(
                "slot_rules",
                com.duozhi.outbound.workspace.service.SlotRules.normalize(
                        body.getOrDefault("slot_rules", List.of()),
                        (List<?>) map(value.get("policy")).getOrDefault("slots", List.of())));
        String unknown = Objects.toString(body.get("unknown_strategy"), "clarify");
        if (!Set.of("clarify", "handoff", "end").contains(unknown))
            throw new ApiError(422, "未命中处理方式无效");
        value.put("unknown_strategy", unknown);
        String prompt = text(body, "unknown_prompt", 500, false);
        value.put("unknown_prompt", prompt.isBlank() ? "这个问题需要进一步确认，我先记录下来，请不要以未核实的信息办理。" : prompt);
        return value;
    }

    @AdminPermission(value = "product:write", operator = true)
    @PutMapping("/scripts/{id}/draft")
    public Object saveScript(
            @PathVariable
            String id, @RequestBody
            Map<String, Object> body, HttpServletRequest r) {
        var i = Identity.from(r);
        if (!i.isOperator()) i.admin("product:write");
        script(id, r);
        docs.save(
                i,
                "script",
                id,
                "",
                scriptDraft(map(body.get("draft")), i.tenant()),
                number(body.get("expected_revision")));
        return script(id, r);
    }

    String compiled(Map<String, Object> value) {
        var lines = new ArrayList<String>();
        for (String key :
                List.of("identity", "goal", "steps", "opening", "limits", "ending", "content")) {
            String content = Objects.toString(value.get(key), "").trim();
            if (!content.isEmpty())
                lines.add(
                        Map.of(
                                                "identity",
                                                "身份",
                                                "goal",
                                                "业务目标",
                                                "steps",
                                                "沟通步骤",
                                                "opening",
                                                "开场",
                                                "limits",
                                                "回答边界",
                                                "ending",
                                                "结束",
                                                "content",
                                                "话术内容")
                                        .get(key)
                                + "："
                                + content);
        }
        String text = String.join("\n", lines);
        if (text.length() > 12000) throw new ApiError(422, "话术合计不得超过 12000 字符");
        return text;
    }

    List<Map<String, Object>> scriptKnowledge(long tenant, Map<String, Object> value) {
        var items = new ArrayList<Map<String, Object>>();
        for (Object id : (List<?>) value.getOrDefault("knowledge_library_ids", List.of())) {
            var lib = published(docs.require(tenant, "library", id.toString()));
            for (Object item : (List<?>) lib.getOrDefault("items", List.of())) items.add(map(item));
        }
        if (items.size() > 500) throw new ApiError(422, "本话术最多引用 500 条发布知识");
        return items;
    }

    @AdminPermission(value = "product:write", operator = true)
    @GetMapping("/scripts/{id}/publish-check")
    public Object publishCheck(@PathVariable
    String id, HttpServletRequest r) {
        var i = Identity.from(r);
        if (!i.isOperator()) i.admin("product:write");
        var doc = docs.require(i.tenant(), "script", id);
        var value = draft(doc);
        var errors = new ArrayList<String>();
        var warnings = new ArrayList<String>();
        try {
            value = scriptDraft(value, i.tenant());
            if (compiled(value).isBlank()) errors.add("话术内容为空");
            scriptKnowledge(i.tenant(), value);
            routing.validate(i.tenant(), map(value.get("policy")));
        } catch (ApiError e) {
            errors.add(e.getMessage());
        }
        if (Objects.toString(value.get("goal"), "").isBlank()) warnings.add("未单独说明业务目标");
        if (value.get("owner_id") == null) warnings.add("未指定负责人");
        var previous =
                doc.get("published") == null ? Map.<String, Object>of() : map(doc.get("published"));
        var changes = new ArrayList<Map<String, Object>>();
        for (String key :
                List.of(
                        "name",
                        "category",
                        "description",
                        "identity",
                        "goal",
                        "opening",
                        "steps",
                        "limits",
                        "ending",
                        "content",
                        "policy",
                        "knowledge_library_ids",
                        "import_fields",
                        "slot_rules",
                        "unknown_strategy",
                        "unknown_prompt"))
            if (!Objects.equals(previous.get(key), value.get(key)))
                changes.add(
                        JsonSupport.map(
                                "field",
                                key,
                                "before",
                                previous.get(key),
                                "after",
                                value.get(key)));
        return JsonSupport.map(
                "revision",
                doc.get("revision"),
                "ready",
                errors.isEmpty(),
                "errors",
                errors,
                "warnings",
                warnings,
                "changes",
                changes,
                "references",
                docs.jdbc.queryForList(
                        "SELECT id,name,status FROM campaign WHERE tenant_id=? AND script_template_id=? AND status<>'deleted' ORDER BY id DESC LIMIT 200",
                        i.tenant(),
                        id),
                "version_note",
                "发布仅供新建或重新保存的任务选用；运行中的任务使用已冻结版本");
    }

    @AdminPermission(value = "product:write", operator = true)
    @PostMapping("/scripts/{id}/publish")
    public Object publishScript(
            @PathVariable
            String id, @RequestBody
            Map<String, Object> body, HttpServletRequest r) {
        var i = Identity.from(r);
        if (!i.isOperator()) i.admin("product:write");
        return docs.tx.serialized(
                () -> {
                    var doc = docs.require(i.tenant(), "script", id);
                    var value = scriptDraft(draft(doc), i.tenant());
                    String content = compiled(value);
                    if (content.isBlank()) throw new ApiError(422, "请先配置业务目标或话术内容");
                    var policy = map(value.get("policy"));
                    routing.validate(i.tenant(), policy);
                    value.put("compiled_content", content);
                    value.put("knowledge", scriptKnowledge(i.tenant(), value));
                    docs.publish(i, "script", id, number(body.get("expected_revision")), value);
                    templates.update(
                            Long.parseLong(id),
                            JsonSupport.map(
                                    "name",
                                    value.get("name"),
                                    "category",
                                    value.get("category"),
                                    "content",
                                    content,
                                    "description",
                                    value.get("description"),
                                    "is_active",
                                    true),
                            r);
                    return script(id, r);
                });
    }

    @AdminPermission(value = "product:write", operator = true)
    @PostMapping("/scripts/{id}/probe")
    public Object probeScript(
            @PathVariable
            String id, @RequestBody
            Map<String, Object> body, HttpServletRequest r) {
        var i = Identity.from(r);
        if (!i.isOperator()) i.admin("product:write");
        var doc = script(id, r);
        var value =
                body.get("draft") instanceof Map<?, ?>
                        ? scriptDraft(map(body.get("draft")), i.tenant())
                        : draft(doc);
        String query = text(body, "query", 2000, true);
        var knowledge = scriptKnowledge(i.tenant(), value);
        return JsonSupport.map(
                "scope",
                "本地意图规则与已发布知识检索；未调用模型或执行业务动作",
                "intent",
                com.duozhi.outbound.ai.service.ScriptIntents.classify(
                        query, map(value.get("policy"))),
                "knowledge",
                runtime.knowledge(
                        JsonSupport.map("tenant_id", i.tenant()),
                        JsonSupport.map("_knowledge", knowledge),
                        query),
                "slots",
                map(value.get("policy")).get("slots"));
    }

    @AdminPermission(value = "product:write", operator = true)
    @PostMapping("/scripts/{id}/rule-preview")
    public Object previewOutcome(
            @PathVariable
            String id, @RequestBody
            Map<String, Object> body, HttpServletRequest r) {
        var i = Identity.from(r);
        if (!i.isOperator()) i.admin("product:write");
        var doc = script(id, r);
        var value =
                body.get("draft") instanceof Map<?, ?>
                        ? scriptDraft(map(body.get("draft")), i.tenant())
                        : draft(doc);
        var policy = map(value.get("policy"));
        if (!(body.get("turns") instanceof List<?> turns) || turns.size() > 50)
            throw new ApiError(422, "最多试算50轮");
        var state = new LinkedHashMap<String, Object>();
        var knowledge = scriptKnowledge(i.tenant(), value);
        var trace = new ArrayList<Map<String, Object>>();
        for (Object turn : turns) {
            if (!(turn instanceof String text) || text.length() > 2000)
                throw new ApiError(422, "单轮文本最多2000字");
            String intent = com.duozhi.outbound.ai.service.ScriptIntents.classify(text, policy);
            boolean hit =
                    "question".equals(intent)
                            && !runtime.knowledge(
                                            JsonSupport.map("tenant_id", i.tenant()),
                                            JsonSupport.map("_knowledge", knowledge),
                                            text)
                                    .isEmpty();
            com.duozhi.outbound.ai.service.OutcomeRules.turn(
                    policy, state, intent, hit, "machine_candidate".equals(intent));
            trace.add(
                    JsonSupport.map(
                            "intent",
                            intent,
                            "tags",
                            state.getOrDefault("rule_tags", List.of()),
                            "once",
                            state.getOrDefault("rule_events_done", List.of())));
        }
        return JsonSupport.map(
                "trace",
                trace,
                "result",
                com.duozhi.outbound.ai.service.OutcomeRules.finish(policy, state));
    }

    @GetMapping("/libraries")
    public Object libraries(HttpServletRequest r) {
        var i = actor(r);
        return JsonSupport.map(
                "rows",
                docs.list(i.tenant(), "library", null).stream()
                        .map(
                                row -> {
                                    row.put(
                                            "owner_name",
                                            ownerName(i.tenant(), draft(row).get("owner_id")));
                                    row.put(
                                            "entry_count",
                                            docs.jdbc.queryForObject(
                                                    "SELECT COUNT(*) FROM platform_document WHERE tenant_id=? AND kind='knowledge' AND parent_id=?",
                                                    Long.class,
                                                    i.tenant(),
                                                    row.get("object_id")));
                                    return row;
                                })
                        .toList(),
                "legacy_count",
                docs.jdbc.queryForObject(
                        "SELECT COUNT(*) FROM knowledgeitem WHERE tenant_id=?",
                        Long.class,
                        i.tenant()));
    }

    @PostMapping("/libraries")
    public Object createLibrary(@RequestBody
    Map<String, Object> body, HttpServletRequest r) {
        var i = actor(r);
        String id = UUID.randomUUID().toString();
        return docs.save(
                i,
                "library",
                id,
                "",
                JsonSupport.map(
                        "name",
                        text(body, "name", 160, true),
                        "owner_id",
                        i.userId(),
                        "description",
                        text(body, "description", 2000, false)),
                0);
    }

    @PostMapping("/libraries/import-legacy")
    public Object importLegacy(HttpServletRequest r) {
        var i = actor(r);
        return docs.tx.serialized(
                () -> {
                    String id = "legacy-knowledge";
                    if (docs.find(i.tenant(), "library", id) != null) return library(id, r);
                    docs.save(
                            i,
                            "library",
                            id,
                            "",
                            JsonSupport.map(
                                    "name", "历史知识迁移", "description", "保留原始条目来源；核对后发布。原知识记录未删除。"),
                            0);
                    for (var row :
                            docs.jdbc.queryForList(
                                    "SELECT * FROM knowledgeitem WHERE tenant_id=? AND campaign_id IS NULL ORDER BY id LIMIT 500",
                                    i.tenant())) {
                        var value =
                                JsonSupport.<String, Object>map(
                                        "name",
                                        row.get("title"),
                                        "content",
                                        row.get("content"),
                                        "category",
                                        Objects.toString(row.get("category"), ""),
                                        "keywords",
                                        Objects.toString(row.get("keywords"), ""),
                                        "source",
                                        "历史知识 #" + row.get("id"),
                                        "enabled",
                                        !Boolean.FALSE.equals(row.get("is_active")),
                                        "valid_from",
                                        Objects.toString(row.get("valid_from"), ""),
                                        "valid_until",
                                        Objects.toString(row.get("valid_until"), ""));
                        docs.save(i, "knowledge", "legacy-" + row.get("id"), id, item(value), 0);
                    }
                    return library(id, r);
                });
    }

    @GetMapping("/libraries/{id}")
    public Object library(@PathVariable
    String id, HttpServletRequest r) {
        var i = actor(r);
        var doc = docs.require(i.tenant(), "library", id);
        var result = new LinkedHashMap<>(doc);
        var ordered = faqOrder.entries(i.tenant(), id);
        var measured =
                docs.jdbc.queryForList(
                        "SELECT target_id,COUNT(DISTINCT call_id) AS hits FROM script_reach WHERE tenant_id=? AND kind='knowledge' GROUP BY target_id",
                        i.tenant());
        var counts = new HashMap<Object, Object>();
        for (var m : measured) counts.put(m.get("target_id"), m.get("hits"));
        long total =
                docs.jdbc.queryForObject(
                        "SELECT COUNT(DISTINCT call_id) FROM script_reach WHERE tenant_id=? AND kind='sample'",
                        Long.class,
                        i.tenant());
        for (var e : ordered) {
            e.put("hit_calls", counts.getOrDefault(e.get("object_id"), 0));
            e.put("sample_calls", total);
        }
        result.put("items", ordered);
        result.put("categories", knowledgeDirectories.list(i.tenant(), id));
        return result;
    }

    @org.springframework.beans.factory.annotation.Autowired
    private com.duozhi.outbound.workspace.service.KnowledgeOrder faqOrder;

    @PostMapping("/libraries/{id}/batch")
    public Object batchFaq(
            @PathVariable
            String id, @RequestBody
            Map<String, Object> b, HttpServletRequest r) {
        return faqOrder.change(actor(r), id, b);
    }

    @PutMapping("/libraries/{id}")
    public Object saveLibrary(
            @PathVariable
            String id, @RequestBody
            Map<String, Object> body, HttpServletRequest r) {
        var i = actor(r);
        docs.require(i.tenant(), "library", id);
        return docs.save(
                i,
                "library",
                id,
                "",
                JsonSupport.map(
                        "name",
                        text(map(body.get("draft")), "name", 160, true),
                        "description",
                        text(map(body.get("draft")), "description", 2000, false),
                        "owner_id",
                        owner(map(body.get("draft")), i.tenant())),
                number(body.get("expected_revision")));
    }

    Map<String, Object> item(Map<String, Object> raw) {
        return knowledgeDrafts.normalize(raw);
    }

    @PutMapping("/libraries/{library}/items/{id}")
    public Object saveItem(
            @PathVariable
            String library,
            @PathVariable
            String id,
            @RequestBody
            Map<String, Object> body,
            HttpServletRequest r) {
        return knowledgeDrafts.save(
                actor(r),
                library,
                id,
                map(body.get("draft")),
                number(body.get("expected_revision")));
    }

    private static final List<String> FAQ_HEADERS =
            List.of("序号", "标题", "关键词", "回答内容", "类型", "相似问法", "分类");

    @GetMapping("/libraries/{library}/faq-file")
    public ResponseEntity<byte[]> exportFaq(@PathVariable
    String library, HttpServletRequest r)
            throws IOException {
        var i = actor(r);
        docs.require(i.tenant(), "library", library);
        var output = new StringWriter();
        output.write(0xfeff);
        try (var csv = new CSVPrinter(output, CSVFormat.DEFAULT)) {
            csv.printRecord(FAQ_HEADERS);
            for (var entry : faqOrder.entries(i.tenant(), library)) {
                var value = draft(entry);
                if ("document".equals(value.get("kind"))) continue;
                for (String key :
                        List.of(
                                "display_code",
                                "name",
                                "keywords",
                                "content",
                                "answer_type",
                                "similar_questions",
                                "category"))
                    csv.print(
                            ContactFiles.safe(
                                    Objects.toString(
                                            value.get(key),
                                            key.equals("answer_type") ? "normal" : "")));
                csv.println();
            }
        }
        return ResponseEntity.ok()
                .header("Content-Type", "text/csv;charset=UTF-8")
                .header("Content-Disposition", "attachment; filename=faq.csv")
                .body(output.toString().getBytes(StandardCharsets.UTF_8));
    }

    @PostMapping(value = "/libraries/{library}/faq-file", consumes = "multipart/form-data")
    public Object importFaq(
            @PathVariable
            String library,
            @RequestParam
            int expected_revision,
            @RequestPart
            MultipartFile file,
            HttpServletRequest r)
            throws IOException {
        var i = actor(r);
        if (file.getSize() > 2 * 1024 * 1024) throw new ApiError(422, "问答文件超过2MB");
        var items = new ArrayList<Map<String, Object>>();
        try {
            String text =
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .decode(ByteBuffer.wrap(file.getBytes()))
                            .toString();
            try (var csv =
                    CSVFormat.DEFAULT.parse(
                            new StringReader(
                                    text.startsWith("\ufeff") ? text.substring(1) : text))) {
                var it = csv.iterator();
                if (!it.hasNext() || !FAQ_HEADERS.equals(it.next().toList()))
                    throw new ApiError(422, "表头不符，请使用本库导出的CSV表头");
                while (it.hasNext()) {
                    var row = it.next();
                    if (items.size() == 500 || row.size() != FAQ_HEADERS.size())
                        throw new ApiError(422, "问答超过500条或列数不符");
                    items.add(
                            knowledgeDrafts.normalize(
                                    JsonSupport.map(
                                            "display_code",
                                            row.get(0),
                                            "name",
                                            row.get(1),
                                            "keywords",
                                            row.get(2),
                                            "content",
                                            row.get(3),
                                            "answer_type",
                                            row.get(4).isBlank() ? "normal" : row.get(4),
                                            "similar_questions",
                                            row.get(5),
                                            "category",
                                            row.get(6))));
                }
            }
        } catch (IOException | UncheckedIOException ex) {
            throw new ApiError(422, "请上传有效的UTF-8 CSV文件");
        }
        if (items.isEmpty()) throw new ApiError(422, "文件中没有问答");
        return docs.tx.serialized(
                () -> {
                    var lib = docs.require(i.tenant(), "library", library);
                    if (number(lib.get("revision")) != expected_revision)
                        throw new ApiError(409, "知识库已变更，请刷新核对后再导入");
                    if (docs.list(i.tenant(), "knowledge", library).size() + items.size() > 500)
                        throw new ApiError(422, "单库最多500条知识");
                    for (var item : items)
                        docs.save(i, "knowledge", UUID.randomUUID().toString(), library, item, 0);
                    docs.save(i, "library", library, "", draft(lib), expected_revision);
                    return Map.of("imported", items.size());
                });
    }

    List<Map<String, Object>> knowledgeItems(long tenant, String library, int version) {
        var items = new ArrayList<Map<String, Object>>();
        for (var entry : faqOrder.entries(tenant, library)) {
            var value = draft(entry);
            if (Boolean.FALSE.equals(value.get("enabled"))) continue;
            var normalized =
                    JsonSupport.<String, Object>map(
                            "id",
                            entry.get("object_id"),
                            "title",
                            value.get("name"),
                            "content",
                            value.get("content"),
                            "category",
                            value.get("category"),
                            "keywords",
                            value.get("keywords") + " " + value.get("similar_questions"),
                            "source",
                            value.get("source"),
                            "version",
                            version);
            for (String key :
                    List.of(
                            "valid_from",
                            "valid_until",
                            "kind",
                            "document_id",
                            "segment_index",
                            "display_code",
                            "answer_type"))
                if (!Objects.toString(value.get(key), "").isBlank())
                    normalized.put(key, value.get(key));
            items.add(normalized);
        }
        if (items.isEmpty()) throw new ApiError(422, "知识库至少需要一条启用知识");
        return items;
    }

    @PostMapping("/libraries/{id}/publish")
    public Object publishLibrary(
            @PathVariable
            String id, @RequestBody
            Map<String, Object> body, HttpServletRequest r) {
        var i = actor(r);
        return docs.tx.serialized(
                () -> {
                    var doc = docs.require(i.tenant(), "library", id);
                    var value = draft(doc);
                    value.put(
                            "categories",
                            docs.list(i.tenant(), "knowledge_category", id).stream()
                                    .map(c -> c.get("name"))
                                    .sorted()
                                    .toList());
                    value.put(
                            "items",
                            knowledgeItems(
                                    i.tenant(), id, number(doc.get("published_version")) + 1));
                    return docs.publish(
                            i, "library", id, number(body.get("expected_revision")), value);
                });
    }

    @PostMapping("/libraries/{id}/probe")
    public Object probeLibrary(
            @PathVariable
            String id, @RequestBody
            Map<String, Object> body, HttpServletRequest r) {
        var i = actor(r);
        var lib = docs.require(i.tenant(), "library", id);
        var items =
                Boolean.TRUE.equals(body.get("draft"))
                        ? knowledgeItems(i.tenant(), id, number(lib.get("published_version")) + 1)
                        : (List<?>) published(lib).get("items");
        return JsonSupport.map(
                "scope",
                Boolean.TRUE.equals(body.get("draft")) ? "草稿检索预览，未发布" : "已发布知识检索",
                "hits",
                runtime.knowledge(
                        JsonSupport.map("tenant_id", i.tenant()),
                        JsonSupport.map("_knowledge", items),
                        text(body, "query", 2000, true)));
    }

    @GetMapping("/scripts/{id}/history")
    @AdminPermission(value = "product:write", operator = true)
    public Object scriptHistory(@PathVariable
    String id, HttpServletRequest r) {
        var i = Identity.from(r);
        if (!i.isOperator()) i.admin("product:write");
        return docs.history(i.tenant(), "script", id);
    }

    @GetMapping("/{kind}/{id}/history")
    public Object history(
            @PathVariable
            String kind, @PathVariable
            String id, HttpServletRequest r) {
        var i = actor(r);
        return docs.history(i.tenant(), kind(kind), id);
    }

    @PostMapping("/{kind}/{id}/restore")
    public Object restore(
            @PathVariable
            String kind,
            @PathVariable
            String id,
            @RequestBody
            Map<String, Object> body,
            HttpServletRequest r) {
        var i = actor(r);
        String type = kind(kind);
        var doc = docs.require(i.tenant(), type, id);
        var value = docs.revision(i.tenant(), type, id, number(body.get("version")));
        value.remove("version");
        value.remove("knowledge");
        value.remove("compiled_content");
        if (type.equals("library"))
            return docs.tx.serialized(
                    () -> {
                        var current = docs.require(i.tenant(), type, id);
                        if (number(current.get("revision"))
                                != number(body.get("expected_revision")))
                            throw new ApiError(409, "配置已变化，请刷新");
                        for (var entry : docs.list(i.tenant(), "knowledge", id)) {
                            var disabled = draft(entry);
                            disabled.put("enabled", false);
                            docs.save(
                                    i,
                                    "knowledge",
                                    entry.get("object_id").toString(),
                                    id,
                                    disabled,
                                    number(entry.get("revision")));
                        }
                        for (Object raw : (List<?>) value.getOrDefault("items", List.of())) {
                            var entry = map(raw);
                            String itemId = entry.get("id").toString();
                            var old = docs.find(i.tenant(), "knowledge", itemId);
                            var restored =
                                    JsonSupport.<String, Object>map(
                                            "name",
                                            entry.get("title"),
                                            "content",
                                            entry.get("content"),
                                            "category",
                                            entry.get("category"),
                                            "keywords",
                                            entry.get("keywords"),
                                            "source",
                                            entry.get("source"),
                                            "valid_from",
                                            entry.get("valid_from"),
                                            "valid_until",
                                            entry.get("valid_until"),
                                            "enabled",
                                            true);
                            for (String field : List.of("kind", "document_id", "segment_index"))
                                if (entry.get(field) != null) restored.put(field, entry.get(field));
                            docs.save(
                                    i,
                                    "knowledge",
                                    itemId,
                                    id,
                                    item(restored),
                                    old == null ? 0 : number(old.get("revision")));
                        }
                        docs.jdbc.update(
                                "DELETE FROM platform_document WHERE tenant_id=? AND kind='knowledge_category' AND parent_id=?",
                                i.tenant(),
                                id);
                        for (Object path : (List<?>) value.getOrDefault("categories", List.of()))
                            docs.save(
                                    i,
                                    "knowledge_category",
                                    UUID.randomUUID().toString(),
                                    id,
                                    JsonSupport.map("name", path.toString()),
                                    0);
                        value.remove("items");
                        value.remove("categories");
                        return docs.save(i, type, id, "", value, number(current.get("revision")));
                    });
        return docs.save(
                i,
                type,
                id,
                Objects.toString(doc.get("parent_id"), ""),
                value,
                number(body.get("expected_revision")));
    }

    @PostMapping("/{kind}/{id}/state")
    public Object state(
            @PathVariable
            String kind,
            @PathVariable
            String id,
            @RequestBody
            Map<String, Object> body,
            HttpServletRequest r) {
        var i = actor(r);
        String type = kind(kind), state = text(body, "state", 24, true);
        if (!Set.of("review", "disabled", "draft").contains(state))
            throw new ApiError(422, "不支持的状态");
        return docs.tx.serialized(
                () -> {
                    var doc = docs.require(i.tenant(), type, id);
                    if (number(doc.get("revision")) != number(body.get("expected_revision")))
                        throw new ApiError(409, "配置已修改，请刷新");
                    docs.jdbc.update(
                            "UPDATE platform_document SET state=?,revision=revision+1,updated_at=? WHERE tenant_id=? AND kind=? AND object_id=?",
                            state,
                            Store.now(),
                            i.tenant(),
                            type,
                            id);
                    docs.recordTransition(i, type, id, state);
                    if (type.equals("script") && state.equals("disabled"))
                        templates.update(Long.parseLong(id), Map.of("is_active", false), r);
                    return docs.require(i.tenant(), type, id);
                });
    }

    private String kind(String value) {
        return switch (value) {
            case "scripts" -> "script";
            case "libraries" -> "library";
            default -> throw new ApiError(404, "模块不存在");
        };
    }
}
