package com.duozhi.outbound.operations.security;

import com.duozhi.outbound.auth.domain.Identity;
import com.duozhi.outbound.operations.service.OperationsAccess;
import jakarta.servlet.http.*;
import java.util.*;
import org.springframework.core.MethodParameter;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.*;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.config.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

@RestControllerAdvice
public class OperationsPhoneMask implements ResponseBodyAdvice<Object> {
    private final OperationsAccess access;

    @org.springframework.beans.factory.annotation.Autowired
    private com.duozhi.outbound.workspace.service.WorkspaceDocuments docs;

    @org.springframework.beans.factory.annotation.Autowired
    private com.duozhi.outbound.operations.controller.SeatBehavior seatBehavior;

    OperationsPhoneMask(OperationsAccess access) {
        this.access = access;
    }

    public boolean supports(
            MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converter) {
        return true;
    }

    public Object beforeBodyWrite(
            Object body,
            MethodParameter type,
            MediaType media,
            Class<? extends HttpMessageConverter<?>> converter,
            ServerHttpRequest request,
            ServerHttpResponse response) {
        if (request instanceof ServletServerHttpRequest servlet
                && servlet.getServletRequest().getAttribute("identity") instanceof Identity i
                && i.user() != null) {
            Object out =
                    access.flag(i, "can_unmask")
                            ? maskTasks(body, i.tenant(), new HashMap<>())
                            : mask(body);
            String path = servlet.getServletRequest().getRequestURI();
            var match =
                    java.util.regex.Pattern.compile(
                                    "/api/v1/workspace/task-resources/tasks/([0-9]+)/members")
                            .matcher(path);
            if (match.matches() && hidden(i.tenant(), Long.parseLong(match.group(1))))
                out = mask(out);
            if (!Boolean.TRUE.equals(
                    seatBehavior.effective(i.tenant(), i.userId()).get("success_phone")))
                out = maskSuccessful(out);
            if (!"admin".equals(i.user().get("role"))
                    && (path.contains("/calls") || path.contains("/handoffs"))) {
                var callPath =
                        java.util.regex.Pattern.compile(
                                        "/api/v1/(?:ops/)?calls/([a-zA-Z0-9-]+)(?:/.*)?")
                                .matcher(path);
                if (callPath.matches() && dialogueHidden(i.tenant(), callPath.group(1), null))
                    out = scrubDialogue(out);
                else out = dialogue(out, i.tenant(), new HashMap<>());
            }
            return access.flag(i, "can_record") ? out : withoutRecording(out);
        }
        return body;
    }

    private boolean dialogueHidden(long tenant, Object call, Object campaign) {
        var state =
                call == null
                        ? null
                        : docs.one(
                                "SELECT s.policy_json FROM conversationstate s JOIN callsession c ON c.tenant_id=s.tenant_id AND c.id=s.call_id AND c.attempts=s.attempt WHERE c.tenant_id=? AND c.id=?",
                                tenant,
                                call);
        Map<String, Object> policy = Map.of();
        if (state != null) policy = docs.json.object(state.get("policy_json").toString());
        else {
            var snapshot = docs.campaignSnapshot(tenant, campaign);
            if (snapshot != null)
                policy =
                        com.duozhi.outbound.workspace.service.WorkspaceDocuments.map(
                                snapshot.getOrDefault("policy", Map.of()));
        }
        return Boolean.TRUE.equals(
                com.duozhi.outbound.ai.service.FlowSettings.from(policy)
                        .get("handoff_hide_dialogue"));
    }

    private Object dialogue(Object value, long tenant, Map<Long, Boolean> cache) {
        if (value instanceof Map<?, ?> source) {
            boolean hidden =
                    source.get("campaign_id") instanceof Number n
                            && cache.computeIfAbsent(
                                    n.longValue(), id -> dialogueHidden(tenant, null, id));
            if (!hidden && source.get("call_session_id") != null)
                hidden = dialogueHidden(tenant, source.get("call_session_id"), null);
            if (hidden) return scrubDialogue(value);
            var out = new LinkedHashMap<String, Object>();
            source.forEach((k, v) -> out.put(k.toString(), dialogue(v, tenant, cache)));
            return out;
        }
        if (value instanceof List<?> list)
            return list.stream().map(v -> dialogue(v, tenant, cache)).toList();
        return value;
    }

