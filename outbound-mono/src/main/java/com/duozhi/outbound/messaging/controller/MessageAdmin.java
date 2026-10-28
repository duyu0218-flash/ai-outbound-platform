package com.duozhi.outbound.messaging.controller;

import com.duozhi.outbound.auth.domain.*;
import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.integration.support.Support;
import com.duozhi.outbound.messaging.service.Messaging;
import com.duozhi.outbound.system.service.Audit;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/messaging")
@AdminPermission("messaging:write")
public class MessageAdmin {
    private final Messaging service;
    private final Audit audit;
    private final com.duozhi.outbound.messaging.service.MessageWebhooks hooks;

    public MessageAdmin(
            Messaging service,
            Audit audit,
            com.duozhi.outbound.messaging.service.MessageWebhooks hooks) {
        this.service = service;
        this.audit = audit;
        this.hooks = hooks;
    }

    private Identity actor(HttpServletRequest r) {
        var a = Identity.from(r);
        a.requireUser();
        a.admin("messaging:write");
        return a;
    }

    private Messaging.Principal principal(HttpServletRequest r) {
        return new Messaging.Principal(actor(r).tenant(), "console");
    }

    private void log(Identity a, String action, Object id) {
        audit.record(a, action, "messaging", id, "消息控制台操作");
    }

    @GetMapping("/capabilities")
    public Object capabilities(HttpServletRequest r) {
        actor(r);
        return service.capabilities();
    }

    @GetMapping("/resources/{kind}")
    public Object resources(@PathVariable
    String kind, HttpServletRequest r) {
        return service.resources(actor(r).tenant(), kind);
    }

    @PostMapping("/resources/{kind}")
    public Object createResource(
            @PathVariable
            String kind,
            @RequestBody
            Map<String, Object> body,
            HttpServletRequest r) {
        var a = actor(r);
        var result = service.createResource(a.tenant(), kind, body);
        log(a, "create_" + kind, null);
        return result;
    }

    @PostMapping("/resources/{id}/state")
    public Object state(
            @PathVariable
            String id, @RequestBody
            Map<String, Object> body, HttpServletRequest r) {
        var a = actor(r);
        var result = service.resourceState(a.tenant(), id, Support.required(body, "state", 32));
        log(a, "resource_state", id);
        return result;
    }

    @GetMapping("/applications")
    public Object apps(HttpServletRequest r) {
        return service.applications(actor(r).tenant());
    }

    @PostMapping("/applications")
    public Object app(@RequestBody
    Map<String, Object> body, HttpServletRequest r) {
        var a = actor(r);
        var result = service.createApplication(a.tenant(), body);
        log(a, "application_create", null);
        return result;
    }

    @PostMapping("/applications/{id}/revoke")
    public Object revoke(@PathVariable
    String id, HttpServletRequest r) {
        var a = actor(r);
        var result = service.revokeApplication(a.tenant(), id);
        log(a, "application_revoke", id);
        return result;
    }

    @GetMapping("/balance")
    public Object balance(HttpServletRequest r) {
        return service.balance(actor(r).tenant());
    }

    @GetMapping("/ledger")
    public Object ledger(HttpServletRequest r) {
        return service.ledger(actor(r).tenant());
    }

    @PostMapping("/sandbox-credit")
    public Object credit(
            @RequestBody
            Map<String, Object> body,
            @RequestHeader("Idempotency-Key")
            String key,
            HttpServletRequest r) {
        var a = actor(r);
        if (key.length() > 128) throw new ApiError(422, "幂等键过长");
        var result = service.credit(a.tenant(), key, Support.integer(body, "amount", 0));
        log(a, "sandbox_credit", key);
        return result;
    }

    @GetMapping("/report")
    public Object report(HttpServletRequest r) {
        return service.report(actor(r).tenant());
    }

    @GetMapping("/interactions")
    public Object interactions(HttpServletRequest r) {
        return service.interactions(actor(r).tenant());
    }

    @PostMapping("/messages/{id}/interactions")
    public Object interaction(
            @PathVariable
            String id,
            @RequestHeader("Idempotency-Key")
            String key,
            @RequestBody
            Map<String, Object> body,
            HttpServletRequest r) {
        var a = actor(r);
        var result = service.interaction(principal(r), id, key, body);
        log(a, "message_interaction", id);
        return result;
    }

    @GetMapping("/call-intents")
    public Object callIntents(HttpServletRequest r) {
        return service.callIntents(actor(r).tenant());
    }

    @GetMapping("/messages")
    public Object messages(HttpServletRequest r) {
        return service.messages(actor(r).tenant());
    }

    @PostMapping("/messages")
    public Object send(
            @RequestBody
            Map<String, Object> body,
            @RequestHeader("Idempotency-Key")
            String key,
            HttpServletRequest r) {
        var a = actor(r);
        var result = service.create(new Messaging.Principal(a.tenant(), "console"), key, body);
        log(a, "message_create", result.get("message_id"));
        return result;
    }

    @GetMapping("/messages/{id}")
    public Object detail(@PathVariable
    String id, HttpServletRequest r) {
        return service.message(principal(r), id);
    }

    @PostMapping("/messages/{id}/cancel")
    public Object cancel(@PathVariable
    String id, HttpServletRequest r) {
        var a = actor(r);
        var result = service.cancel(principal(r), id);
        log(a, "message_cancel", id);
        return result;
    }

    @GetMapping("/messages/{id}/events")
    public Object events(@PathVariable
    String id, HttpServletRequest r) {
        return service.events(principal(r), id, 0);
    }

    @PostMapping("/messages/{id}/sandbox-receipt")
    public Object receipt(
            @PathVariable
            String id, @RequestBody
            Map<String, Object> body, HttpServletRequest r) {
        var a = actor(r);
        var result =
                service.receipt(
                        a.tenant(),
                        id,
                        Support.required(body, "event_key", 128),
                        Support.required(body, "state", 32));
        log(a, "sandbox_receipt", id);
        return result;
    }

    @PostMapping("/messages/{id}/refund")
    public Object refund(
            @PathVariable
            String id, @RequestBody
            Map<String, Object> body, HttpServletRequest r) {
        var a = actor(r);
        var result = service.refund(a.tenant(), id, Support.required(body, "reason", 256));
        log(a, "refund", id);
        return result;
    }

    @PostMapping("/suppressions")
    public Object suppress(@RequestBody
    Map<String, Object> body, HttpServletRequest r) {
        var a = actor(r);
        var result =
                service.suppress(
                        a.tenant(),
                        Support.required(body, "contact_id", 64),
                        Support.required(body, "reason", 256));
        log(a, "suppression", null);
        return result;
    }

    @PostMapping("/campaigns/{id}/launch")
    public Object launch(
            @PathVariable
            String id,
            @RequestHeader("Idempotency-Key")
            String key,
            HttpServletRequest r) {
        var a = actor(r);
        var result = service.launchCampaign(a.tenant(), id, key);
        log(a, "campaign_launch", id);
        return result;
    }

    @GetMapping("/batches/{id}")
    public Object batch(@PathVariable
    String id, HttpServletRequest r) {
        return service.batchStatus(principal(r), id);
    }

    @GetMapping("/webhook-deliveries")
    public Object deliveries(HttpServletRequest r) {
        return hooks.deliveries(actor(r).tenant());
    }
}
