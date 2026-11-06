package com.duozhi.outbound.workspace.service;

import static com.duozhi.outbound.workspace.service.WorkspaceDocuments.*;

import com.duozhi.outbound.auth.domain.Identity;
import com.duozhi.outbound.common.config.Config;
import com.duozhi.outbound.common.dao.Store;
import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.common.runtime.ScheduledWork;
import com.duozhi.outbound.common.support.JsonSupport;
import com.duozhi.outbound.messaging.service.Messaging;
import java.time.Instant;
import java.util.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Draft and start are separate; chunks reuse the existing idempotent message ledger. */
@Service
public class WorkspaceMessages {
    @org.springframework.beans.factory.annotation.Autowired
    private ScheduledWork scheduledWork;

    private final WorkspaceDocuments docs;
    private final WorkspaceAudience audience;
    private final ContactVariables variables;
    private final Messaging messaging;
    private final Config config;

    public WorkspaceMessages(
            WorkspaceDocuments docs,
            WorkspaceAudience audience,
            ContactVariables variables,
            Messaging messaging,
            Config config) {
        this.docs = docs;
        this.audience = audience;
        this.variables = variables;
        this.messaging = messaging;
        this.config = config;
    }

    public List<Map<String, Object>> list(long tenant) {
        return docs.list(tenant, "sms_task", null);
    }

    public Map<String, Object> get(long tenant, String id) {
        var doc = docs.require(tenant, "sms_task", id);
        doc.put("job", job(tenant, id));
        doc.put("members", members(tenant, id, 1));
        return doc;
    }

    public Object members(long tenant, String id, int page) {
        docs.require(tenant, "sms_task", id);
        if (page < 1) throw new ApiError(422, "分页参数不正确");
        return JsonSupport.map(
                "total",
                docs.jdbc.queryForObject(
                        "SELECT COUNT(*) FROM platform_audience_member WHERE tenant_id=? AND snapshot_id=?",
                        Long.class,
                        tenant,
                        "sms:" + id),
                "rows",
                docs
                        .jdbc
                        .queryForList(
                                "SELECT contact_id,variables_json FROM platform_audience_member WHERE tenant_id=? AND snapshot_id=? ORDER BY contact_id LIMIT 50 OFFSET ?",
                                tenant,
                                "sms:" + id,
                                (long) (page - 1) * 50)
                        .stream()
                        .map(
                                row -> {
                                    row.put(
                                            "variables",
                                            docs.json.object(
                                                    row.remove("variables_json").toString()));
                                    return row;
                                })
                        .toList());
    }

    private Map<String, Object> job(long tenant, String id) {
        var row =
                docs.one(
                        "SELECT * FROM platform_message_job WHERE tenant_id=? AND id=?",
                        tenant,
                        id);
        if (row != null) {
            row.put("chunks", docs.json.object(row.remove("chunks_json").toString()).get("items"));
        }
        if (row != null) {
            var batchRows = new ArrayList<Map<String, Object>>();
            for (Object chunk :
                    ((List<?>) row.getOrDefault("chunks", List.of())).stream().limit(50).toList()) {
                var batch =
                        docs.one(
                                "SELECT id,state,results FROM msg_batch WHERE tenant_id=? AND id=?",
                                tenant,
                                chunk);
                if (batch != null) {
                    batch.put(
                            "results",
                            docs.json
                                    .object("{\"items\":" + batch.get("results") + "}")
                                    .get("items"));
                    batchRows.add(batch);
                }
            }
            row.put("batch_results", batchRows);
            row.put("batch_results_limit", 50);
        }
        return row;
    }

    private Map<String, Object> asset(long tenant, String id, String kind) {
        var row =
                docs.one(
                        "SELECT * FROM msg_resource WHERE tenant_id=? AND id=? AND kind=?",
                        tenant,
                        id,
                        kind);
        if (row == null) throw new ApiError(404, "短信资源不存在：" + kind);
        if (!"APPROVED".equals(row.get("state")))
            throw new ApiError(409, "短信资源尚未审核或已停用：" + row.get("name"));
        row.put("payload", docs.json.object(row.get("payload").toString()));
        return row;
    }