    public static Object scrubDialogue(Object value) {
        if (value instanceof Map<?, ?> source) {
            var out = new LinkedHashMap<String, Object>();
            source.forEach(
                    (k, v) ->
                            out.put(
                                    k.toString(),
                                    Set.of(
                                                            "transcript",
                                                            "normalized_transcript",
                                                            "last_transcript",
                                                            "summary",
                                                            "last_customer_utterance",
                                                            "structured_json",
                                                            "automatic_result_json",
                                                            "payload_json",
                                                            "payload",
                                                            "detail_json",
                                                            "data_json",
                                                            "evidence",
                                                            "slots")
                                                    .contains(k.toString())
                                            ? null
                                            : scrubDialogue(v)));
            return out;
        }
        if (value instanceof List<?> list)
            return list.stream().map(OperationsPhoneMask::scrubDialogue).toList();
        return value;
    }

    private Object maskSuccessful(Object value) {
        if (value instanceof Map<?, ?> source) {
            if ("completed".equals(source.get("stage"))) return mask(source);
            var out = new LinkedHashMap<String, Object>();
            source.forEach((k, v) -> out.put(k.toString(), maskSuccessful(v)));
            return out;
        }
        if (value instanceof List<?> list) return list.stream().map(this::maskSuccessful).toList();
        return value;
    }

    private boolean hidden(long tenant, long campaign) {
        var snapshot = docs.campaignSnapshot(tenant, campaign);
        return snapshot != null
                && Boolean.TRUE.equals(
                        com.duozhi.outbound.workspace.service.WorkspaceDocuments.map(
                                        snapshot.getOrDefault("task_options", Map.of()))
                                .get("mask_numbers"));
    }

    private Object maskTasks(Object value, long tenant, Map<Long, Boolean> cache) {
        if (value instanceof Map<?, ?> source) {
            boolean hide = false;
            if (source.get("campaign_id") instanceof Number n)
                hide = cache.computeIfAbsent(n.longValue(), id -> hidden(tenant, id));
            if (source.get("snapshot") instanceof Map<?, ?> snapshot)
                hide =
                        hide
                                || Boolean.TRUE.equals(
                                        com.duozhi.outbound.workspace.service.WorkspaceDocuments
                                                .map(
                                                        snapshot.containsKey("task_options")
                                                                ? snapshot.get("task_options")
                                                                : Map.of())
                                                .get("mask_numbers"));
            if (hide) return mask(source);
            var out = new LinkedHashMap<String, Object>();
            source.forEach((k, v) -> out.put(k.toString(), maskTasks(v, tenant, cache)));
            return out;
        }
        if (value instanceof List<?> list)
            return list.stream().map(v -> maskTasks(v, tenant, cache)).toList();
        return value;
    }

    static Object withoutRecording(Object value) {
        if (value instanceof Map<?, ?> source) {
            var out = new LinkedHashMap<String, Object>();
            source.forEach(
                    (k, v) ->
                            out.put(
                                    k.toString(),
                                    Set.of(
                                                            "recording_url",
                                                            "recording_urls",
                                                            "recording_storage_uris",
                                                            "storage_uri",
                                                            "provider_url")
                                                    .contains(k.toString())
                                            ? null
                                            : withoutRecording(v)));
            return out;
        }
        if (value instanceof List<?> list)
            return list.stream().map(OperationsPhoneMask::withoutRecording).toList();
        return value;
    }

    public static Object mask(Object value) {
        if (value instanceof Map<?, ?> source) {
            var out = new LinkedHashMap<String, Object>();
            source.forEach(
                    (k, v) ->
                            out.put(
                                    k.toString(),
                                    Set.of("phone", "contact_phone").contains(k.toString())
                                                    && v != null
                                            ? v.toString().replaceAll("(?<=.{3}).(?=.{4})", "*")
                                            : mask(v)));
            return out;
        }
        if (value instanceof List<?> list)
            return list.stream().map(OperationsPhoneMask::mask).toList();
        return value;
    }
}
