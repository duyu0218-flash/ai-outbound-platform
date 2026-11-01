package com.duozhi.outbound.marketing.dataset;

import com.duozhi.outbound.common.dao.Store;
import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.common.support.JsonSupport;
import com.duozhi.outbound.common.support.Values;
import com.duozhi.outbound.marketing.common.*;
import com.duozhi.outbound.messaging.service.Messaging;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The marketing send path on top of the existing execution layer. Each send runs admission ({@link
 * ContactPolicy}, which the journey module overrides with a {@code @Primary} policy), creates the
 * message through {@link Messaging#create} or adds the recipient to an AI campaign through the
 * contact / campaigncontact / callsession records, then registers the dispatch. The phone is
 * decrypted only inside this call.
 */
@Service
public class SnapshotChannelSender implements ChannelSender {
    private final MarketingStore db;
    private final Store store;
    private final PhoneIdentity phones;
    private final ContactPolicy policy;
    private final Attribution attribution;
    private final Messaging messaging;
    private final ObjectProvider<LinkMinter> links;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;

    public SnapshotChannelSender(
            MarketingStore db,
            Store store,
            PhoneIdentity phones,
            ContactPolicy policy,
            Attribution attribution,
            Messaging messaging,
            ObjectProvider<LinkMinter> links,
            ObjectMapper mapper,
            TransactionTemplate tx) {
        this.db = db;
        this.store = store;
        this.phones = phones;
        this.policy = policy;
        this.attribution = attribution;
        this.messaging = messaging;
        this.links = links;
        this.mapper = mapper;
        this.tx = tx;
    }

    public boolean linksAvailable() {
        return links.getIfAvailable() != null;
    }

    private static Result reject(String reason) {
        return new Result(false, reason, null);
    }

    private static String local(String e164) {
        return e164.startsWith("+86") ? e164.substring(3) : e164;
    }

    /**
     * Finds the tenant contact for this number or creates one. A new contact's consent rests on the
     * data package authorization that admitted the number into the snapshot.
     */
    long contact(long tenant, String e164, Origin origin) {
        String local = local(e164);
        var existing =
                db.one(
                        "SELECT id FROM contact WHERE tenant_id=? AND phone IN (?,?,?) ORDER BY id LIMIT 1",
                        tenant,
                        local,
                        e164,
                        e164.substring(1));
        if (existing != null) return ((Number) existing.get("id")).longValue();
        String evidence =
                "marketing:"
                        + (origin == null
                                ? "direct"
                                : origin.snapshotId() != null
                                        ? origin.snapshotId()
                                        : String.valueOf(origin.journeyId()));
        try {
            return ((Number)
                            store.insert(
                                            "contact",
                                            Values.map(
                                                    "tenant_id",
                                                    tenant,
                                                    "phone",
                                                    e164.startsWith("+86") ? local : e164,
                                                    "consent_state",
                                                    "consented",
                                                    "consented_at",
                                                    Store.now(),
                                                    "consented_by",
                                                    evidence.length() > 128
                                                            ? evidence.substring(0, 128)
                                                            : evidence))
                                    .get("id"))
                    .longValue();
        } catch (DuplicateKeyException race) {
            var row =
                    db.one(
                            "SELECT id FROM contact WHERE tenant_id=? AND phone IN (?,?,?) ORDER BY id LIMIT 1",
                            tenant,
                            local,
                            e164,
                            e164.substring(1));
            if (row == null) throw race;
            return ((Number) row.get("id")).longValue();
        }
    }

    private String key(long tenant, Origin origin, String suffix, String hash) {
        String o =
                origin == null
                        ? "|||"
                        : origin.snapshotId()
                                + "|"
                                + origin.journeyId()
                                + "|"
                                + origin.journeyMemberId();
        return "mk-" + JsonSupport.sha(tenant + "|" + o + "|" + suffix + "|" + hash);
    }

    @Override
    public Result sms(long tenant, Origin origin, Recipient recipient, Sms sms) {
        String e164 = phones.decrypt(tenant, recipient.phoneCipher());
        String hash =
                recipient.phoneHash() != null ? recipient.phoneHash() : phones.hash(tenant, e164);
        var admission = policy.admit(tenant, e164, hash, OutcomeCodes.SMS);
        if (!admission.allowed()) return reject(admission.reason());
        boolean link = sms.landingUrl() != null && !sms.landingUrl().isBlank();
        LinkMinter minter = link ? links.getIfAvailable() : null;
        if (link && minter == null) return reject("短链模块未接入");
        String application =
                sms.applicationId() == null || sms.applicationId().isBlank()
                        ? "console"
                        : sms.applicationId();
        String key = key(tenant, origin, "sms|" + sms.templateId() + "|" + sms.landingUrl(), hash);
        var existing =
                db.one(
                        "SELECT id,contact_id FROM msg_message WHERE tenant_id=? AND application_id=? AND idempotency_key=?",
                        tenant,
                        application,
                        key);
        if (existing != null) {
            Long contactId =
                    existing.get("contact_id") == null
                            ? null
                            : ((Number) existing.get("contact_id")).longValue();
            register(
                    tenant,
                    OutcomeCodes.SMS,
                    Attribution.MESSAGE,
                    (String) existing.get("id"),
                    hash,
                    contactId,
                    origin);
            return new Result(true, "", (String) existing.get("id"));
        }
        long contactId = tx.execute(s -> contact(tenant, e164, origin));
        Map<String, Object> variables =
                new LinkedHashMap<>(sms.variables() == null ? Map.of() : sms.variables());
        LinkMinter.ShortLink shortLink = null;
        if (link) {
            String body = templateBody(tenant, sms.templateId());
            if (body == null) return reject("短信模板不存在");
            if (!body.contains("{{link}}")
                    && variables.values().stream()
                            .noneMatch(v -> String.valueOf(v).contains("{link}")))
                return reject("短信模板缺少 {link} 占位符");
            try {
                shortLink =
                        minter.mint(
                                tenant,
                                sms.domainPool(),
                                sms.landingUrl(),
                                hash,
                                contactId,
                                origin);
            } catch (ApiError e) {
                return reject("短链生成失败：" + e.getMessage());
            }
            String url = shortLink.url();
            variables.replaceAll((k, v) -> v instanceof String s ? s.replace("{link}", url) : v);
            if (body.contains("{{link}}")) variables.put("link", url);
        }
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("service_product_id", sms.productId());
        request.put("sender_identity_id", sms.senderId());
        request.put("template_version_id", sms.templateId());
        request.put("recipient", Map.of("type", "phone", "value", e164));
        request.put("consent_reference", String.valueOf(contactId));
        request.put("variables", variables);
        request.put("purpose", "marketing");
        request.put(
                "channel_policy",
                Map.of(
                        "mode",
                        "automatic",
                        "allowed_forms",
                        List.of("sms", "rcs", "yuexin", "oem_ip"),
                        "allow_fallback",
                        false));
        final LinkMinter.ShortLink minted = shortLink;
        String messageId;
        try {
            // One transaction: the dispatch exists before any worker can see the queued message.
            messageId =
                    tx.execute(
                            s -> {
                                var message =
                                        messaging.create(
                                                new Messaging.Principal(tenant, application),
                                                key,
                                                request);
                                String created = String.valueOf(message.get("id"));
                                register(
                                        tenant,
                                        OutcomeCodes.SMS,
                                        Attribution.MESSAGE,
                                        created,
                                        hash,
                                        contactId,
                                        origin);
                                if (minted != null)
                                    minter.bindMessage(tenant, minted.code(), created);
                                return created;
                            });
        } catch (ApiError e) {
            return reject(e.getMessage());
        }
        return new Result(true, "", messageId);
    }

    private String templateBody(long tenant, String templateId) {
        var row =
                db.one(
                        "SELECT payload FROM msg_resource WHERE tenant_id=? AND id=? AND kind='template'",
                        tenant,
                        templateId);
        if (row == null) return null;
        try {
            JsonNode payload = mapper.readTree(String.valueOf(row.get("payload")));
            return payload.path("body").asText("");
        } catch (Exception e) {
            return null;
        }
    }

    private void register(
            long tenant,
            String channel,
            String type,
            String target,
            String hash,
            Long contactId,
            Origin origin) {
        attribution.register(
                tenant,
                channel,
                type,
                target,
                hash,
                contactId,
                origin == null ? null : origin.snapshotId(),
                origin == null ? null : origin.journeyId(),
                origin == null ? null : origin.journeyMemberId());
    }

    @Override
    public Result call(long tenant, Origin origin, Recipient recipient, Call call) {
        String e164 = phones.decrypt(tenant, recipient.phoneCipher());
        String hash =
                recipient.phoneHash() != null ? recipient.phoneHash() : phones.hash(tenant, e164);
        var admission = policy.admit(tenant, e164, hash, OutcomeCodes.CALL);
        if (!admission.allowed()) return reject(admission.reason());
        // The dispatch is registered in the same transaction, before the call can be dialed.
        Added added =
                tx.execute(
                        s -> {
                            Added a = addToCampaign(tenant, e164, origin, call);
                            if (a.error() == null)
                                register(
                                        tenant,
                                        OutcomeCodes.CALL,
                                        Attribution.CALL,
                                        a.callId(),
                                        hash,
                                        a.contactId(),
                                        origin);
                            return a;
                        });
        if (added.error() != null) return reject(added.error());
        return new Result(true, "", added.callId());
    }

    record Added(String callId, long contactId, String error) {}

    private Added addToCampaign(long tenant, String e164, Origin origin, Call call) {
        return tx.execute(
                s -> {
                    // Serializes membership changes of this campaign (same row Campaigns
                    // locks).
                    if (db.one(
                                    "SELECT id FROM campaign WHERE tenant_id=? AND id=? FOR UPDATE",
                                    tenant,
                                    call.campaignId())
                            == null) return new Added(null, 0, "外呼任务不存在");
                    var campaign = store.require("campaign", tenant, call.campaignId());
                    String status = String.valueOf(campaign.get("status"));
                    if ("deleted".equals(status)) return new Added(null, 0, "外呼任务已删除");
                    long contactId = contact(tenant, e164, origin);
                    var contactRow =
                            db.one(
                                    "SELECT dnc,consent_state FROM contact WHERE tenant_id=? AND id=?",
                                    tenant,
                                    contactId);
                    if (Boolean.TRUE.equals(contactRow.get("dnc"))
                            || "REVOKED".equals(contactRow.get("consent_state")))
                        return new Added(null, contactId, "联系人拒绝联系");
                    // campaigncontact has no tenant_id; the campaign row above is
                    // tenant-checked.
                    if (db.one(
                                    "SELECT id FROM campaigncontact WHERE campaign_id=? AND contact_id=?",
                                    call.campaignId(),
                                    contactId)
                            == null) {
                        long order =
                                db.count(
                                        "SELECT COALESCE(MAX(contact_order)+1,0) FROM campaigncontact WHERE campaign_id=?",
                                        call.campaignId());
                        store.insert(
                                "campaigncontact",
                                Values.map(
                                        "campaign_id",
                                        call.campaignId(),
                                        "contact_id",
                                        contactId,
                                        "contact_order",
                                        order));
                    }
                    String contactKey = "campaign:" + call.campaignId() + ":contact:" + contactId;
                    var existing =
                            db.one(
                                    "SELECT id FROM callsession WHERE tenant_id=? AND campaign_contact_key=?",
                                    tenant,
                                    contactKey);
                    if (existing != null)
                        return new Added(String.valueOf(existing.get("id")), contactId, null);
                    Map<String, Object> values = new LinkedHashMap<>();
                    values.put("tenant_id", tenant);
                    values.put("campaign_id", call.campaignId());
                    values.put("contact_id", contactId);
                    values.put("campaign_contact_key", contactKey);
                    values.put("phone", e164.startsWith("+86") ? local(e164) : e164.substring(1));
                    values.put("mode", campaign.get("mode"));
                    // A running campaign dials the new member right away; otherwise the
                    // call waits as "created" and the campaign start picks it up.
                    values.put("status", "running".equals(status) ? "queued" : "created");
                    values.put(
                            "max_attempts", ((Number) campaign.get("retry_limit")).intValue() + 1);
                    values.put("script_flow_version_id", campaign.get("script_flow_version_id"));
                    values.put(
                            "flow_node_key",
                            startNode(tenant, campaign.get("script_flow_version_id")));
                    values.put("voice_ai_pipeline", "pending");
                    return new Added(
                            String.valueOf(store.insert("callsession", values).get("id")),
                            contactId,
                            null);
                });
    }

    /** Same start-node resolution as Campaigns: the published flow's "start" node. */
    private Object startNode(long tenant, Object flowId) {
        if (flowId == null) return null;
        var flow = store.require("scriptflowversion", tenant, flowId);
        try {
            for (JsonNode node : mapper.readTree(flow.get("graph_json").toString()).path("nodes"))
                if ("start".equals(node.path("type").asText())) return node.path("id").asText();
        } catch (Exception e) {
            throw new ApiError(409, "invalid published flow graph");
        }
        throw new ApiError(409, "published flow has no start node");
    }
}