    public Object save(Identity actor, String id, Map<String, Object> body) {
        return docs.tx.serialized(
                () -> {
                    long tenant = actor.tenant();
                    if (job(tenant, id) != null) throw new ApiError(409, "任务已启动，不能修改冻结版本；请新建任务");
                    var input = map(body.get("draft"));
                    var draft = new LinkedHashMap<String, Object>();
                    draft.put("name", text(input, "name", 128, true));
                    for (String key :
                            List.of(
                                    "batch_ids",
                                    "contact_ids",
                                    "segment_snapshot_ids",
                                    "excluded_contact_ids"))
                        draft.put(key, input.getOrDefault(key, List.of()));
                    for (String key : List.of("product_id", "template_id", "sender_id"))
                        draft.put(key, text(input, key, 64, true));
                    var product = asset(tenant, draft.get("product_id").toString(), "product");
                    var template = asset(tenant, draft.get("template_id").toString(), "template");
                    asset(tenant, draft.get("sender_id").toString(), "sender");
                    String form = Objects.toString(map(product.get("payload")).get("form"), "");
                    if (!com.duozhi.outbound.messaging.service.MessageContent.FORMS.contains(form)
                            || !form.equals(map(template.get("payload")).get("form")))
                        throw new ApiError(422, "请选择同一消息类型的产品与模板");
                    draft.put("form", form);
                    long budget;
                    try {
                        budget =
                                new java.math.BigDecimal(input.get("budget").toString())
                                        .longValueExact();
                    } catch (Exception e) {
                        throw new ApiError(422, "预算须为整数微元");
                    }
                    if (budget < 1 || budget > 1_000_000_000_000L)
                        throw new ApiError(422, "预算超出范围");
                    draft.put("budget", budget);
                    String expires = text(input, "expires_at", 64, true),
                            scheduled = text(input, "scheduled_at", 64, false);
                    try {
                        if (!Instant.parse(expires).isAfter(Instant.now())
                                || Instant.parse(expires)
                                        .isAfter(Instant.now().plusSeconds(30L * 86400))
                                || !scheduled.isBlank()
                                        && !Instant.parse(scheduled)
                                                .isBefore(Instant.parse(expires)))
                            throw new IllegalArgumentException();
                    } catch (Exception e) {
                        throw new ApiError(422, "请填写未来到期时间，且到期晚于计划发送时间");
                    }
                    draft.put("expires_at", expires);
                    if (!scheduled.isBlank()) draft.put("scheduled_at", scheduled);
                    var selection = audience.resolve(tenant, draft);
                    if (selection.members().isEmpty()) throw new ApiError(422, "名单为空");
                    draft.put("recipient_count", selection.members().size());
                    draft.put(
                            "variable_schema",
                            variables.schema(tenant, "", draft.get("template_id").toString()));
                    draft.put("product_name", product.get("name"));
                    draft.put("template_name", template.get("name"));
                    draft.put(
                            "sender_name",
                            asset(tenant, draft.get("sender_id").toString(), "sender").get("name"));
                    docs.save(
                            actor,
                            "sms_task",
                            id,
                            "",
                            draft,
                            number(body.get("expected_revision")));
                    audience.freeze(tenant, "sms:" + id, selection);
                    return get(tenant, id);
                });
    }

