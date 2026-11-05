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
    @org.springframework.beans.factory.annotation.Autowired
    private com.duozhi.outbound.operations.service.AccountResources accountResources;

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

    public boolean ownerAvailable(long tenant, Object owner) {
        if (owner == null) return true;
        if (!accountResources.available(tenant, owner)) return false;
        var batch = DialBatchScope.current();
        String k = "owner-limit:" + tenant + ":" + owner;
        long cap;
        if (batch != null && batch.counts.containsKey(k)) cap = batch.counts.get(k);
        else {
            var doc = docs.find(tenant, "owner_dial_limit", owner.toString());
            cap =
                    doc == null
                            ? -1
                            : WorkspaceDocuments.number(
                                    WorkspaceDocuments.map(doc.get("draft")).get("limit"));
            if (batch != null) batch.counts.put(k, cap);
        }
        if (cap < 0) return true;
        String countKey = "owner-count:" + tenant + ":" + owner;
        long active;
        if (batch != null && batch.counts.containsKey(countKey))
            active = batch.counts.get(countKey);
        else {
            active =
                    docs.jdbc.queryForObject(
                            "SELECT COUNT(*) FROM callsession c LEFT JOIN campaign g ON g.tenant_id=c.tenant_id AND g.id=c.campaign_id WHERE c.tenant_id=? AND COALESCE(g.created_by,c.human_agent_id)=? AND c.status IN ('DIALING','ANSWERED','IN_AI','WAITING_HUMAN','HANDOFF_TRANSFERRING','IN_HUMAN')",
                            Long.class,
                            tenant,
                            owner);
            if (batch != null) batch.counts.put(countKey, active);
        }
        return active < cap;
    }

    public void captured(long tenant, Map<String, Object> call, Object owner) {
        var batch = DialBatchScope.current();
        String key = "owner-count:" + tenant + ":" + owner;
        if (batch != null && batch.counts.containsKey(key)) batch.counts.merge(key, 1L, Long::sum);
        docs.jdbc.update(
                "INSERT INTO ops_call_dimension(tenant_id,call_id,actor_id,team_id,city,line_group,captured_at) SELECT ?,?,?,(SELECT MIN(m.team_id) FROM ops_member m JOIN ops_team t ON t.tenant_id=m.tenant_id AND t.id=m.team_id AND t.enabled=1 WHERE m.tenant_id=? AND m.user_id=?),(SELECT LEFT(COALESCE(JSON_UNQUOTE(JSON_EXTRACT(m.variables_json,'$.city')),JSON_UNQUOTE(JSON_EXTRACT(m.variables_json,'$.\"城市\"'))),100) FROM platform_audience_member m WHERE m.tenant_id=? AND m.snapshot_id=CONCAT('call:',?) AND m.contact_id=?),(SELECT group_name FROM ops_line_group_assignment WHERE tenant_id=? AND line_id=?),UTC_TIMESTAMP(3) ON DUPLICATE KEY UPDATE call_id=ops_call_dimension.call_id",
                tenant,
                call.get("id"),
                owner,
                tenant,
                owner,
                tenant,
                call.get("campaign_id"),
                call.get("contact_id"),
                tenant,
                call.get("telephony_line_id"));
    }

    public Object limit(Identity i, long owner) {
        if (docs.one("SELECT id FROM sys_user WHERE tenant_id=? AND id=?", i.tenant(), owner)
                == null) throw new ApiError(404, "账号不存在");
        var doc = docs.find(i.tenant(), "owner_dial_limit", "" + owner);
        return Map.of(
                "limit",
                doc == null
                        ? 0
                        : WorkspaceDocuments.number(
                                WorkspaceDocuments.map(doc.get("draft")).get("limit")),
                "inherited",
                doc == null,
                "revision",
                doc == null ? 0 : doc.get("revision"));
    }

    public Object saveLimit(Identity i, long owner, int limit, int revision) {
        return docs.tx.serialized(
                () -> {
                    docs.tx.platform();
                    docs.jdbc.queryForObject(
                            "SELECT id FROM tenant WHERE id=? FOR UPDATE", Long.class, i.tenant());
                    if (docs.one(
                                    "SELECT id FROM sys_user WHERE tenant_id=? AND id=? AND enabled=1",
                                    i.tenant(),
                                    owner)
                            == null) throw new ApiError(404, "账号不可用");
                    docs.save(
                            i,
                            "owner_dial_limit",
                            "" + owner,
                            "",
                            Map.of("name", "账号外呼并发", "limit", limit),
                            revision);
                    return limit(i, owner);
                });
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
