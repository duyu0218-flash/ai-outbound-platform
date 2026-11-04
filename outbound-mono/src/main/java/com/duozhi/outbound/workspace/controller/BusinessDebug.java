package com.duozhi.outbound.workspace.controller;

import static com.duozhi.outbound.workspace.service.WorkspaceDocuments.*;

import com.duozhi.outbound.ai.service.*;
import com.duozhi.outbound.auth.domain.*;
import com.duozhi.outbound.common.dao.Store;
import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.common.support.JsonSupport;
import com.duozhi.outbound.script.controller.ScriptFlows;
import com.duozhi.outbound.workspace.service.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/workspace/debug-sessions")
@AdminPermission("product:write")
public class BusinessDebug {
    private final WorkspaceDocuments docs;
    private final AiRuntime runtime;
    private final ScriptFlows flows;
    private final KnowledgeGaps gaps;
    private final ContactVariables variables;

    public BusinessDebug(
            WorkspaceDocuments docs,
            AiRuntime runtime,
            ScriptFlows flows,
            KnowledgeGaps gaps,
            ContactVariables variables) {
        this.docs = docs;
        this.runtime = runtime;
        this.flows = flows;
        this.gaps = gaps;
        this.variables = variables;
    }

    private Identity actor(HttpServletRequest r) {
        var i = Identity.from(r);
        i.admin("product:write");
        return i;
    }

    public Map<String, Object> require(long tenant, String id) {
        var row =
                docs.one(
                        "SELECT * FROM platform_debug_session WHERE tenant_id=? AND id=?",
                        tenant,
                        id);
        if (row == null) throw new ApiError(404, "调试会话不存在");
        row.put("snapshot", docs.json.object(row.remove("snapshot_json").toString()));
        row.put("data", docs.json.object(row.remove("state_json").toString()));
        return row;
    }

    @GetMapping
    public Object list(HttpServletRequest r) {
        return docs.jdbc.queryForList(
                "SELECT id,name,script_id,script_version,mode,provider,state,revision,created_at,updated_at FROM platform_debug_session WHERE tenant_id=? ORDER BY created_at DESC LIMIT 100",
                actor(r).tenant());
    }

    @GetMapping("/{id}")
    public Object get(@PathVariable
    String id, HttpServletRequest r) {
        var row = require(actor(r).tenant(), id);
        row.put(
                "turns",
                docs
                        .jdbc
                        .queryForList(
                                "SELECT * FROM platform_debug_turn WHERE tenant_id=? AND session_id=? ORDER BY turn_number",
                                actor(r).tenant(),
                                id)
                        .stream()
                        .map(
                                v -> {
                                    v.put(
                                            "result",
                                            docs.json.object(v.remove("result_json").toString()));
                                    return v;
                                })
                        .toList());
        return row;
    }

    @PostMapping
    public Object create(@RequestBody
    Map<String, Object> body, HttpServletRequest r) {
        var i = actor(r);
        return docs.tx.serialized(
                () -> {
                    String key = text(body, "request_key", 64, true);
                    if (!key.matches("[A-Za-z0-9-]+")) throw new ApiError(422, "请求标识无效");
                    String script = text(body, "script_id", 64, true);
                    var doc = docs.require(i.tenant(), "script", script);
                    if (doc.get("published") == null || "disabled".equals(doc.get("state")))
                        throw new ApiError(409, "调试需要已发布话术");
                    var pub = new LinkedHashMap<>(map(doc.get("published")));
                    var customer = map(body.getOrDefault("variables", Map.of()));
                    if (docs.json.json(customer).length() > 20000)
                        throw new ApiError(422, "客户变量过多");
                    var raw = new HashMap<String, String>();
                    customer.forEach((k, v) -> raw.put(k, Objects.toString(v, "")));
                    variables.values(variables.schema(i.tenant(), script, ""), raw);
                    var fingerprint =
                            JsonSupport.sha(
                                    docs.json.canonical(
                                            JsonSupport.map(
                                                    "script_id",
                                                    script,
                                                    "variables",
                                                    customer,
                                                    "name",
                                                    text(body, "name", 160, true)),
                                            false));
                    var prior =
                            docs.one(
                                    "SELECT id,snapshot_json FROM platform_debug_session WHERE tenant_id=? AND request_key=?",
                                    i.tenant(),
                                    key);
                    if (prior != null) {
                        if (!fingerprint.equals(
                                docs.json
                                        .object(prior.get("snapshot_json").toString())
                                        .get("request_hash")))
                            throw new ApiError(409, "请求标识已用于其他调试输入");
                        return get(prior.get("id").toString(), r);
                    }
                    var flow =
                            docs.one(
                                    "SELECT id,version,graph_json FROM scriptflowversion WHERE tenant_id=? AND script_template_id=? AND status='published' ORDER BY version DESC LIMIT 1",
                                    i.tenant(),
                                    script);
                    pub.put(
                            "flow",
                            flow == null
                                    ? null
                                    : JsonSupport.map(
                                            "id",
                                            flow.get("id"),
                                            "version",
                                            flow.get("version"),
                                            "graph",
                                            docs.json.object(flow.get("graph_json").toString())));
                    pub.put("variables", customer);
                    pub.put("request_hash", fingerprint);
                    String id = UUID.randomUUID().toString();
                    docs.jdbc.update(
                            "INSERT INTO platform_debug_session(tenant_id,id,request_key,name,script_id,script_version,mode,provider,state,snapshot_json,state_json,created_by,created_at,updated_at) VALUES(?,?,?,?,?,?,'text','local-rules','active',?,?,?,?,?)",
                            i.tenant(),
                            id,
                            key,
                            text(body, "name", 160, true),
                            Long.parseLong(script),
                            number(pub.get("version")),
                            docs.json.json(pub),
                            docs.json.json(JsonSupport.map("variables", customer)),
                            i.userId(),
                            Store.now(),
                            Store.now());
                    docs.recordTransition(i, "debug", id, "created");
                    return get(id, r);
                });
    }

