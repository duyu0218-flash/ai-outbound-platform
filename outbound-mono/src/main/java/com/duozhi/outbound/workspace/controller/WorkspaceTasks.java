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
    private final WorkspaceDocuments docs;
    private final Campaigns campaigns;
    private final Store store;

    @org.springframework.beans.factory.annotation.Autowired
    private com.duozhi.outbound.operations.controller.ProductOperations product;

    @org.springframework.beans.factory.annotation.Autowired
    private com.duozhi.outbound.agent.service.HumanRouting routing;

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
        return save(null, body, r);
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
        return docs.tx.serialized(
                () -> {
                    var data = new LinkedHashMap<>(body);
                    Object rawBatches = data.remove("batch_ids");
                    Object clientId = data.remove("client_id");
                    Object execution = data.remove("execution_policy");
                    String startAt = Objects.toString(data.remove("start_at"), ""),
                            endAt = Objects.toString(data.remove("end_at"), "");
                    com.duozhi.outbound.workspace.service.TaskSchedule.validate(startAt, endAt);
                    var selected = new LinkedHashSet<Long>();
                    var ids = data.getOrDefault("contact_ids", List.of());
                    if (!(ids instanceof List<?>)) throw new ApiError(422, "联系人格式不正确");
                    for (Object raw : (List<?>) ids) {
                        if (!(raw instanceof Number n)) throw new ApiError(422, "联系人标识不正确");
                        selected.add(n.longValue());
                    }
                    if (rawBatches != null && !(rawBatches instanceof List<?>))
                        throw new ApiError(422, "名单批次格式不正确");
                    var batches = rawBatches == null ? List.of() : (List<?>) rawBatches;
                    if (batches.size() > 50) throw new ApiError(422, "最多选择50个批次");
                    for (Object batch : batches) {
                        if (docs.one(
                                        "SELECT id FROM platform_contact_batch WHERE tenant_id=? AND id=?",
                                        tenant,
                                        batch)
                                == null) throw new ApiError(404, "名单批次不存在");
                        for (var row :
                                docs.jdbc.queryForList(
                                        "SELECT contact_id FROM platform_contact_batch_member WHERE tenant_id=? AND batch_id=?",
                                        tenant,
                                        batch))
                            selected.add(((Number) row.get("contact_id")).longValue());
                    }
                    if (selected.isEmpty() || selected.size() > 200000)
                        throw new ApiError(422, "任务名单须为1至200000个联系人");
                    Object scriptId = data.get("script_template_id");
                    if (scriptId == null) throw new ApiError(422, "请选择已发布话术");
                    var doc = docs.require(tenant, "script", scriptId.toString());
                    if (doc.get("published") == null || "disabled".equals(doc.get("state")))
                        throw new ApiError(409, "话术尚未发布或已停用");
                    var publication = map(doc.get("published"));
                    var executionPolicy = map(publication.get("policy"));
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
                    if ("ai_handoff".equals(data.get("mode"))
                            && ((List<?>) executionPolicy.get("handoff_team_ids")).isEmpty()
                            && ((List<?>) executionPolicy.get("handoff_agent_ids")).isEmpty())
                        throw new ApiError(422, "AI转人工模式必须配置接听团队或坐席");
                    executionPolicy.put("_task_start_at", startAt);
                    executionPolicy.put("_task_end_at", endAt);
                    data.put("script", publication.get("compiled_content"));
                    data.put("contact_ids", new ArrayList<>(selected));
                    Map<String, Object> row;
                    Object oldContext = r.getAttribute("workspaceTaskWrite");
                    try {
                        r.setAttribute("workspaceTaskWrite", true);
                        row =
                                id == null
                                        ? campaigns.create(data, r)
                                        : campaigns.update(id, data, r);
                    } finally {
                        if (oldContext == null) r.removeAttribute("workspaceTaskWrite");
                        else r.setAttribute("workspaceTaskWrite", oldContext);
                    }
                    Object taskId = row.get("id");
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
                                    "batch_ids",
                                    batches,
                                    "direct_contact_ids",
                                    ids,
                                    "contact_count",
                                    selected.size(),
                                    "saved_at",
                                    Store.now().toString());
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
                });
    }
}