    public Map<String, Object> preflight(long tenant, String id) {
        var doc = docs.require(tenant, "sms_task", id);
        var draft = map(doc.get("draft"));
        var errors = new ArrayList<String>();
        for (String key : List.of("product", "template", "sender"))
            try {
                asset(tenant, draft.get(key + "_id").toString(), key);
            } catch (ApiError e) {
                errors.add(e.getMessage());
            }
        try {
            if (!Instant.parse(draft.get("expires_at").toString()).isAfter(Instant.now()))
                errors.add("任务已过期");
        } catch (Exception e) {
            errors.add("到期时间不正确");
        }
        String templateBody = "";
        try {
            templateBody =
                    Objects.toString(
                            map(asset(tenant, draft.get("template_id").toString(), "template")
                                            .get("payload"))
                                    .get("body"),
                            "");
        } catch (ApiError ignored) {
        }
        int total = 0, dnc = 0, noConsent = 0, missing = 0;
        long estimated = 0;
        var schema = map(draft.get("variable_schema"));
        for (var row :
                docs.jdbc.queryForList(
                        "SELECT m.variables_json,c.phone,c.dnc,c.consent_state FROM platform_audience_member m JOIN contact c ON c.tenant_id=m.tenant_id AND c.id=m.contact_id WHERE m.tenant_id=? AND m.snapshot_id=?",
                        tenant,
                        "sms:" + id)) {
            total++;
            if (Boolean.TRUE.equals(row.get("dnc"))) dnc++;
            if (!"consented".equalsIgnoreCase(Objects.toString(row.get("consent_state"), "")))
                noConsent++;
            var v = docs.json.object(row.get("variables_json").toString());
            if (!Objects.equals(v.get("phone"), row.get("phone"))) errors.add("名单客户号码已变更，请重新保存任务");
            var text = new HashMap<String, String>();
            v.forEach((k, value) -> text.put(k, Objects.toString(value, "")));
            try {
                variables.values(schema, text);
                estimated +=
                        messaging.estimate(
                                tenant,
                                draft.get("product_id").toString(),
                                draft.get("sender_id").toString(),
                                draft.get("template_id").toString(),
                                v);
                if (ContactVariables.render(templateBody, v).length() > 4000)
                    throw new ApiError(422, "变量替换后的短信超过4000字符");
            } catch (ApiError e) {
                missing++;
                if (!errors.contains(e.getMessage())) errors.add(e.getMessage());
            }
        }
        if (total != number(draft.get("recipient_count"))) errors.add("名单客户已变更，请重新保存任务");
        if (dnc > 0 || noConsent > 0) errors.add("名单含禁发或未确认授权客户，请明确排除后保存");
        if (missing > 0) errors.add("名单含缺少业务变量的客户");
        long unit = 0;
        try {
            unit =
                    ((Number)
                                    map(asset(tenant, draft.get("product_id").toString(), "product")
                                                    .get("payload"))
                                            .get("unit_price"))
                            .longValue();
        } catch (Exception ignored) {
        }

        var account = docs.one("SELECT available FROM msg_account WHERE tenant_id=?", tenant);
        long available = account == null ? 0 : ((Number) account.get("available")).longValue();
        if (estimated > available) errors.add("可用额度不足，需补充额度后启动");
        if (estimated > ((Number) draft.get("budget")).longValue()) errors.add("任务预算小于名单估算金额");
        return JsonSupport.map(
                "total",
                total,
                "dnc",
                dnc,
                "not_consented",
                noConsent,
                "missing_variables",
                missing,
                "errors",
                errors,
                "unit",
                "微元 CNY / 计费单位",
                "unit_price",
                unit,
                "available",
                available,
                "estimated",
                estimated,
                "ready",
                errors.isEmpty());
    }

