package com.duozhi.outbound.client.service;

import com.duozhi.outbound.call.service.CallLifecycle;
import com.duozhi.outbound.client.dao.ClientStore;
import com.duozhi.outbound.client.security.ClientAuthenticator;
import com.duozhi.outbound.common.dao.Store;
import com.duozhi.outbound.common.service.Transactions;
import com.duozhi.outbound.common.support.JsonSupport;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Turns reviewed call analyses into client lead snapshots. Runs inside the analysis transaction,
 * which already holds the call row; the client row lock serializes duplicate detection.
 */
@Service
public class ClientDelivery {
    private final ClientStore db;
    private final Store store;
    private final Transactions tx;
    private final JsonSupport json;
    private final ClientSettings settings;
    private final ClientAuthenticator clients;

    public ClientDelivery(
            ClientStore db,
            Store store,
            Transactions tx,
            JsonSupport json,
            ClientSettings settings,
            ClientAuthenticator clients) {
        this.db = db;
        this.store = store;
        this.tx = tx;
        this.json = json;
        this.settings = settings;
        this.clients = clients;
    }

    public static String mask(Object phone) {
        String digits = Objects.toString(phone, "").trim();
        if (digits.length() < 7) return "****";
        return digits.substring(0, 3) + "****" + digits.substring(digits.length() - 4);
    }

    static boolean qualified(Map<String, Object> analysis, Map<String, Object> config) {
        if (analysis == null || Boolean.TRUE.equals(analysis.get("needs_review"))) return false;
        boolean reviewed = "reviewed".equals(analysis.get("review_state"));
        if (!reviewed && !Boolean.TRUE.equals(config.get("auto_deliver"))) return false;
        return ((List<?>) config.get("deliver_results")).contains(analysis.get("result_code"));
    }

    /** Called from CallAnalysis.publish for every automatic or reviewed result change. */
    public void sync(Map<String, Object> call, Map<String, Object> analysis) {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("client delivery requires the analysis transaction");
        if (!clients.enabled() || call == null || call.get("campaign_id") == null) return;
        long tenant = CallLifecycle.tenant(call);
        String callId = call.get("id").toString();
        Map<String, Object> binding =
                db.one(
                        "bindingGet",
                        JsonSupport.map("tenantId", tenant, "campaignId", call.get("campaign_id")));
        Long bound = binding == null ? null : ((Number) binding.get("client_id")).longValue();
        Map<String, Object> org =
                bound == null
                        ? null
                        : db.one("orgLock", JsonSupport.map("tenantId", tenant, "clientId", bound));
        String version =
                JsonSupport.sha(
                        json.canonical(
                                JsonSupport.map(
                                        "result",
                                        analysis.get("result_code"),
                                        "review",
                                        analysis.get("review_state"),
                                        "needs_review",
                                        analysis.get("needs_review"),
                                        "updated",
                                        Objects.toString(analysis.get("updated_at"), ""),
                                        "summary",
                                        analysis.get("summary")),
                                true));
        boolean delivered = false;
        // QC changes follow the lead to the client that received it; new deliveries only go to
        // the currently bound client. Most analyses have no lead: probe without locking so the hot
        // path takes no gap locks, then lock existing rows against concurrent staff changes.
        // A manual withdrawal is a staff decision and is never revived by a later review.
        Map<String, Object> lookup = JsonSupport.map("tenantId", tenant, "callId", callId);
        List<Map<String, Object>> existing = db.rows("leadsByCall", lookup);
        if (!existing.isEmpty()) {
            lookup.put("locked", true);
            existing = db.rows("leadsByCall", lookup);
        }
        for (Map<String, Object> lead : existing) {
            long client = ((Number) lead.get("client_id")).longValue();
            delivered = true;
            Map<String, Object> config = settings.read(tenant, client);
            long id = ((Number) lead.get("id")).longValue();
            boolean manual = "manual".equals(lead.get("withdraw_reason"));
            if (qualified(analysis, config) && !manual) {
                db.write("leadSnapshot", snapshot(tenant, call, analysis, id));
                event(
                        tenant,
                        client,
                        id,
                        "system",
                        null,
                        "delivered".equals(lead.get("status")) ? "refreshed" : "redelivered",
                        JsonSupport.map("result_code", analysis.get("result_code")),
                        "sync:" + id + ":" + version);
            } else if ("delivered".equals(lead.get("status"))) {
                String reason =
                        Boolean.TRUE.equals(analysis.get("needs_review"))
                                ? "review_pending"
                                : "qc_changed";
                withdraw(tenant, client, id, reason, null, "system", null, version);
            }
        }
        // A call belongs to at most one client: rebinding never copies a delivered lead.
        if (bound == null || delivered) return;
        Map<String, Object> config = settings.read(tenant, bound);
        if (!qualified(analysis, config)) return;
        if (!clients.active(org)) {
            event(
                    tenant,
                    bound,
                    null,
                    "system",
                    null,
                    "skipped",
                    JsonSupport.map("call_session_id", callId, "reason", "client_inactive"),
                    "skip:" + callId + ":" + version);
            return;
        }
        String phone = Objects.toString(call.get("phone"), "");
        String dedup = JsonSupport.sha(tenant + "|" + bound + "|" + phone);
        int days = ((Number) config.get("dedup_days")).intValue();
        if (days > 0
                && db.count(
                                "leadDuplicate",
                                JsonSupport.map(
                                        "tenantId",
                                        tenant,
                                        "clientId",
                                        bound,
                                        "dedupKey",
                                        dedup,
                                        "since",
                                        Store.now().minusDays(days)))
                        > 0) {
            event(
                    tenant,
                    bound,
                    null,
                    "system",
                    null,
                    "dup_hit",
                    JsonSupport.map("call_session_id", callId, "phone", mask(phone)),
                    "dup:" + callId + ":" + version);
            return;
        }
        Map<String, Object> p = snapshot(tenant, call, analysis, null);
        p.putAll(
                JsonSupport.map(
                        "clientId",
                        bound,
                        "campaignId",
                        call.get("campaign_id"),
                        "callId",
                        callId,
                        "contactId",
                        call.get("contact_id"),
                        "dedupKey",
                        dedup,
                        "phone_masked",
                        mask(phone),
                        "now",
                        Store.now()));
        db.write("leadInsert", p);
        long id = ((Number) p.get("lead_id")).longValue();
        event(
                tenant,
                bound,
                id,
                "system",
                null,
                "delivered",
                JsonSupport.map(
                        "result_code", analysis.get("result_code"), "call_session_id", callId),
                "delivered:" + id);
    }

