package com.duozhi.outbound.dial.service;

import com.duozhi.outbound.auth.domain.Identity;
import com.duozhi.outbound.common.dao.Store;
import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.dial.domain.DialBatchScope;
import com.duozhi.outbound.workspace.service.WorkspaceDocuments;
import java.util.*;
import org.springframework.stereotype.Service;

/**
 * The admission switch and operator ceilings are tenant-local. Already claimed calls may finish.
 */
@Service
public class TenantDialPolicy {
    private final WorkspaceDocuments docs;

    public TenantDialPolicy(WorkspaceDocuments docs) {
        this.docs = docs;
    }

    public boolean open(long tenant) {
        var batch = DialBatchScope.current();
        String k = "tenant-dial-open:" + tenant;
        if (batch != null && batch.counts.containsKey(k)) return batch.counts.get(k) == 1;
        var doc = docs.find(tenant, "call_switch", "tenant");
        boolean enabled =
                doc == null
                        || !Boolean.FALSE.equals(
                                WorkspaceDocuments.map(doc.get("draft")).get("enabled"));
        if (batch != null) batch.counts.put(k, enabled ? 1L : 0L);
        return enabled;
    }

    public void requireOpen(long tenant) {
        if (!open(tenant)) throw new ApiError(409, "本租户呼叫开关已关闭");
    }

    public Map<String, Object> read(Identity i) {
        var doc = docs.find(i.tenant(), "call_switch", "tenant");
        long active =
                docs.jdbc.queryForObject(
                        "SELECT COUNT(*) FROM callsession WHERE tenant_id=? AND status IN ('DIALING','ANSWERED','IN_AI','WAITING_HUMAN','HANDOFF_TRANSFERRING','IN_HUMAN')",
                        Long.class,
                        i.tenant());
        var queue =
                docs.jdbc.queryForMap(
                        "SELECT COUNT(CASE WHEN c.attempts=0 THEN 1 END) AS first_pending,COUNT(CASE WHEN c.attempts>0 THEN 1 END) AS repeat_pending FROM callsession c LEFT JOIN campaign g ON g.tenant_id=c.tenant_id AND g.id=c.campaign_id WHERE c.tenant_id=? AND (g.id IS NULL OR g.status NOT IN ('deleted','stopped','completed')) AND (c.status IN ('CREATED','QUEUED') OR (c.status='FAILED' AND c.next_attempt_at IS NOT NULL AND c.attempts<c.max_attempts))",
                        i.tenant());
        long unprepared =
                docs.jdbc.queryForObject(
                        "SELECT COUNT(*) FROM campaigncontact m JOIN campaign g ON g.id=m.campaign_id WHERE g.tenant_id=? AND m.is_active AND g.status IN ('draft','prepared','running','paused') AND NOT EXISTS(SELECT 1 FROM callsession c WHERE c.tenant_id=g.tenant_id AND c.campaign_id=g.id AND c.contact_id=m.contact_id)",
                        Long.class,
                        i.tenant());
        return Map.of(
                "enabled",
                doc == null
                        || !Boolean.FALSE.equals(
                                WorkspaceDocuments.map(doc.get("draft")).get("enabled")),
                "revision",
                doc == null ? 0 : doc.get("revision"),
                "active",
                active,
                "first_pending",
                unprepared + ((Number) queue.get("first_pending")).longValue(),
                "repeat_pending",
                queue.get("repeat_pending"),
                "updated_at",
                Store.now().toString());
    }

    public Object update(Identity i, boolean enabled, int revision) {
        return docs.tx.serialized(
                () -> {
                    docs.tx.platform();
                    docs.jdbc.queryForObject(
                            "SELECT id FROM tenant WHERE id=? FOR UPDATE", Long.class, i.tenant());
                    docs.save(
                            i,
                            "call_switch",
                            "tenant",
                            "",
                            Map.of("name", "呼叫开关", "enabled", enabled),
                            revision);
                    return read(i);
                });
    }
}
