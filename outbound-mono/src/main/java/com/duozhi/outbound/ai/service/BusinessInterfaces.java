package com.duozhi.outbound.ai.service;

import static com.duozhi.outbound.workspace.service.WorkspaceDocuments.*;

import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.common.support.JsonSupport;
import com.duozhi.outbound.job.service.Outbox;
import com.duozhi.outbound.messaging.service.PinnedWebhookTransport;
import com.duozhi.outbound.workspace.service.WorkspaceDocuments;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.stereotype.Service;

/** Node HTTP requests run in the integration outbox, outside the dial/conversation writer lock. */
@Service
public class BusinessInterfaces {
    private final WorkspaceDocuments docs;
    private final Outbox outbox;
    private final PinnedWebhookTransport transport;

    public BusinessInterfaces(
            WorkspaceDocuments docs, Outbox outbox, PinnedWebhookTransport transport) {
        this.docs = docs;
        this.outbox = outbox;
        this.transport = transport;
    }

    public static List<Map<String, Object>> normalize(Object raw) {
        if (!(raw instanceof List<?> list) || list.size() > 30)
            throw new ApiError(422, "最多配置30个接口");
        var result = new ArrayList<Map<String, Object>>();
        var ids = new HashSet<String>();
        for (var item : list) {
            var v = map(item);
            String id = text(v, "id", 64, true),
                    name = text(v, "name", 160, true),
                    url = text(v, "url", 2048, true);
            if (!id.matches("[A-Za-z0-9_-]+")
                    || !ids.add(id)
                    || !"POST".equals(v.getOrDefault("method", "POST"))
                    || !"application/json"
                            .equals(v.getOrDefault("content_type", "application/json")))
                throw new ApiError(422, "接口编号、方法或内容类型无效");
            try {
                PinnedWebhookTransport.validate(URI.create(url));
            } catch (Exception invalid) {
                throw new ApiError(422, "接口地址须为HTTPS，端口443");
            }
            var body = map(v.get("body"));
            if (body.size() > 50
                    || new JsonSupport(new com.fasterxml.jackson.databind.ObjectMapper())
                                    .json(body)
                                    .length()
                            > 12000) throw new ApiError(422, "接口内容过多");
            var bindings = map(v.get("response_variables"));
            if (bindings.size() > 30) throw new ApiError(422, "接口最多映射30个响应变量");
            for (var e : bindings.entrySet())
                if (!e.getKey().matches("[\\p{L}_][\\p{L}0-9_]{0,63}")
                        || !(e.getValue() instanceof String s)
                        || !s.matches("[A-Za-z0-9_.]{1,160}"))
                    throw new ApiError(422, "响应映射须使用变量名和JSON字段路径");
            result.add(
                    Map.of(
                            "id",
                            id,
                            "name",
                            name,
                            "url",
                            url,
                            "method",
                            "POST",
                            "content_type",
                            "application/json",
                            "body",
                            body,
                            "response_variables",
                            bindings));
        }
        return result;
    }

    public static Map<String, Object> definition(Map<String, Object> policy, String id) {
        for (var item : (List<?>) policy.getOrDefault("http_interfaces", List.of())) {
            var v = map(item);
            if (id.equals(v.get("id"))) return v;
        }
        return Map.of();
    }

    public boolean pending(
            Map<String, Object> call,
            Map<String, Object> state,
            Map<String, Object> data,
            Map<String, Object> node) {
        String id = Objects.toString(node.get("interface_id"), "");
        if (id.isEmpty()) return false;
        String nodeId = node.get("id").toString();
        var results = map(data.getOrDefault("interface_results", Map.of()));
        if (results.containsKey(nodeId)) return false;
        var policy = docs.json.object(state.get("policy_json").toString());
        var def = definition(policy, id);
        if (def.isEmpty()) throw new ApiError(409, "流程节点引用的接口不存在");
        String request =
                "flow-http:"
                        + JsonSupport.sha(
                                call.get("id") + ":" + call.get("attempts") + ":" + nodeId);
        var variables = map(data.getOrDefault("flow_variables", Map.of()));
        data.put("pending_interface", Map.of("id", request, "node", nodeId));
        docs.jdbc.update(
                "UPDATE conversationstate SET data_json=? WHERE tenant_id=? AND id=?",
                docs.json.json(data),
                call.get("tenant_id"),
                state.get("id"));
        state.put("data_json", docs.json.json(data));
        docs.jdbc.update(
                "UPDATE callsession SET flow_node_key=? WHERE tenant_id=? AND id=?",
                nodeId,
                call.get("tenant_id"),
                call.get("id"));
        call.put("flow_node_key", nodeId);
        outbox.enqueue(
                ((Number) call.get("tenant_id")).longValue(),
                "business_callback",
                call.get("id"),
                request,
                Map.of(
                        "interface_request",
                        request,
                        "call_id",
                        call.get("id"),
                        "attempt",
                        call.get("attempts"),
                        "node",
                        nodeId,
                        "definition",
                        def,
                        "variables",
                        variables));
        return true;
    }

