package com.duozhi.outbound.operations.controller;

import com.duozhi.outbound.auth.domain.Identity;
import com.duozhi.outbound.operations.service.TaskStrategies;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/ops/benchmark/strategies")
public class TaskStrategyController {
    private final TaskStrategies strategies;

    public TaskStrategyController(TaskStrategies strategies) {
        this.strategies = strategies;
    }

    private Identity actor(HttpServletRequest r) {
        var i = Identity.from(r);
        i.requireUser();
        i.admin("operations:write");
        return i;
    }

    @GetMapping("/{type}")
    public Object list(@PathVariable
    String type, HttpServletRequest r) {
        return strategies.list(actor(r).tenant(), type);
    }

    @PutMapping("/{type}/{id}")
    public Object save(
            @PathVariable
            String type,
            @PathVariable
            String id,
            @RequestBody
            Map<String, Object> b,
            HttpServletRequest r) {
        return strategies.save(actor(r), type, id, b);
    }

    @DeleteMapping("/{type}/{id}")
    public Object delete(
            @PathVariable
            String type,
            @PathVariable
            String id,
            @RequestParam
            int revision,
            HttpServletRequest r) {
        return strategies.delete(actor(r), type, id, revision);
    }

    @GetMapping("/evaluation/{id}/tasks/{campaign}")
    public Object evaluate(
            @PathVariable
            String id, @PathVariable
            long campaign, HttpServletRequest r) {
        long tenant = actor(r).tenant();
        var rule = strategies.selected(tenant, "evaluation", id);
        if (rule.isEmpty())
            throw new com.duozhi.outbound.common.exception.ApiError(404, "评估规则不存在或已停用");
        return TaskStrategies.assess(rule, strategies.stats(tenant, campaign));
    }
}
