package com.duozhi.outbound.messaging.controller;

import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.integration.support.Support;
import com.duozhi.outbound.messaging.service.*;
import jakarta.servlet.http.HttpServletRequest;
import java.io.*;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/openapi/v1")
public class MessageApi {
    private final Messaging service;
    private final MessageApiAuth auth;
    private final MessageWebhooks hooks;

    public MessageApi(Messaging service, MessageApiAuth auth, MessageWebhooks hooks) {
        this.service = service;
        this.auth = auth;
        this.hooks = hooks;
    }

    private byte[] read(HttpServletRequest r) throws IOException {
        byte[] body = r.getInputStream().readNBytes(512 * 1024 + 1);
        if (body.length > 512 * 1024) throw new ApiError(413, "请求体过大");
        return body;
    }

    private Map<String, Object> parse(byte[] body) {
        try {
            var mapper =
                    Support.JSON
                            .copy()
                            .enable(
                                    com.fasterxml.jackson.core.JsonParser.Feature
                                            .STRICT_DUPLICATE_DETECTION);
            Object value = mapper.readValue(body, Object.class);
            if (!(value instanceof Map<?, ?>)) throw new IOException();
            return Support.map(value);
        } catch (Exception e) {
            throw new ApiError(422, "JSON 无效或存在重复字段");
        }
    }

    @PostMapping("/messages")
    public Object create(HttpServletRequest r) throws IOException {
        byte[] body = read(r);
        var p = auth.verify(r, body);
        return ResponseEntity.accepted()
                .body(service.create(p, r.getHeader("Idempotency-Key"), parse(body)));
    }

    @GetMapping("/messages/{id}")
    public Object message(@PathVariable
    String id, HttpServletRequest r) throws IOException {
        return service.message(auth.verify(r, read(r)), id);
    }

    @PostMapping("/messages/{id}/cancel")
    public Object cancel(@PathVariable
    String id, HttpServletRequest r) throws IOException {
        return service.cancel(auth.verify(r, read(r)), id);
    }

    @GetMapping("/messages/{id}/events")
    public Object events(
            @PathVariable
            String id,
            @RequestParam(defaultValue = "0")
            long after,
            HttpServletRequest r)
            throws IOException {
        return service.events(auth.verify(r, read(r)), id, after);
    }

    @GetMapping("/events")
    public Object events(@RequestParam(defaultValue = "0")
    long after, HttpServletRequest r)
            throws IOException {
        return service.events(auth.verify(r, read(r)), null, after);
    }

    @GetMapping("/capabilities")
    public Object capabilities(HttpServletRequest r) throws IOException {
        auth.verify(r, read(r));
        return service.capabilities();
    }

    @GetMapping("/balance")
    public Object balance(HttpServletRequest r) throws IOException {
        return service.balance(auth.verify(r, read(r)).tenant());
    }

    @PostMapping("/message-batches")
    public Object batch(HttpServletRequest r) throws IOException {
        byte[] body = read(r);
        var p = auth.verify(r, body);
        return ResponseEntity.accepted()
                .body(service.batch(p, r.getHeader("Idempotency-Key"), parse(body)));
    }

    @GetMapping({"/message-batches/{id}", "/message-batches/{id}/items"})
    public Object batch(@PathVariable
    String id, HttpServletRequest r) throws IOException {
        return service.batchStatus(auth.verify(r, read(r)), id);
    }

    @PostMapping("/messages/{id}/interactions")
    public Object interaction(@PathVariable
    String id, HttpServletRequest r) throws IOException {
        byte[] body = read(r);
        var p = auth.verify(r, body);
        return ResponseEntity.accepted()
                .body(service.interaction(p, id, r.getHeader("Idempotency-Key"), parse(body)));
    }

    @PostMapping("/webhook-subscriptions")
    public Object subscribe(HttpServletRequest r) throws IOException {
        byte[] body = read(r);
        var p = auth.verify(r, body);
        return ResponseEntity.accepted()
                .body(hooks.create(p, r.getHeader("Idempotency-Key"), parse(body)));
    }

    @GetMapping("/webhook-subscriptions/{id}")
    public Object subscription(@PathVariable
    String id, HttpServletRequest r) throws IOException {
        return hooks.get(auth.verify(r, read(r)), id);
    }

    @PostMapping("/webhook-subscriptions/{id}/disable")
    public Object disable(@PathVariable
    String id, HttpServletRequest r) throws IOException {
        return hooks.disable(auth.verify(r, read(r)), id);
    }
}