    public Object start(Identity actor, String id, Map<String, Object> body) {
        return docs.tx.serialized(
                () -> {
                    var old = job(actor.tenant(), id);
                    if (old != null) return get(actor.tenant(), id);
                    if (!Boolean.TRUE.equals(body.get("confirmed")))
                        throw new ApiError(422, "请确认发送名单、模板、签名与预算");
                    var doc = docs.require(actor.tenant(), "sms_task", id);
                    if (number(doc.get("revision")) != number(body.get("expected_revision")))
                        throw new ApiError(409, "任务已变化，请刷新核对");
                    var check = preflight(actor.tenant(), id);
                    if (!Boolean.TRUE.equals(check.get("ready")))
                        throw new ApiError(409, "预检未通过：" + check.get("errors"));
                    var draft = map(doc.get("draft"));
                    var aud =
                            map(
                                    messaging.createResource(
                                            actor.tenant(),
                                            "audience",
                                            JsonSupport.map(
                                                    "name",
                                                    draft.get("name"),
                                                    "payload",
                                                    Map.of("workspace_snapshot", "sms:" + id))));
                    messaging.resourceState(actor.tenant(), aud.get("id").toString(), "APPROVED");
                    var payload = new LinkedHashMap<String, Object>();
                    for (String k :
                            List.of(
                                    "product_id",
                                    "template_id",
                                    "sender_id",
                                    "budget",
                                    "expires_at",
                                    "scheduled_at"))
                        if (draft.containsKey(k)) payload.put(k, draft.get(k));
                    payload.put("audience_id", aud.get("id"));
                    var campaign =
                            map(
                                    messaging.createResource(
                                            actor.tenant(),
                                            "campaign",
                                            JsonSupport.map(
                                                    "name",
                                                    draft.get("name"),
                                                    "payload",
                                                    payload)));
                    messaging.resourceState(
                            actor.tenant(), campaign.get("id").toString(), "APPROVED");
                    String key = text(body, "request_key", 64, true);
                    if (!key.matches("[A-Za-z0-9-]+")) throw new ApiError(422, "请求标识无效");
                    docs.jdbc.update(
                            "INSERT INTO platform_message_job(tenant_id,id,request_key,campaign_id,state,chunks_json,created_at,updated_at) VALUES(?,?,?,?,'queued',?,?,?)",
                            actor.tenant(),
                            id,
                            key,
                            campaign.get("id"),
                            "{\"items\":[]}",
                            Store.now(),
                            Store.now());
                    docs.publish(actor, "sms_task", id, number(doc.get("revision")), draft);
                    docs.recordTransition(actor, "sms_task", id, "started");
                    return get(actor.tenant(), id);
                });
    }

    @Scheduled(fixedDelay = 1500)
    public void scheduledTick() {
        scheduledWork.submit(ScheduledWork.Job.WORKSPACE_MESSAGES, this::tick);
    }

    public void tick() {
        if (!config.flag("MESSAGING_WORKERS_ENABLED", false)
                || !config.flag("MESSAGING_ENABLED", false)) return;
        try {
            processNext();
        } catch (RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(getClass())
                    .warn("SMS task worker: {}", e.getClass().getSimpleName());
        }
    }

