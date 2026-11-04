package com.duozhi.outbound.workspace.controller;

import com.duozhi.outbound.auth.domain.*;
import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.workspace.service.*;
import jakarta.servlet.http.HttpServletRequest;
import java.time.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/workspace/scripts/{id}/reach")
@AdminPermission("script-templates:write")
public class ReachReports {
    private final WorkspaceDocuments docs;
    private final ScriptReach reach;

    public ReachReports(WorkspaceDocuments docs, ScriptReach reach) {
        this.docs = docs;
        this.reach = reach;
    }

    private Identity check(long id, HttpServletRequest request) {
        var i = Identity.from(request);
        i.admin("script-templates:write");
        docs.require(i.tenant(), "script", String.valueOf(id));
        return i;
    }

    @PutMapping("/settings")
    public Object setting(
            @PathVariable
            long id, @RequestBody
            Map<String, Object> body, HttpServletRequest r) {
        var i = check(id, r);
        if (!(body.get("enabled") instanceof Boolean enabled)) throw new ApiError(422, "需要选择采集状态");
        return docs.save(
                i,
                "reach_setting",
                String.valueOf(id),
                "",
                Map.of("name", "节点采集", "enabled", enabled),
                WorkspaceDocuments.number(body.get("revision")));
    }

    @GetMapping
    public Object report(
            @PathVariable
            long id,
            @RequestParam
            String kind,
            @RequestParam
            OffsetDateTime from,
            @RequestParam
            OffsetDateTime until,
            @RequestParam
            OffsetDateTime compareFrom,
            @RequestParam
            OffsetDateTime compareUntil,
            HttpServletRequest r) {
        var i = check(id, r);
        var setting = docs.find(i.tenant(), "reach_setting", String.valueOf(id));
        return Map.of(
                "enabled",
                setting != null
                        && Boolean.TRUE.equals(
                                WorkspaceDocuments.map(setting.get("draft")).get("enabled")),
                "revision",
                setting == null ? 0 : setting.get("revision"),
                "current",
                reach.period(i.tenant(), id, kind, utc(from), utc(until)),
                "comparison",
                reach.period(i.tenant(), id, kind, utc(compareFrom), utc(compareUntil)));
    }

    private LocalDateTime utc(OffsetDateTime value) {
        return value.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }
}
