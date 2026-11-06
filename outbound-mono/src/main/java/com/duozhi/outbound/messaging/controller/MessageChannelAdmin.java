package com.duozhi.outbound.messaging.controller;

import com.duozhi.outbound.auth.domain.*;
import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.integration.support.Support;
import com.duozhi.outbound.messaging.service.*;
import com.duozhi.outbound.system.service.Audit;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/messaging")
@AdminPermission("messaging:write")
public class MessageChannelAdmin {
    private final MessageChannels channels;
    private final MessageDelivery delivery;
    private final MessageAssets assets;
    private final Messaging messages;
    private final MessageReconciliation bills;
    private final MessageWebhooks hooks;
    private final Audit audit;

    public MessageChannelAdmin(
            MessageChannels channels,
            MessageDelivery delivery,
            MessageAssets assets,
            Messaging messages,
            MessageReconciliation bills,
            MessageWebhooks hooks,
            Audit audit) {
        this.channels = channels;
        this.delivery = delivery;
        this.assets = assets;
        this.messages = messages;
        this.bills = bills;
        this.hooks = hooks;
        this.audit = audit;
    }

    private Identity actor(HttpServletRequest r) {
        var a = Identity.from(r);
        a.requireUser();
        a.admin("messaging:write");
        return a;
    }

    private void log(Identity a, String action, Object id) {
        audit.record(a, action, "messaging", id, "消息通道管理");
    }

    @GetMapping("/channels")
    public Object channels(HttpServletRequest r) {
        return channels.list(actor(r).tenant());
    }

    @PostMapping("/channels")
    public Object create(@RequestBody
    Map<String, Object> body, HttpServletRequest r) {
        var a = actor(r);
        var result = channels.create(a.tenant(), body);
        log(a, "channel_create", result.get("id"));
        return result;
    }

    @GetMapping("/channels/{id}")
    public Object channel(@PathVariable
    String id, HttpServletRequest r) {
        return channels.get(actor(r).tenant(), id);
    }

    @PostMapping("/channels/{id}/probe")
    public Object probe(@PathVariable
    String id, HttpServletRequest r) {
        var a = actor(r);
        var result = delivery.probe(a.tenant(), id);
        log(a, "channel_probe", id);
        return result;
    }

    @PostMapping("/channels/{id}/state")
    public Object state(
            @PathVariable
            String id, @RequestBody
            Map<String, Object> body, HttpServletRequest r) {
        var a = actor(r);
        var result = channels.state(a.tenant(), id, Support.required(body, "state", 16));
        log(a, "channel_state", id);
        return result;
    }

    @PostMapping("/channels/{id}/acceptance")
    public Object acceptance(
            @PathVariable
            String id, @RequestBody
            Map<String, Object> body, HttpServletRequest r) {
        var a = actor(r);
        var result = channels.recordAcceptance(a.tenant(), id, body);
        log(a, "channel_provider_acceptance", id);
        return result;
    }

    @GetMapping("/channels/{id}/bindings")
    public Object bindings(@PathVariable
    String id, HttpServletRequest r) {
        return channels.bindings(actor(r).tenant(), id);
    }

    @PostMapping("/channels/{id}/bindings")
    public Object bind(
            @PathVariable
            String id, @RequestBody
            Map<String, Object> body, HttpServletRequest r) {
        var a = actor(r);
        var result = channels.bind(a.tenant(), id, body);
        log(a, "channel_binding", id);
        return result;
    }

    @GetMapping("/assets")
    public Object assets(HttpServletRequest r) {
        return assets.list(actor(r).tenant());
    }

    @PostMapping("/assets")
    public Object upload(
            @RequestParam
            String name,
            @RequestParam(required = false)
            String expires_at,
            HttpServletRequest r)
            throws java.io.IOException {
        var a = actor(r);
        byte[] bytes = r.getInputStream().readNBytes(5 * 1024 * 1024 + 1);
        Instant expiry = null;
        if (expires_at != null)
            try {
                expiry = Instant.parse(expires_at);
            } catch (Exception e) {
                throw new ApiError(422, "有效期格式无效");
            }
        var result = assets.upload(a.tenant(), name, bytes, expiry);
        log(a, "asset_upload", null);
        return result;
    }

    @GetMapping("/assets/{id}/content")
    public Object asset(@PathVariable
    String id, HttpServletRequest r) {
        long tenant = actor(r).tenant();
        var asset = assets.get(tenant, id, false);
        return ResponseEntity.ok()
                .header("Cache-Control", "private, no-store")
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Disposition", "attachment")
                .contentType(MediaType.parseMediaType(asset.get("mime").toString()))
                .body(assets.bytes(tenant, id));
    }

    @PostMapping("/assets/{id}/disable")
    public Object disable(@PathVariable
    String id, HttpServletRequest r) {
        var a = actor(r);
        var result = assets.disable(a.tenant(), id);
        log(a, "asset_disable", id);
        return result;
    }

    @GetMapping("/messages/{id}/delivery")
    public Object detail(@PathVariable
    String id, HttpServletRequest r) {
        return delivery.detail(actor(r).tenant(), id);
    }

    @PostMapping("/messages/{id}/query")
    public Object query(@PathVariable
    String id, HttpServletRequest r) {
        var a = actor(r);
        var result = delivery.scheduleQuery(a.tenant(), id);
        log(a, "message_query", id);
        return result;
    }

    @PostMapping("/applications/{id}/settings")
    public Object settings(
            @PathVariable
            String id, @RequestBody
            Map<String, Object> body, HttpServletRequest r) {
        var a = actor(r);
        var result = messages.applicationSettings(a.tenant(), id, body);
        log(a, "application_settings", id);
        return result;
    }

    @PostMapping("/applications/{id}/rotate-secret")
    public Object rotate(
            @PathVariable
            String id, @RequestBody
            Map<String, Object> body, HttpServletRequest r) {
        var a = actor(r);
        var result = messages.rotateApplication(a.tenant(), id, body);
        log(a, "application_rotate", id);
        return result;
    }

    @PostMapping("/funding")
    public Object fund(@RequestBody
    Map<String, Object> body, HttpServletRequest r) {
        var a = actor(r);
        a.admin("billing:write");
        var result =
                messages.fund(
                        a.tenant(),
                        r.getHeader("Idempotency-Key"),
                        Support.integer(body, "amount", 0),
                        Support.required(body, "reference", 128));
        log(a, "message_funding", null);
        return result;
    }

    @GetMapping("/reconciliations")
    public Object bills(HttpServletRequest r) {
        return bills.list(actor(r).tenant());
    }

    @GetMapping("/reconciliations/{id}")
    public Object bill(@PathVariable
    String id, HttpServletRequest r) {
        return bills.get(actor(r).tenant(), id);
    }

    @PostMapping("/reconciliations")
    public Object bill(@RequestBody
    Map<String, Object> body, HttpServletRequest r) {
        var a = actor(r);
        var result = bills.create(a.tenant(), body);
        log(a, "supplier_reconcile", null);
        return result;
    }

    @PostMapping("/webhook-deliveries/{id}/retry")
    public Object retry(@PathVariable
    String id, HttpServletRequest r) {
        var a = actor(r);
        var result = hooks.retry(new Messaging.Principal(a.tenant(), "console"), id);
        log(a, "message_webhook_retry", id);
        return result;
    }
}
