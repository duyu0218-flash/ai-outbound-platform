package com.duozhi.outbound.workspace.controller;

import static com.duozhi.outbound.workspace.service.WorkspaceDocuments.*;

import com.duozhi.outbound.auth.domain.*;
import com.duozhi.outbound.campaign.controller.Campaigns;
import com.duozhi.outbound.common.dao.Store;
import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.common.support.JsonSupport;
import com.duozhi.outbound.workspace.service.WorkspaceDocuments;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/workspace/tasks")
@AdminPermission("campaigns:write")
public class WorkspaceTasks {
    @org.springframework.beans.factory.annotation.Autowired
    private com.duozhi.outbound.workspace.service.WorkspaceAudience audience;

    @org.springframework.beans.factory.annotation.Autowired
    private com.duozhi.outbound.workspace.service.ContactVariables variables;

    private final WorkspaceDocuments docs;
    private final Campaigns campaigns;
    private final Store store;

    @org.springframework.beans.factory.annotation.Autowired
    private com.duozhi.outbound.operations.controller.ProductOperations product;

    @org.springframework.beans.factory.annotation.Autowired
    private com.duozhi.outbound.agent.service.HumanRouting routing;

    @org.springframework.beans.factory.annotation.Autowired
    private com.duozhi.outbound.operations.service.TaskStrategies strategies;

    @org.springframework.beans.factory.annotation.Autowired
    private com.duozhi.outbound.system.service.Audit audit;

    private final com.duozhi.outbound.client.controller.ClientAdmin clients;

    public WorkspaceTasks(
            WorkspaceDocuments docs,
            Campaigns campaigns,
            Store store,
            com.duozhi.outbound.client.controller.ClientAdmin clients) {
        this.docs = docs;
        this.campaigns = campaigns;
        this.store = store;
        this.clients = clients;
    }

    private long tenant(HttpServletRequest r) {
        var i = Identity.from(r);
        i.admin("campaigns:write");
        return i.tenant();
    }

    @GetMapping
    public Object list(
            @RequestParam(defaultValue = "1")
            int page,
            @RequestParam(defaultValue = "50")
            int size,
            @RequestParam(defaultValue = "")
            String keyword,
            HttpServletRequest r) {
        int offset = com.duozhi.outbound.common.web.Contract.page(page, size);
        long tenant = tenant(r);
        String search = "%" + keyword + "%";
        return JsonSupport.map(
                "rows",
                docs.jdbc.queryForList(
                        "SELECT c.*,(SELECT o.name FROM client_campaign b JOIN client_org o ON o.id=b.client_id AND o.tenant_id=b.tenant_id WHERE b.tenant_id=c.tenant_id AND b.campaign_id=c.id) AS client_name,(SELECT COUNT(*) FROM campaigncontact cc WHERE cc.campaign_id=c.id) AS contact_count FROM campaign c WHERE c.tenant_id=? AND c.status<>'deleted' AND c.name LIKE ? ORDER BY c.id DESC LIMIT ? OFFSET ?",
                        tenant,
                        search,
                        size,
                        offset),
                "total",
                docs.jdbc.queryForObject(
                        "SELECT COUNT(*) FROM campaign WHERE tenant_id=? AND status<>'deleted' AND name LIKE ?",
                        Long.class,
                        tenant,
                        search));
    }

    @GetMapping("/{id}")
    public Object detail(@PathVariable
    long id, HttpServletRequest r) {
        long tenant = tenant(r);
        var row = store.require("campaign", tenant, id);
        if ("deleted".equals(row.get("status"))) throw new ApiError(404, "任务不存在");
        var result = new LinkedHashMap<>(row);
        result.put("snapshot", docs.campaignSnapshot(tenant, id));
        result.put(
                "contact_count",
                docs.jdbc.queryForObject(
                        "SELECT COUNT(*) FROM campaigncontact WHERE campaign_id=?",
                        Long.class,
                        id));
        result.put(
                "contacts",
                docs.jdbc.queryForList(
                        "SELECT c.id,c.phone,c.name,c.dnc,c.consent_state FROM contact c JOIN campaigncontact cc ON cc.contact_id=c.id WHERE c.tenant_id=? AND cc.campaign_id=? ORDER BY cc.contact_order LIMIT 50",
                        tenant,
                        id));
        result.put(
                "results",
                docs.jdbc.queryForList(
                        "SELECT status,COUNT(*) AS total FROM callsession WHERE tenant_id=? AND campaign_id=? GROUP BY status",
                        tenant,
                        id));
        return result;
    }