    public Map<String, Object> context(long tenant, String id, String query) {
        var session = require(tenant, id);
        var snapshot = map(session.get("snapshot"));
        var policy = map(snapshot.get("policy"));
        policy.put("_knowledge", snapshot.getOrDefault("knowledge", List.of()));
        return JsonSupport.map(
                "script",
                ContactVariables.render(
                        Objects.toString(snapshot.get("compiled_content"), ""),
                        map(snapshot.get("variables"))),
                "script_version",
                session.get("script_version"),
                "customer_variables",
                snapshot.get("variables"),
                "knowledge",
                runtime.knowledge(Map.of("tenant_id", tenant), policy, query),
                "trial_only",
                true);
    }

    @PostMapping("/{id}/turns")
    public Object turn(
            @PathVariable
            String id, @RequestBody
            Map<String, Object> body, HttpServletRequest r) {
        var i = actor(r);
        return docs.tx.serialized(
                () -> {
                    String key = text(body, "request_key", 64, true),
                            input = text(body, "text", 2000, true);
                    if (!key.matches("[A-Za-z0-9-]+")) throw new ApiError(422, "请求标识无效");
                    var session = require(i.tenant(), id);
                    var old =
                            docs.one(
                                    "SELECT input_text FROM platform_debug_turn WHERE tenant_id=? AND session_id=? AND id=?",
                                    i.tenant(),
                                    id,
                                    key);
                    if (old != null) {
                        if (!input.equals(old.get("input_text")))
                            throw new ApiError(409, "请求标识已用于其他输入");
                        return get(id, r);
                    }
                    if (!"active".equals(session.get("state"))) throw new ApiError(409, "调试会话已结束");
                    if (number(body.get("expected_revision")) != number(session.get("revision")))
                        throw new ApiError(409, "会话已变化，请刷新");
                    int count =
                            docs.jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM platform_debug_turn WHERE tenant_id=? AND session_id=?",
                                    Integer.class,
                                    i.tenant(),
                                    id);
                    if (count >= 100) throw new ApiError(409, "单会话最多100轮，请另建会话");
                    var snapshot = map(session.get("snapshot"));
                    var state = map(session.get("data"));
                    var values = map(state.getOrDefault("variables", snapshot.get("variables")));
                    var policy = map(snapshot.get("policy"));
                    String
                            intent =
                                    com.duozhi.outbound.ai.service.ScriptIntents.classify(
                                            input, policy),
                            action = "speak",
                            reply = "",
                            node = Objects.toString(state.get("node"), "opening");
                    var context = context(i.tenant(), id, input);
                    var hits = (List<?>) context.get("knowledge");
                    var trace = new ArrayList<String>();
                    if (Set.of("stop_contact", "rejected", "wrong_person", "end")
                            .contains(intent)) {
                        action = "hangup";
                        reply = Objects.toString(snapshot.get("ending"), "感谢，再见。");
                        trace.add("结束/拒绝规则");
                    } else if ("handoff".equals(intent)) {
                        action = "handoff";
                        reply = Objects.toString(policy.get("handoff_prompt"), "正在为您转接人工。");
                        trace.add("仅模拟转人工，不创建转接或线索");
                    } else if ("question".equals(intent) && !hits.isEmpty()) {
                        reply = Objects.toString(map(hits.getFirst()).get("content"), "");
                        trace.add("发布知识本地排序命中");
                    } else if ("question".equals(intent) && hits.isEmpty()) {
                        String strategy =
                                Objects.toString(snapshot.get("unknown_strategy"), "clarify");
                        reply =
                                Objects.toString(
                                        snapshot.get("unknown_prompt"), "这个问题需要进一步确认，我先记录下来。");
                        action =
                                strategy.equals("handoff")
                                        ? "handoff"
                                        : strategy.equals("end") ? "hangup" : "speak";
                        trace.add("使用发布版本的未命中处理：" + strategy);
                    } else if (snapshot.get("flow") != null) {
                        var flow = map(snapshot.get("flow"));
                        var result =
                                flows.simulate(
                                        map(flow.get("graph")),
                                        state.get("node"),
                                        input,
                                        false,
                                        values);
                        node =
                                Objects.toString(
                                        result.get("next_node_id"),
                                        Objects.toString(result.get("current_node_id"), ""));
                        values = map(result.get("variables"));
                        action = Objects.toString(result.get("action"), "wait");
                        reply = Objects.toString(result.get("prompt"), "");
                        trace.add("发布流程画布 v" + flow.get("version"));
                    } else {
                        var simulated =
                                DebugCollection.step(snapshot, state, values, input, intent, count);
                        values = map(simulated.get("variables"));
                        reply = Objects.toString(simulated.get("reply"), "");
                        action = simulated.get("action").toString();
                        node = simulated.get("node").toString();
                        for (Object event : (List<?>) simulated.get("trace"))
                            trace.add(event.toString());
                    }
                    if ("question".equals(intent) && hits.isEmpty()) {
                        gaps.capture(
                                i.tenant(),
                                "debug",
                                id,
                                null,
                                input,
                                Map.of(
                                        "reason",
                                        "published_knowledge_miss",
                                        "script_id",
                                        session.get("script_id"),
                                        "script_version",
                                        session.get("script_version"),
                                        "simulated",
                                        true));
                        trace.add("未命中问题已进入人工整理队列");
                    }
                    reply = ContactVariables.render(reply, values);
                    state.put("variables", values);
                    state.put("node", node);
                    var result =
                            JsonSupport.map(
                                    "intent",
                                    intent,
                                    "node",
                                    node,
                                    "knowledge",
                                    hits,
                                    "variables",
                                    values,
                                    "collected",
                                    state.getOrDefault("collected", Map.of()),
                                    "pending_slot",
                                    state.getOrDefault("pending_slot", Map.of()),
                                    "action",
                                    action,
                                    "reply",
                                    reply,
                                    "trace",
                                    trace,
                                    "execution",
                                    "local-rules; no provider, phone, SMS or lead writes");
                    docs.jdbc.update(
                            "INSERT INTO platform_debug_turn(tenant_id,session_id,id,turn_number,input_text,result_json,created_at) VALUES(?,?,?,?,?,?,?)",
                            i.tenant(),
                            id,
                            key,
                            count + 1,
                            input,
                            docs.json.json(result),
                            Store.now());
                    docs.jdbc.update(
                            "UPDATE platform_debug_session SET state_json=?,revision=revision+1,updated_at=? WHERE tenant_id=? AND id=?",
                            docs.json.json(state),
                            Store.now(),
                            i.tenant(),
                            id);
                    return get(id, r);
                });
    }

    @PostMapping("/{id}/end")
    public Object end(
            @PathVariable
            String id, @RequestBody
            Map<String, Object> body, HttpServletRequest r) {
        var i = actor(r);
        return docs.tx.serialized(
                () -> {
                    var row = require(i.tenant(), id);
                    if ("ended".equals(row.get("state"))) return get(id, r);
                    if (number(body.get("expected_revision")) != number(row.get("revision")))
                        throw new ApiError(409, "会话已变化");
                    docs.jdbc.update(
                            "UPDATE platform_debug_session SET state='ended',revision=revision+1,updated_at=? WHERE tenant_id=? AND id=?",
                            Store.now(),
                            i.tenant(),
                            id);
                    return get(id, r);
                });
    }
}
