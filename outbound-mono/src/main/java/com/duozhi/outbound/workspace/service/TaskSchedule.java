package com.duozhi.outbound.workspace.service;

import com.duozhi.outbound.common.exception.ApiError;
import java.time.*;
import java.util.*;

/** UTC execution window; daily calendar and tenant compliance remain separate, stricter gates. */
public final class TaskSchedule {
    private TaskSchedule() {}

    public static void validate(String start, String end) {
        try {
            Instant a = start.isBlank() ? null : Instant.parse(start),
                    b = end.isBlank() ? null : Instant.parse(end);
            if (a != null && b != null && !a.isBefore(b)) throw new ApiError(422, "结束时间必须晚于开始时间");
        } catch (DateTimeException e) {
            throw new ApiError(422, "任务时间须为有效ISO时间");
        }
    }

    public static boolean isOpen(Map<String, Object> policy, Instant now) {
        var options = WorkspaceDocuments.map(policy.getOrDefault("_task_options", Map.of()));
        if (options.get("windows") instanceof List<?> list) {
            var calendar =
                    new LinkedHashMap<>(
                            com.duozhi.outbound.operations.service.OutboundRules.defaults());
            calendar.put("timezone", policy.getOrDefault("timezone", "Asia/Shanghai"));
            calendar.put("windows", list);
            if (!com.duozhi.outbound.operations.service.OutboundRules.inWindow(calendar, now))
                return false;
        }
        String start = Objects.toString(policy.get("_task_start_at"), ""),
                end = Objects.toString(policy.get("_task_end_at"), "");
        return (start.isBlank() || !now.isBefore(Instant.parse(start)))
                && (end.isBlank() || now.isBefore(Instant.parse(end)));
    }
}
