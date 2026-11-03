package com.duozhi.outbound.workspace.controller;

import com.duozhi.outbound.auth.domain.*;
import com.duozhi.outbound.workspace.service.WorkspaceMessages;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/workspace/sms-tasks")
@AdminPermission("messaging:write")
public class MessageWorkflow {
    private final WorkspaceMessages service;

    public MessageWorkflow(WorkspaceMessages service) {
        this.service = service;
    }

    private Identity actor(HttpServletRequest r) {
        var i = Identity.from(r);
        i.requireUser();
        i.admin("messaging:write");
        return i;
    }

    @GetMapping
    public Object list(HttpServletRequest r) {
        return service.list(actor(r).tenant());
    }

    @GetMapping("/{id}")
    public Object get(@PathVariable
    String id, HttpServletRequest r) {
        return service.get(actor(r).tenant(), id);
    }

    @GetMapping("/{id}/members")
    public Object members(
            @PathVariable
            String id,
            @RequestParam(defaultValue = "1")
            int page,
            HttpServletRequest r) {
        return service.members(actor(r).tenant(), id, page);
    }

    @PutMapping("/{id}")
    public Object save(
            @PathVariable
            String id, @RequestBody
            Map<String, Object> body, HttpServletRequest r) {
        return service.save(actor(r), id, body);
    }

    @GetMapping("/{id}/preflight")
    public Object check(@PathVariable
    String id, HttpServletRequest r) {
        return service.preflight(actor(r).tenant(), id);
    }

    @PostMapping("/{id}/start")
    public Object start(
            @PathVariable
            String id, @RequestBody
            Map<String, Object> body, HttpServletRequest r) {
        return service.start(actor(r), id, body);
    }

    @PostMapping("/{id}/{command:retry|cancel}")
    public Object command(
            @PathVariable
            String id, @PathVariable
            String command, HttpServletRequest r) {
        return service.command(actor(r), id, command);
    }
}
