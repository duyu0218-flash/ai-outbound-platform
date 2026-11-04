package com.duozhi.outbound.workspace.service;

import com.duozhi.outbound.common.exception.ApiError;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class ScriptReach {
    private final WorkspaceDocuments docs;

    public ScriptReach(WorkspaceDocuments docs) {
        this.docs = docs;
    }

    public boolean collecting(long tenant, long script) {
        var setting = docs.find(tenant, "reach_setting", String.valueOf(script));
        return setting != null
                && Boolean.TRUE.equals(WorkspaceDocuments.map(setting.get("draft")).get("enabled"));
    }

    public void record(
            Map<String, Object> call, Object script, String kind, Map<String, String> targets) {
        if (!(script instanceof Number n)
                || call.get("id") == null
                || call.get("created_at") == null) return;
        long tenant = ((Number) call.get("tenant_id")).longValue(), id = n.longValue();
        if (!collecting(tenant, id)) return;
        var batch = new ArrayList<Object[]>();
        add(batch, tenant, id, call, "sample", "call", "已采集通话");
        targets.forEach((k, v) -> add(batch, tenant, id, call, kind, k, v));
        docs.jdbc.batchUpdate(
                "INSERT INTO script_reach(tenant_id,script_id,flow_id,call_id,kind,target_id,target_label,cohort_at) VALUES(?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE target_id=script_reach.target_id",
                batch);
    }

    private void add(
            List<Object[]> batch,
            long tenant,
            long script,
            Map<String, Object> call,
            String kind,
            String id,
            String label) {
        batch.add(
                new Object[] {
                    tenant,
                    script,
                    Objects.requireNonNullElse(call.get("script_flow_version_id"), 0),
                    call.get("id"),
                    kind,
                    id,
                    label.substring(0, Math.min(250, label.length())),
                    call.get("created_at")
                });
    }

    public Map<String, Object> period(
            long tenant, long script, String kind, LocalDateTime from, LocalDateTime until) {
        if (!Set.of("node", "knowledge").contains(kind)
                || !until.isAfter(from)
                || Duration.between(from, until).toDays() > 366)
            throw new ApiError(422, "统计类型或时间范围无效");
        var params = new Object[] {tenant, script, from, until};
        var total =
                docs.jdbc.queryForObject(
                        "SELECT COUNT(*) FROM script_reach WHERE tenant_id=? AND script_id=? AND cohort_at>=? AND cohort_at<? AND kind='sample'",
                        Long.class,
                        params);
        return Map.of(
                "total",
                total,
                "rows",
                docs.jdbc.queryForList(
                        "SELECT flow_id,target_id,MAX(target_label) AS label,COUNT(*) AS reached FROM script_reach WHERE tenant_id=? AND script_id=? AND cohort_at>=? AND cohort_at<? AND kind=? GROUP BY flow_id,target_id ORDER BY reached DESC,flow_id,target_id",
                        tenant,
                        script,
                        from,
                        until,
                        kind));
    }
}
