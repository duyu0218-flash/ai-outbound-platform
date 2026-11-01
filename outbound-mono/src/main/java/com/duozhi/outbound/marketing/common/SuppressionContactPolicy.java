package com.duozhi.outbound.marketing.common;

import org.springframework.stereotype.Service;

/**
 * Hard blocks shared by every channel: the call suppression list, the messaging unsubscribe list,
 * contacts flagged do-not-contact, and any blocking touch result (invalid number, opt-out, hard
 * refusal).
 */
@Service
public class SuppressionContactPolicy implements ContactPolicy {
    private final MarketingStore db;

    public SuppressionContactPolicy(MarketingStore db) {
        this.db = db;
    }

    @Override
    public Admission admit(long tenant, String e164, String phoneHash, String channel) {
        String local = e164.startsWith("+86") ? e164.substring(3) : e164;
        if (db.count(
                        "SELECT COUNT(*) FROM phonesuppression WHERE tenant_id=? AND phone IN (?,?)",
                        tenant,
                        e164,
                        local)
                > 0) return Admission.deny("通话黑名单");
        if (db.count(
                        "SELECT COUNT(*) FROM msg_suppression WHERE tenant_id=? AND recipient_hash=?",
                        tenant,
                        phoneHash)
                > 0) return Admission.deny("已退订");
        if (db.count(
                        "SELECT COUNT(*) FROM contact WHERE tenant_id=? AND phone IN (?,?) AND (dnc=TRUE OR consent_state='REVOKED')",
                        tenant,
                        e164,
                        local)
                > 0) return Admission.deny("联系人拒绝联系");
        if (db.count(
                        "SELECT COUNT(*) FROM mk_touch_event WHERE tenant_id=? AND phone_hash=? AND code IN ('"
                                + String.join(
                                        "','", OutcomeCodes.BLOCKING.stream().sorted().toList())
                                + "')",
                        tenant,
                        phoneHash)
                > 0) return Admission.deny("历史触达结果禁止再次联系");
        return Admission.OK;
    }
}
