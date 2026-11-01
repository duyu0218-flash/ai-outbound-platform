package com.duozhi.outbound.marketing.common;

import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Links an executed send or call back to the segment snapshot and journey step that produced it.
 * Senders register a dispatch when they create the message or call; result handlers resolve it.
 */
@Service
public class Attribution {
    public static final String MESSAGE = "msg_message", CALL = "callsession";

    private final MarketingStore db;

    public Attribution(MarketingStore db) {
        this.db = db;
    }

    public record Dispatch(
            String id,
            long tenant,
            String channel,
            String targetType,
            String targetId,
            String phoneHash,
            Long contactId,
            String snapshotId,
            String journeyId,
            String journeyMemberId) {}

    /** Idempotent per target: registering the same message or call twice keeps the first row. */
    public Dispatch register(
            long tenant,
            String channel,
            String targetType,
            String targetId,
            String phoneHash,
            Long contactId,
            String snapshotId,
            String journeyId,
            String journeyMemberId) {
        db.write(
                "INSERT IGNORE INTO mk_dispatch(id,tenant_id,channel,target_type,target_id,phone_hash,contact_id,snapshot_id,journey_id,journey_member_id) VALUES(?,?,?,?,?,?,?,?,?,?)",
                MarketingStore.id("dsp"),
                tenant,
                channel,
                targetType,
                targetId,
                phoneHash,
                contactId,
                snapshotId,
                journeyId,
                journeyMemberId);
        return find(tenant, targetType, targetId);
    }

    public Dispatch find(long tenant, String targetType, String targetId) {
        Map<String, Object> r =
                db.one(
                        "SELECT * FROM mk_dispatch WHERE tenant_id=? AND target_type=? AND target_id=?",
                        tenant,
                        targetType,
                        targetId);
        if (r == null) return null;
        return new Dispatch(
                (String) r.get("id"),
                tenant,
                (String) r.get("channel"),
                targetType,
                targetId,
                (String) r.get("phone_hash"),
                r.get("contact_id") == null ? null : ((Number) r.get("contact_id")).longValue(),
                (String) r.get("snapshot_id"),
                (String) r.get("journey_id"),
                (String) r.get("journey_member_id"));
    }
}