    private Map<String, Object> snapshot(
            long tenant, Map<String, Object> call, Map<String, Object> analysis, Long id) {
        Map<String, Object> structured =
                json.object(Objects.toString(analysis.get("structured_json"), "{}"));
        Object slots = structured.getOrDefault("slots", Collections.emptyMap());
        String name =
                call.get("contact_id") == null
                        ? null
                        : (String)
                                db.value(
                                        "contactName",
                                        JsonSupport.map(
                                                "tenantId",
                                                tenant,
                                                "contactId",
                                                call.get("contact_id")));
        String summary = Objects.toString(analysis.get("summary"), "");
        return JsonSupport.map(
                "tenantId",
                tenant,
                "leadId",
                id,
                "result_code",
                analysis.get("result_code"),
                "intent",
                Objects.toString(analysis.get("intent"), ""),
                "summary",
                summary.length() > 10000 ? summary.substring(0, 10000) : summary,
                "slots_json",
                json.json(slots == null ? Collections.emptyMap() : slots),
                "contact_name",
                name);
    }

    public boolean withdraw(
            long tenant,
            long client,
            long lead,
            String reason,
            String note,
            String actorKind,
            Long actor,
            String version) {
        int changed =
                db.write(
                        "leadWithdraw",
                        JsonSupport.map(
                                "tenantId",
                                tenant,
                                "leadId",
                                lead,
                                "reason",
                                reason,
                                "note",
                                note));
        if (changed == 1)
            event(
                    tenant,
                    client,
                    lead,
                    actorKind,
                    actor,
                    "withdrawn",
                    JsonSupport.map("reason", reason, "note", note),
                    "withdrawn:" + lead + ":" + (version == null ? UUID.randomUUID() : version));
        return changed == 1;
    }

    public void event(
            long tenant,
            long client,
            Long lead,
            String actorKind,
            Long actor,
            String action,
            Map<String, Object> detail,
            String key) {
        db.write(
                "eventInsert",
                JsonSupport.map(
                        "tenantId",
                        tenant,
                        "clientId",
                        client,
                        "leadId",
                        lead,
                        "actorKind",
                        actorKind,
                        "actorId",
                        actor,
                        "action",
                        action,
                        "detail_json",
                        json.json(detail),
                        "eventKey",
                        key.length() > 160 ? JsonSupport.sha(key) : key));
    }

    /**
     * Replays existing analyses of one campaign. Each call is locked like a review so a concurrent
     * reviewer cannot be overwritten by a stale snapshot. Idempotent; returns a resume cursor.
     */
    public Map<String, Object> backfill(long tenant, int campaignId, long afterId, int limit) {
        List<Map<String, Object>> rows =
                db.rows(
                        "backfillRows",
                        JsonSupport.map(
                                "tenantId",
                                tenant,
                                "campaignId",
                                campaignId,
                                "afterId",
                                afterId,
                                "limit",
                                limit));
        long last = afterId;
        for (Map<String, Object> row : rows) {
            last = ((Number) row.get("analysis_id")).longValue();
            Object id = row.get("id");
            tx.run(
                    () -> {
                        Map<String, Object> call =
                                store.one(
                                        "callsession",
                                        "lockById",
                                        JsonSupport.map(
                                                "tenantId",
                                                tenant,
                                                "id",
                                                store.encode("callsession", "id", id)));
                        Map<String, Object> analysis =
                                call == null
                                        ? null
                                        : store.one(
                                                "callanalysis",
                                                "scopedCallAnalysis17",
                                                Store.scopedParameters(
                                                        tenant,
                                                        store.encode(
                                                                "callanalysis",
                                                                "call_session_id",
                                                                call.get("id"))));
                        if (analysis != null) sync(call, analysis);
                        return null;
                    });
        }
        return JsonSupport.map(
                "processed", rows.size(), "last_id", last, "has_more", rows.size() == limit);
    }
}