    public boolean processNext() {
        var job =
                docs.one(
                        "SELECT tenant_id,id FROM platform_message_job WHERE state='queued' ORDER BY created_at LIMIT 1");
        if (job == null) return false;
        long tenant = ((Number) job.get("tenant_id")).longValue();
        String id = job.get("id").toString();
        try {
            return docs.tx.serialized(
                    () -> {
                        var current =
                                docs.one(
                                        "SELECT * FROM platform_message_job WHERE tenant_id=? AND id=? FOR UPDATE",
                                        tenant,
                                        id);
                        if (!"queued".equals(current.get("state"))) return false;
                        var draft = map(docs.require(tenant, "sms_task", id).get("published"));
                        var members =
                                docs.jdbc.queryForList(
                                        "SELECT contact_id,variables_json FROM platform_audience_member WHERE tenant_id=? AND snapshot_id=? AND contact_id>? ORDER BY contact_id LIMIT 100",
                                        tenant,
                                        "sms:" + id,
                                        current.get("after_contact"));
                        if (members.isEmpty()) {
                            docs.jdbc.update(
                                    "UPDATE platform_message_job SET state='submitted',updated_at=? WHERE tenant_id=? AND id=?",
                                    Store.now(),
                                    tenant,
                                    id);
                            return true;
                        }
                        var items = new ArrayList<Object>();
                        Object lastContact = current.get("after_contact");
                        int encodedSize = 0;
                        for (var member : members) {
                            var v = docs.json.object(member.get("variables_json").toString());
                            var values = new LinkedHashMap<String, Object>();
                            var payload =
                                    map(
                                            asset(
                                                            tenant,
                                                            draft.get("template_id").toString(),
                                                            "template")
                                                    .get("payload"));
                            values.putAll(
                                    com.duozhi.outbound.messaging.service.MessageContent
                                            .selectVariables(payload, v));
                            var body =
                                    JsonSupport.<String, Object>map(
                                            "service_product_id",
                                            draft.get("product_id"),
                                            "template_version_id",
                                            draft.get("template_id"),
                                            "sender_identity_id",
                                            draft.get("sender_id"),
                                            "recipient",
                                            Map.of("type", "phone", "value", v.get("phone")),
                                            "consent_reference",
                                            member.get("contact_id").toString(),
                                            "variables",
                                            values,
                                            "purpose",
                                            "marketing",
                                            "expires_at",
                                            draft.get("expires_at"),
                                            "marketing_campaign_id",
                                            current.get("campaign_id"),
                                            "channel_policy",
                                            Map.of(
                                                    "mode",
                                                    "fixed",
                                                    "allowed_forms",
                                                    List.of(draft.getOrDefault("form", "sms")),
                                                    "allow_fallback",
                                                    false));
                            if (draft.containsKey("scheduled_at"))
                                body.put("scheduled_at", draft.get("scheduled_at"));
                            var item =
                                    Map.of(
                                            "client_item_id",
                                            member.get("contact_id").toString(),
                                            "message",
                                            body);
                            int size = docs.json.json(item).length();
                            if (!items.isEmpty() && encodedSize + size > 480000) break;
                            items.add(item);
                            encodedSize += size;
                            lastContact = member.get("contact_id");
                        }
                        var batch =
                                map(
                                        messaging.batch(
                                                new Messaging.Principal(tenant, "console"),
                                                "sms-task:"
                                                        + id
                                                        + ":"
                                                        + current.get("after_contact"),
                                                Map.of("items", items)));
                        var chunks = map(docs.json.object(current.get("chunks_json").toString()));
                        var list = new ArrayList<Object>((List<?>) chunks.get("items"));
                        list.add(
                                batch.get("batch_id") != null
                                        ? batch.get("batch_id")
                                        : batch.get("id"));
                        chunks.put("items", list);
                        docs.jdbc.update(
                                "UPDATE platform_message_job SET after_contact=?,submitted_count=submitted_count+?,chunks_json=?,updated_at=? WHERE tenant_id=? AND id=?",
                                lastContact,
                                items.size(),
                                docs.json.json(chunks),
                                Store.now(),
                                tenant,
                                id);
                        return true;
                    });
        } catch (RuntimeException e) {
            String error = e instanceof ApiError ? e.getMessage() : "批次提交中断，可恢复；已提交批次不会重复发送";
            docs.jdbc.update(
                    "UPDATE platform_message_job SET state='failed',error=?,updated_at=? WHERE tenant_id=? AND id=? AND state='queued'",
                    error.substring(0, Math.min(2000, error.length())),
                    Store.now(),
                    tenant,
                    id);
            return true;
        }
    }

    public Object command(Identity actor, String id, String command) {
        return docs.tx.serialized(
                () -> {
                    var job = job(actor.tenant(), id);
                    if (job == null) throw new ApiError(404, "发送作业不存在");
                    if (command.equals("retry")) {
                        if (!"failed".equals(job.get("state")))
                            throw new ApiError(409, "只有失败的提交可恢复");
                        docs.jdbc.update(
                                "UPDATE platform_message_job SET state='queued',error=NULL,updated_at=? WHERE tenant_id=? AND id=?",
                                Store.now(),
                                actor.tenant(),
                                id);
                    } else if (command.equals("cancel")) {
                        docs.jdbc.update(
                                "UPDATE platform_message_job SET state='cancelled',updated_at=? WHERE tenant_id=? AND id=? AND state IN ('queued','failed')",
                                Store.now(),
                                actor.tenant(),
                                id);
                    } else throw new ApiError(404, "操作不存在");
                    docs.recordTransition(actor, "sms_task", id, command);
                    return get(actor.tenant(), id);
                });
    }
}