    @PostMapping
    public Object create(@RequestBody
    Map<String, Object> body, HttpServletRequest r) {
        String request = Objects.toString(body.get("request_key"), "");
        long tenant = tenant(r);
        if (!request.isBlank() && !request.matches("[A-Za-z0-9-]{1,64}"))
            throw new ApiError(422, "创建请求编号无效");
        String hash = JsonSupport.sha(docs.json.canonical(body, false));
        var old = request.isBlank() ? null : docs.find(tenant, "task_create_request", request);
        if (old != null) return replay(old, hash, r);
        // Expensive source resolution must never occupy the global dial-admission mutex.
        var selection = docs.tx.run(() -> audience.resolve(tenant, body));
        return docs.tx.serializedIdentity(
                "workspace-task-create:"
                        + tenant
                        + ":"
                        + (request.isBlank() ? UUID.randomUUID() : request),
                () -> {
                    var existing =
                            request.isBlank()
                                    ? null
                                    : docs.find(tenant, "task_create_request", request);
                    if (existing != null) return replay(existing, hash, r);
                    Object result = createOnce(body, r, selection);
                    if (request.isBlank()) return result;
                    var output = map(result);
                    boolean split = output.containsKey("tasks");
                    var tasks = split ? (List<?>) output.get("tasks") : List.of(output);
                    // The request-key lock and unique key protect this receipt. It commits with
                    // every child, so a timeout leaves neither a receipt nor partially ready tasks.
                    docs.jdbc.update(
                            "INSERT INTO platform_document(tenant_id,kind,object_id,name,parent_id,draft_json,updated_by,updated_at) VALUES(?,'task_create_request',?,'任务创建请求','',?,?,?)",
                            tenant,
                            request,
                            docs.json.json(
                                    Map.of(
                                            "name",
                                            "任务创建请求",
                                            "hash",
                                            hash,
                                            "split",
                                            split,
                                            "ids",
                                            tasks.stream()
                                                    .map(task -> map(task).get("id"))
                                                    .toList())),
                            Identity.from(r).userId(),
                            Store.now());
                    audit.record(
                            Identity.from(r),
                            "save_draft",
                            "workspace_task_create_request",
                            request,
                            "task creation committed");
                    return result;
                });
    }

    private Object replay(Map<String, Object> old, String hash, HttpServletRequest r) {
        var saved = map(old.get("draft"));
        if (!hash.equals(saved.get("hash"))) throw new ApiError(409, "同一创建请求包含不同内容");
        var tasks =
                ((List<?>) saved.get("ids"))
                        .stream().map(id -> detail(((Number) id).longValue(), r)).toList();
        return Boolean.TRUE.equals(saved.get("split")) ? Map.of("tasks", tasks) : tasks.getFirst();
    }

    private Object createOnce(
            Map<String, Object> body,
            HttpServletRequest r,
            com.duozhi.outbound.workspace.service.WorkspaceAudience.Selection selection) {
        int split =
                com.duozhi.outbound.operations.service.OutboundRules.integer(
                        body.getOrDefault("split_size", 0), 0, 200000);
        if (split > 0) {
            var ids = new ArrayList<>(selection.members().keySet());
            if (ids.isEmpty() || (ids.size() + split - 1) / split > 50)
                throw new ApiError(422, "拆分后任务数须为1至50个");
            var tasks = new ArrayList<Object>();
            for (int offset = 0; offset < ids.size(); offset += split) {
                var part = new LinkedHashMap<>(body);
                part.put(
                        "name",
                        Objects.toString(body.get("name"), "") + " " + (offset / split + 1));
                var include =
                        new ArrayList<>(ids.subList(offset, Math.min(offset + split, ids.size())));
                part.put("contact_ids", include);
                part.put("batch_ids", List.of());
                part.put("segment_snapshot_ids", List.of());
                part.put("excluded_contact_ids", List.of());
                part.put("_split_snapshot", true);
                tasks.add(save(null, part, r, selection.subset(include)));
            }
            return Map.of("tasks", tasks);
        }
        return save(null, body, r, selection);
    }

