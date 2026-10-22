package com.duozhi.outbound.call.controller;

import com.duozhi.outbound.auth.domain.Identity;
import com.duozhi.outbound.common.dao.Store;
import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.common.support.Ascii;
import com.duozhi.outbound.common.support.JsonSupport;
import com.duozhi.outbound.common.support.Values;
import com.duozhi.outbound.common.web.Contract;
import com.duozhi.outbound.dial.service.AsyncDialService;
import com.duozhi.outbound.dial.service.DialService;
import com.duozhi.outbound.operations.service.OperationsAccess;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/calls")
public class Calls {
    @Autowired
    private AsyncDialService async;
    @Autowired
    private JsonSupport json;
    private final Store store;
    private final Contract contract;
    private final DialService dial;
    private final OperationsAccess operations;

    public Calls(Store store, Contract contract, DialService dial, OperationsAccess operations) {
        this.store = store;
        this.contract = contract;
        this.dial = dial;
        this.operations = operations;
    }

    @PostMapping
    public Map<String, Object> create(
            @RequestBody
            Map<String, Object> body, HttpServletRequest request) {
        return contract.output(
                "CallSessionOut",
                dial.create(
                        Identity.from(request), contract.input("StartCallRequest", body, true)));
    }

    @PostMapping("/batch")
    public ResponseEntity<?> batch(
            HttpServletRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false)
            String key)
            throws IOException {
        var body = asyncBody(request);
        if (!(body.get("items") instanceof List<?> items) || body.size() != 1)
            throw new ApiError(422, "body must contain only an items array");
        return ResponseEntity.status(202)
                .body(async.submit(Identity.from(request), key, items, "batch"));
    }

    @PostMapping("/async")
    public ResponseEntity<?> async(
            HttpServletRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false)
            String key)
            throws IOException {
        return ResponseEntity.status(202)
                .body(
                        async.submit(
                                Identity.from(request),
                                key,
                                List.of(asyncBody(request)),
                                "single"));
    }

    @GetMapping("/batches/{id}")
    public Map<String, Object> batchStatus(@PathVariable
    UUID id, HttpServletRequest request) {
        return async.status(Identity.from(request), id);
    }

    private Map<String, Object> asyncBody(HttpServletRequest request) throws IOException {
        async.enabled();
        byte[] body = request.getInputStream().readNBytes(AsyncDialService.MAX_BYTES + 1);
        if (body.length > AsyncDialService.MAX_BYTES)
            throw new ApiError(413, "async call request exceeds 1 MiB");
        return json.object(new String(body, StandardCharsets.UTF_8));
    }

    @PostMapping("/{id}/retry")
    public Map<String, Object> retry(@PathVariable
    UUID id, HttpServletRequest request) {
        return contract.output("CallSessionOut", dial.retry(visible(Identity.from(request), id)));
    }

    @PostMapping("/{id}/hangup")
    public Map<String, Object> hangup(
            @PathVariable
            UUID id,
            @RequestParam(defaultValue = "hangup")
            String reason,
            HttpServletRequest request) {
        return contract.output(
                "CallSessionOut", dial.hangup(visible(Identity.from(request), id), reason));
    }

    private boolean restricted(Identity identity) {
        return identity.user() != null
                && "agent".equals(identity.user().get("role"))
                && !Boolean.TRUE.equals(identity.user().get("is_supervisor"));
    }

    private Map<String, Object> visible(Identity identity, UUID id) {
        Map<String, Object> call = store.require("callsession", identity.tenant(), id);
        operations.call(identity, id);
        return call;
    }

    @GetMapping
    public List<Map<String, Object>> list(
            HttpServletRequest request,
            @RequestParam(name = "status", required = false)
            String status,
            @RequestParam(required = false)
            Long campaign_id,
            @RequestParam(defaultValue = "1")
            int page,
            @RequestParam(defaultValue = "50")
            int size) {
        Identity identity = Identity.from(request);
        int offset = Contract.page(page, size);
        Object encoded = null;
        if (status != null && !status.isEmpty())
            try {
                encoded = store.encode("callsession", "status", status.transform(Ascii::lower));
            } catch (ApiError e) {
                throw new ApiError(400, "invalid status filter");
            }
        return store
                .query(
                        "callsession",
                        "searchCalls",
                        JsonSupport.map(
                                "tenantId",
                                identity.tenant(),
                                "agentId",
                                null,
                                "opsUser",
                                identity.user() != null && !operations.all(identity)
                                        ? identity.userId()
                                        : null,
                                "campaignId",
                                campaign_id,
                                "status",
                                encoded,
                                "limit",
                                size,
                                "offset",
                                offset))
                .stream()
                .map(row -> contract.output("CallSessionOut", row))
                .collect(Collectors.toList());
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable
    UUID id, HttpServletRequest request) {
        return contract.output("CallSessionOut", visible(Identity.from(request), id));
    }

    @GetMapping("/{id}/events")
    public List<Map<String, Object>> events(
            @PathVariable
            UUID id,
            HttpServletRequest request,
            @RequestParam(defaultValue = "1")
            int page,
            @RequestParam(defaultValue = "50")
            int size) {
        visible(Identity.from(request), id);
        return store
                .across(
                        "callevent",
                        "scopedCalls71",
                        Store.queryParameters(
                                -1,
                                store.encode("callevent", "call_session_id", id),
                                size,
                                Contract.page(page, size)))
                .stream()
                .map(row -> contract.output("CallEventOut", row))
                .collect(Collectors.toList());
    }

    @GetMapping("/{id}/webhook-events")
    public List<Map<String, Object>> webhookEvents(
            @PathVariable
            UUID id,
            HttpServletRequest request,
            @RequestParam(defaultValue = "1")
            int page,
            @RequestParam(defaultValue = "50")
            int size) {
        visible(Identity.from(request), id);
        return store
                .across(
                        "webhookeventingest",
                        "scopedCalls79",
                        Store.queryParameters(
                                -1,
                                store.encode("webhookeventingest", "call_session_id", id),
                                size,
                                Contract.page(page, size)))
                .stream()
                .map(row -> contract.output("WebhookEventIngestOut", row))
                .collect(Collectors.toList());
    }

    @GetMapping("/{id}/webhook-stats")
    public Map<String, Object> webhookStats(@PathVariable
    UUID id, HttpServletRequest request) {
        visible(Identity.from(request), id);
        Object boundId = store.encode("webhookeventingest", "call_session_id", id);
        List<Map<String, Object>> groups =
                store.projection(
                        "webhookeventingest",
                        "eventBuckets",
                        Values.map("tenantId", Identity.from(request).tenant(), "callId", boundId));
        Long duplicates =
                store.value(
                        "webhookeventingest",
                        "duplicateCount",
                        Values.map("tenantId", Identity.from(request).tenant(), "callId", boundId),
                        Long.class);
        long total =
                store.sumAcross(
                        "webhookeventingest", "scopedCalls89", Store.queryParameters(-1, boundId));
        return Values.map(
                "total",
                total,
                "unique",
                total,
                "duplicate_estimate",
                duplicates == null ? 0 : duplicates,
                "buckets",
                groups);
    }
}