    private Object render(Object value, Map<String, Object> variables) {
        if (value instanceof Map<?, ?> map) {
            var v = new LinkedHashMap<String, Object>();
            map.forEach((k, item) -> v.put(k.toString(), render(item, variables)));
            return v;
        }
        if (value instanceof List<?> list)
            return list.stream().map(v -> render(v, variables)).toList();
        if (value instanceof String s && s.matches("\\{[\\p{L}_][\\p{L}0-9_]{0,63}\\}"))
            return variables.getOrDefault(s.substring(1, s.length() - 1), "");
        return value;
    }

    public void execute(Map<String, Object> task, Map<String, Object> payload) {
        com.duozhi.outbound.job.worker.TaskWorker.permitted();
        long tenant = ((Number) task.get("tenant_id")).longValue();
        String callId = payload.get("call_id").toString();
        var initial =
                docs.one(
                        "SELECT c.status,s.data_json,s.policy_json FROM callsession c JOIN conversationstate s ON s.tenant_id=c.tenant_id AND s.call_id=c.id AND s.attempt=c.attempts WHERE c.tenant_id=? AND c.id=? AND c.attempts=?",
                        tenant,
                        callId,
                        payload.get("attempt"));
        if (initial == null
                || !"IN_AI".equals(initial.get("status"))
                || !payload.get("interface_request")
                        .equals(
                                map(docs.json
                                                .object(initial.get("data_json").toString())
                                                .getOrDefault("pending_interface", Map.of()))
                                        .get("id"))) return;
        var def = map(payload.get("definition"));
        var values = new LinkedHashMap<String, Object>();
        String error = "";
        try {
            com.duozhi.outbound.job.worker.TaskWorker.permitted();
            var reply =
                    transport.request(
                            URI.create(def.get("url").toString()),
                            Map.of("Idempotency-Key", payload.get("interface_request").toString()),
                            docs.json
                                    .json(render(def.get("body"), map(payload.get("variables"))))
                                    .getBytes(StandardCharsets.UTF_8));
            if (reply.status() < 200 || reply.status() >= 300)
                throw new ApiError(502, "接口状态 " + reply.status());
            Object response = docs.json.object(new String(reply.body(), StandardCharsets.UTF_8));
            for (var binding : map(def.get("response_variables")).entrySet()) {
                Object value = response;
                for (String part : binding.getValue().toString().split("\\."))
                    value = value instanceof Map<?, ?> m ? m.get(part) : null;
                if (value == null) continue;
                if (!(value instanceof String
                                || value instanceof Number
                                || value instanceof Boolean)
                        || value.toString().length() > 2000) throw new ApiError(502, "接口响应变量无效");
                values.put(
                        binding.getKey(),
                        FlowVariables.value(
                                docs.json.object(initial.get("policy_json").toString()),
                                binding.getKey(),
                                value));
            }
        } catch (Exception failed) {
            error = failed instanceof ApiError ? failed.getMessage() : "业务接口未返回有效JSON";
        }
        String failure = error;
        docs.tx.serialized(
                () -> {
                    com.duozhi.outbound.job.worker.TaskWorker.permitted();
                    var row =
                            docs.one(
                                    "SELECT c.status,c.last_transcript,s.id,s.data_json,s.policy_json FROM callsession c JOIN conversationstate s ON s.tenant_id=c.tenant_id AND s.call_id=c.id AND s.attempt=c.attempts WHERE c.tenant_id=? AND c.id=? AND c.attempts=? FOR UPDATE",
                                    tenant,
                                    callId,
                                    payload.get("attempt"));
                    if (row == null || !"IN_AI".equals(row.get("status"))) return null;
                    var data = docs.json.object(row.get("data_json").toString());
                    if (!payload.get("interface_request")
                            .equals(
                                    map(data.getOrDefault("pending_interface", Map.of()))
                                            .get("id"))) return null;
                    var vars =
                            new LinkedHashMap<>(map(data.getOrDefault("flow_variables", Map.of())));
                    if (failure.isEmpty()) vars.putAll(values);
                    data.put("flow_variables", vars);
                    var results =
                            new LinkedHashMap<>(
                                    map(data.getOrDefault("interface_results", Map.of())));
                    results.put(
                            payload.get("node").toString(),
                            Map.of("success", failure.isEmpty(), "error", failure));
                    data.put("interface_results", results);
                    data.remove("pending_interface");
                    data.remove("flow_turn");
                    if (!failure.isEmpty()) data.put("interface_error", failure);
                    docs.jdbc.update(
                            "UPDATE conversationstate SET data_json=? WHERE tenant_id=? AND id=?",
                            docs.json.json(data),
                            tenant,
                            row.get("id"));
                    outbox.enqueue(
                            tenant,
                            "ai_turn",
                            callId,
                            payload.get("interface_request") + ":resume",
                            Map.of(
                                    "call_id",
                                    callId,
                                    "attempt",
                                    payload.get("attempt"),
                                    "transcript",
                                    Objects.toString(row.get("last_transcript"), "")));
                    return null;
                });
    }
}