    @PutMapping("/{id}")
    public Object update(
            @PathVariable
            long id, @RequestBody
            Map<String, Object> body, HttpServletRequest r) {
        return save(id, body, r);
    }

    private Object save(Long id, Map<String, Object> body, HttpServletRequest r) {
        long tenant = tenant(r);
        return save(id, body, r, docs.tx.run(() -> audience.resolve(tenant, body)));
    }

    private Object save(
            Long id,
            Map<String, Object> body,
            HttpServletRequest r,
            com.duozhi.outbound.workspace.service.WorkspaceAudience.Selection selection) {
        long tenant = tenant(r);
        java.util.function.Supplier<Object> write =
                () -> {
                    var data = new LinkedHashMap<>(body);
                    data.remove("split_size");
                    data.remove("request_key");
                    data.remove("_split_snapshot");
                    data.remove("frozen_audience_id");
                    var taskOptions = strategies.options(tenant, data.remove("task_options"));
                    if (id != null) {
                        var previous = docs.campaignSnapshot(tenant, id);
                        if (previous != null
                                && Boolean.TRUE.equals(
                                        map(previous.getOrDefault("task_options", Map.of()))
                                                .get("mask_numbers")))
                            taskOptions.put("mask_numbers", true);
                    }
                    if (id == null) data.putIfAbsent("hangup_sms_enabled", false);
                    Object rawBatches = data.remove("batch_ids");
                    Object clientId = data.remove("client_id");
                    Object execution = data.remove("execution_policy");
                    String startAt = Objects.toString(data.remove("start_at"), ""),
                            endAt = Objects.toString(data.remove("end_at"), "");
                    com.duozhi.outbound.workspace.service.TaskSchedule.validate(startAt, endAt);
                    var selected = new LinkedHashSet<>(selection.members().keySet());
                    var assessment =
                            strategies.assessContacts(
                                    tenant, taskOptions.get("evaluation_rule_id"), selected);
                    if ("reject".equals(assessment.get("advice")))
                        throw new ApiError(409, "智能评估：不建议外呼，请调整号码来源或规则后重试");
                    var ids = data.getOrDefault("contact_ids", List.of());
                    var batches = rawBatches == null ? List.of() : rawBatches;
                    Object segmentIds = data.remove("segment_snapshot_ids"),
                            excludedIds = data.remove("excluded_contact_ids");
                    Object scriptId = data.get("script_template_id");
                    if (scriptId == null) throw new ApiError(422, "请选择已发布话术");
                    var doc = docs.require(tenant, "script", scriptId.toString());
                    if (doc.get("published") == null || "disabled".equals(doc.get("state")))
                        throw new ApiError(409, "话术尚未发布或已停用");
                    var publication = map(doc.get("published"));
                    var executionPolicy = map(publication.get("policy"));
                    if (data.get("script_flow_version_id") != null) {
                        var flow =
                                store.require(
                                        "scriptflowversion",
                                        tenant,
                                        data.get("script_flow_version_id"));
                        var graph = docs.json.object(flow.get("graph_json").toString());
                        for (var node : (List<?>) graph.getOrDefault("nodes", List.of())) {
                            String api = Objects.toString(map(node).get("interface_id"), "");
                            if (!api.isBlank()
                                    && com.duozhi.outbound.ai.service.BusinessInterfaces.definition(
                                                    executionPolicy, api)
                                            .isEmpty())
                                throw new ApiError(422, "流程引用的接口未包含在已发布话术中");
                        }
                    }
                    var bindings =
                            com.duozhi.outbound.ai.service.ScriptBindings.from(executionPolicy);
                    Object lineIds = data.remove("line_ids");
                    if (lineIds == null)
                        lineIds =
                                bindings.containsKey("line_id")
                                        ? List.of(bindings.get("line_id"))
                                        : List.of();
                    if (!(lineIds instanceof List<?> lines) || lines.size() > 100)
                        throw new ApiError(422, "线路选择格式不正确");
                    var lineSnapshot = new ArrayList<Map<String, Object>>();
                    for (Object line : (List<?>) lineIds) {
                        if (!(line instanceof Number n)) throw new ApiError(422, "线路标识不正确");
                        var l = store.require("telephonyline", tenant, n.longValue());
                        if (!Boolean.TRUE.equals(l.get("enabled")))
                            throw new ApiError(409, "所选线路已停用");
                        lineSnapshot.add(
                                JsonSupport.map(
                                        "id",
                                        l.get("id"),
                                        "name",
                                        l.get("name"),
                                        "caller_id",
                                        l.get("caller_id")));
                    }
                    String backend =
                            Objects.toString(
                                    data.remove("service_combo"),
                                    Objects.toString(bindings.get("service_combo"), "inherit"));
                    if (!backend.equals("inherit") && !backend.equals("legacy"))
                        try {
                            com.duozhi.outbound.voiceai.service.VoiceAiProfiles.profile(backend);
                        } catch (com.duozhi.outbound.integration.support.Support.Failure e) {
                            throw new ApiError(422, "语音服务组合不存在");
                        }
                    if (backend.equals("legacy")) data.put("voice_ai_pipeline", "legacy");
                    else if (!backend.equals("inherit")) data.put("voice_ai_pipeline", "inherit");
                    if (selected.isEmpty()) throw new ApiError(422, "排除及去重后没有可触达客户");
                    if (execution != null) {
                        var overrides = map(execution);
                        for (String field :
                                List.of(
                                        "timezone",
                                        "weekdays",
                                        "holidays",
                                        "start_hour",
                                        "end_hour",
                                        "handoff_team_ids",
                                        "handoff_agent_ids",
                                        "handoff_skills",
                                        "handoff_wait_seconds",
                                        "handoff_fallback",
                                        "handoff_auto_callback"))
                            if (overrides.containsKey(field))
                                executionPolicy.put(field, overrides.get(field));
                    }
                    executionPolicy = product.normalize(executionPolicy);
                    routing.validate(tenant, executionPolicy);
                    if (!com.duozhi.outbound.ai.service.FlowSettings.humanAllowed(
                                    com.duozhi.outbound.ai.service.FlowSettings.from(
                                            executionPolicy))
                            && Set.of("ai_handoff", "mixed_human_first")
                                    .contains(Objects.toString(data.get("mode"), "")))
                        throw new ApiError(422, "此话术已关闭人机协同，请使用纯 AI 任务");
                    if ("ai_handoff".equals(data.get("mode"))
                            && ((List<?>) executionPolicy.get("handoff_team_ids")).isEmpty()
                            && ((List<?>) executionPolicy.get("handoff_agent_ids")).isEmpty())
                        throw new ApiError(422, "AI转人工模式必须配置接听团队或坐席");
                    executionPolicy.put("_task_line_ids", lineIds);
                    executionPolicy.put("_task_service_combo", backend);
                    executionPolicy.put("_task_start_at", startAt);
                    executionPolicy.put("_task_end_at", endAt);
                    executionPolicy.put("_task_options", taskOptions);
                    if ("random".equals(taskOptions.get("call_order"))) {
                        var shuffled = new ArrayList<>(selected);
                        Collections.shuffle(shuffled, new java.security.SecureRandom());
                        selected.clear();
                        selected.addAll(shuffled);
                    }
                    data.put("script", publication.get("compiled_content"));
                    data.put("contact_ids", new ArrayList<>(selected));
                    Map<String, Object> row;
                    Object oldContext = r.getAttribute("workspaceTaskWrite");
                    try {
                        r.setAttribute("workspaceTaskWrite", true);
                        row =
                                id == null
                                        ? campaigns.createDraft(data, r)
                                        : campaigns.update(id, data, r);
                    } finally {
                        if (oldContext == null) r.removeAttribute("workspaceTaskWrite");
                        else r.setAttribute("workspaceTaskWrite", oldContext);
                    }
                    Object taskId = row.get("id");
                    docs.jdbc.update(
                            "UPDATE campaign SET created_by=? WHERE tenant_id=? AND id=?",
                            number(taskOptions.get("owner_id")) > 0
                                    ? taskOptions.get("owner_id")
                                    : Identity.from(r).userId(),
                            tenant,
                            taskId);
                    audience.freeze(tenant, "call:" + taskId, selection);
                    var runtimePolicy = new LinkedHashMap<>(executionPolicy);
                    runtimePolicy.put("_knowledge", publication.get("knowledge"));
                    store.insert(
                            "scenarioversion",
                            JsonSupport.map(
                                    "tenant_id",
                                    tenant,
                                    "campaign_id",
                                    taskId,
                                    "policy_json",
                                    docs.json.json(runtimePolicy),
                                    "published_by",
                                    Identity.from(r).userId()));
                    var snapshot =
                            JsonSupport.<String, Object>map(
                                    "script_id",
                                    scriptId,
                                    "script_version",
                                    publication.get("version"),
                                    "script",
                                    publication.get("compiled_content"),
                                    "policy",
                                    executionPolicy,
                                    "start_at",
                                    startAt,
                                    "end_at",
                                    endAt,
                                    "knowledge",
                                    publication.get("knowledge"),
                                    "slot_rules",
                                    publication.getOrDefault("slot_rules", List.of()),
                                    "unknown_strategy",
                                    publication.getOrDefault("unknown_strategy", "clarify"),
                                    "unknown_prompt",
                                    publication.getOrDefault("unknown_prompt", "这个问题需要进一步确认。"),
                                    "batch_ids",
                                    batches,
                                    "direct_contact_ids",
                                    ids,
                                    "segment_snapshot_ids",
                                    segmentIds == null ? List.of() : segmentIds,
                                    "excluded_contact_ids",
                                    excludedIds == null ? List.of() : excludedIds,
                                    "line_ids",
                                    lineIds,
                                    "lines",
                                    lineSnapshot,
                                    "service_combo",
                                    backend,
                                    "variable_schema",
                                    variables.schema(tenant, scriptId.toString(), ""),
                                    "contact_count",
                                    selected.size(),
                                    "saved_at",
                                    Store.now().toString());
                    if (publication.containsKey("sms_defaults"))
                        snapshot.put("sms_defaults", publication.get("sms_defaults"));
                    if (id != null) {
                        var previous = docs.campaignSnapshot(tenant, id);
                        if (previous != null && previous.containsKey("sms_rule"))
                            snapshot.put("sms_rule", previous.get("sms_rule"));
                    }
                    snapshot.put("hangup_sms_enabled", row.get("hangup_sms_enabled"));
                    var share = docs.find(tenant, "script_share", scriptId.toString());
                    if (share != null) {
                        var shared = new LinkedHashMap<>(map(share.get("draft")));
                        shared.put("revision", share.get("revision"));
                        snapshot.put("share_policy", shared);
                    }
                    snapshot.put("task_options", taskOptions);
                    snapshot.put("assessment", assessment);
                    if (Boolean.TRUE.equals(body.get("_split_snapshot"))
                            || body.get("frozen_audience_id") != null)
                        snapshot.put("frozen_audience_id", taskId);
                    docs.jdbc.update(
                            "INSERT INTO platform_campaign_snapshot(tenant_id,campaign_id,snapshot_json,created_at) VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE snapshot_json=VALUES(snapshot_json),created_at=VALUES(created_at)",
                            tenant,
                            taskId,
                            docs.json.json(snapshot),
                            Store.now());
                    if (body.containsKey("client_id"))
                        clients.setCampaignClient(
                                ((Number) taskId).intValue(),
                                JsonSupport.map("client_id", clientId),
                                r);
                    return detail(((Number) taskId).longValue(), r);
                };
        return id == null ? docs.tx.run(write) : docs.tx.serialized(write);
    }
}
