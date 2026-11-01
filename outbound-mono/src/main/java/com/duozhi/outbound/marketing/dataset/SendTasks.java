package com.duozhi.outbound.marketing.dataset;

import com.duozhi.outbound.campaign.controller.Campaigns;
import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.common.support.JsonSupport;
import com.duozhi.outbound.marketing.common.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Bulk "AI call task" or "SMS task" from a frozen snapshot. Members are claimed in steps and sent
 * one by one through {@link ChannelSender}; control-group members are held out. Every member ends
 * as accepted, rejected (with the admission or execution reason) or held_out.
 */
@Service
public class SendTasks {
    static final int STEP = 100;

    private final MarketingStore db;
    private final Segments segments;
    private final ChannelSender sender;
    private final SnapshotChannelSender links;
    private final Campaigns campaigns;
    private final JsonSupport json;
    private final TransactionTemplate tx;

    public SendTasks(
            MarketingStore db,
            Segments segments,
            ChannelSender sender,
            SnapshotChannelSender links,
            Campaigns campaigns,
            JsonSupport json,
            TransactionTemplate tx) {
        this.db = db;
        this.segments = segments;
        this.sender = sender;
        this.links = links;
        this.campaigns = campaigns;
        this.json = json;
        this.tx = tx;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> create(
            long tenant, Map<String, Object> input, HttpServletRequest request, long actor) {
        String snapshotId = Matching.str(input.get("snapshotId"));
        var snapshot = segments.snapshot(tenant, snapshotId);
        String channel = Matching.str(input.get("channel"));
        Map<String, Object> config = new LinkedHashMap<>();
        Long campaignId = null;
        if (OutcomeCodes.CALL.equals(channel)) {
            if (input.get("newCampaign") instanceof Map<?, ?> fresh) {
                Map<String, Object> body = new LinkedHashMap<>((Map<String, Object>) fresh);
                body.putIfAbsent("mode", "ai_only");
                body.putIfAbsent("name", snapshot.get("name"));
                body.remove("contact_ids");
                // The existing campaign API creates it (validation, contract and audit included).
                var created = campaigns.create(body, request);
                campaignId = ((Number) created.get("id")).longValue();
                config.put("created_campaign", true);
            } else {
                if (input.get("campaignId") == null) throw new ApiError(422, "请选择外呼任务或新建外呼任务");
                campaignId = Long.parseLong(input.get("campaignId").toString());
                var c =
                        db.one(
                                "SELECT id,status FROM campaign WHERE tenant_id=? AND id=?",
                                tenant,
                                campaignId);
                if (c == null || "deleted".equals(c.get("status")))
                    throw new ApiError(404, "外呼任务不存在");
            }
        } else if (OutcomeCodes.SMS.equals(channel)) {
            var sms =
                    input.get("sms") instanceof Map<?, ?> m
                            ? (Map<String, Object>) m
                            : Map.<String, Object>of();
            for (String k : List.of("productId", "templateId", "senderId"))
                if (Matching.str(sms.get(k)).isEmpty()) throw new ApiError(422, "请选择短信产品、模板和发送主体");
            if (!Matching.str(sms.get("landingUrl")).isEmpty() && !links.linksAvailable())
                throw new ApiError(503, "短链模块未接入");
            for (String k :
                    List.of(
                            "applicationId",
                            "productId",
                            "templateId",
                            "senderId",
                            "landingUrl",
                            "domainPool"))
                if (!Matching.str(sms.get(k)).isEmpty()) config.put(k, Matching.str(sms.get(k)));
            config.put("variables", sms.get("variables") instanceof Map<?, ?> v ? v : Map.of());
        } else throw new ApiError(422, "渠道只能是 call 或 sms");
        String id = MarketingStore.id("st");
        db.write(
                "INSERT INTO mk_send_task(id,tenant_id,snapshot_id,channel,campaign_id,config,state,total,created_by) VALUES(?,?,?,?,?,?,'running',?,?)",
                id,
                tenant,
                snapshotId,
                channel,
                campaignId,
                json.json(config),
                segments.size(tenant, snapshotId),
                actor);
        return task(tenant, id);
    }

    public Map<String, Object> task(long tenant, String id) {
        var row = db.one("SELECT * FROM mk_send_task WHERE tenant_id=? AND id=?", tenant, id);
        if (row == null) throw new ApiError(404, "发送任务不存在");
        var out = new LinkedHashMap<>(row);
        out.put(
                "reasons",
                db.rows(
                        "SELECT reason,COUNT(*) AS count FROM mk_send_item WHERE tenant_id=? AND task_id=? AND state='rejected' GROUP BY reason ORDER BY count DESC",
                        tenant,
                        id));
        if (row.get("campaign_id") != null)
            out.put(
                    "campaign",
                    db.one(
                            "SELECT id,name,status FROM campaign WHERE tenant_id=? AND id=?",
                            tenant,
                            row.get("campaign_id")));
        return out;
    }

    public List<Map<String, Object>> tasks(long tenant, String snapshotId) {
        return snapshotId == null
                ? db.rows(
                        "SELECT * FROM mk_send_task WHERE tenant_id=? ORDER BY created_at DESC LIMIT 200",
                        tenant)
                : db.rows(
                        "SELECT * FROM mk_send_task WHERE tenant_id=? AND snapshot_id=? ORDER BY created_at DESC LIMIT 200",
                        tenant,
                        snapshotId);
    }

    public List<Map<String, Object>> items(long tenant, String id, String state, int page) {
        task(tenant, id);
        return db.rows(
                "SELECT sequence,phone_mask,state,reason,target_id,updated_at FROM mk_send_item WHERE tenant_id=? AND task_id=?"
                        + (state == null || state.isBlank() ? "" : " AND state=?")
                        + " ORDER BY sequence LIMIT 100 OFFSET ?",
                state == null || state.isBlank()
                        ? new Object[] {tenant, id, Math.max(0, page - 1) * 100}
                        : new Object[] {tenant, id, state, Math.max(0, page - 1) * 100});
    }

    public Map<String, Object> cancel(long tenant, String id) {
        task(tenant, id);
        db.write(
                "UPDATE mk_send_task SET state='cancelled',finished_at=NOW(6) WHERE tenant_id=? AND id=? AND state='running'",
                tenant,
                id);
        return task(tenant, id);
    }

    public Map<String, Object> run(long tenant, String id, int steps) {
        task(tenant, id);
        for (int i = 0; i < steps && step(id); i++) {}
        return task(tenant, id);
    }

    record Claim(long tenant, Map<String, Object> task, List<Long> sequences) {}

    /** Single step: claims and sends up to {@value #STEP} members of one running task. */
    public boolean step(String taskId) {
        Claim claim =
                tx.execute(
                        s -> {
                            // Worker scan across tenants; the claimed task binds its tenant_id
                            // below.
                            var task =
                                    taskId == null
                                            ? db.one(
                                                    "SELECT * FROM mk_send_task WHERE state='running' ORDER BY created_at LIMIT 1 FOR UPDATE SKIP LOCKED")
                                            : db.one(
                                                    "SELECT * FROM mk_send_task WHERE id=? AND state='running' FOR UPDATE SKIP LOCKED",
                                                    taskId);
                            if (task == null) return null;
                            long tenant = ((Number) task.get("tenant_id")).longValue();
                            String id = (String) task.get("id");
                            // Claims left behind by an interrupted step; re-sending them is
                            // idempotent.
                            List<Long> seqs = new ArrayList<>();
                            for (var r :
                                    db.rows(
                                            "SELECT sequence FROM mk_send_item WHERE task_id=? AND state='claimed' AND updated_at < NOW(6) - INTERVAL 2 MINUTE ORDER BY sequence LIMIT ?",
                                            id,
                                            STEP))
                                seqs.add(((Number) r.get("sequence")).longValue());
                            if (seqs.isEmpty()) {
                                long cursor = ((Number) task.get("cursor_sequence")).longValue();
                                var members =
                                        db.rows(
                                                "SELECT sequence,phone_mask FROM mk_snapshot_member WHERE tenant_id=? AND snapshot_id=? AND sequence>? ORDER BY sequence LIMIT ?",
                                                tenant,
                                                task.get("snapshot_id"),
                                                cursor,
                                                STEP);
                                if (members.isEmpty()) {
                                    if (db.count(
                                                    "SELECT COUNT(*) FROM mk_send_item WHERE task_id=? AND state='claimed'",
                                                    id)
                                            == 0)
                                        db.write(
                                                "UPDATE mk_send_task SET state='finished',finished_at=NOW(6) WHERE tenant_id=? AND id=?",
                                                tenant,
                                                id);
                                    return null;
                                }
                                for (var m : members) {
                                    long seq = ((Number) m.get("sequence")).longValue();
                                    db.write(
                                            "INSERT IGNORE INTO mk_send_item(task_id,sequence,tenant_id,phone_mask,state) VALUES(?,?,?,?,'claimed')",
                                            id,
                                            seq,
                                            tenant,
                                            m.get("phone_mask"));
                                    seqs.add(seq);
                                }
                                db.write(
                                        "UPDATE mk_send_task SET cursor_sequence=? WHERE tenant_id=? AND id=?",
                                        seqs.getLast(),
                                        tenant,
                                        id);
                            } else {
                                db.write(
                                        "UPDATE mk_send_item SET updated_at=NOW(6) WHERE task_id=? AND sequence IN ("
                                                + String.join(
                                                        ",", Collections.nCopies(seqs.size(), "?"))
                                                + ")",
                                        concat(id, seqs));
                            }
                            return new Claim(tenant, task, seqs);
                        });
        if (claim == null) return false;
        send(claim);
        return true;
    }

    private static Object[] concat(Object first, List<Long> rest) {
        List<Object> all = new ArrayList<>();
        all.add(first);
        all.addAll(rest);
        return all.toArray();
    }

    @SuppressWarnings("unchecked")
    private void send(Claim claim) {
        long tenant = claim.tenant();
        var task = claim.task();
        String id = (String) task.get("id"),
                snapshot = (String) task.get("snapshot_id"),
                channel = (String) task.get("channel");
        Map<String, Object> config = json.object(String.valueOf(task.get("config")));
        var origin = new ChannelSender.Origin(snapshot, null, null);
        List<Object> args = new ArrayList<>(List.of(tenant, snapshot));
        args.addAll(claim.sequences());
        var members =
                db.rows(
                        "SELECT sequence,phone_hash,phone_cipher,contact_id,grp FROM mk_snapshot_member WHERE tenant_id=? AND snapshot_id=? AND sequence IN ("
                                + String.join(
                                        ",", Collections.nCopies(claim.sequences().size(), "?"))
                                + ") ORDER BY sequence",
                        args.toArray());
        for (var m : members) {
            long seq = ((Number) m.get("sequence")).longValue();
            if ("control".equals(m.get("grp"))) {
                finishItem(id, seq, "held_out", "对照组（按 A/B 设计不触达）", null);
                continue;
            }
            var recipient =
                    new ChannelSender.Recipient(
                            (String) m.get("phone_hash"),
                            (String) m.get("phone_cipher"),
                            m.get("contact_id") == null
                                    ? null
                                    : ((Number) m.get("contact_id")).longValue());
            ChannelSender.Result result;
            try {
                result =
                        OutcomeCodes.CALL.equals(channel)
                                ? sender.call(
                                        tenant,
                                        origin,
                                        recipient,
                                        new ChannelSender.Call(
                                                ((Number) task.get("campaign_id")).longValue()))
                                : sender.sms(
                                        tenant,
                                        origin,
                                        recipient,
                                        new ChannelSender.Sms(
                                                (String) config.get("applicationId"),
                                                (String) config.get("productId"),
                                                (String) config.get("templateId"),
                                                (String) config.get("senderId"),
                                                (Map<String, Object>)
                                                        config.getOrDefault("variables", Map.of()),
                                                (String) config.get("landingUrl"),
                                                (String) config.get("domainPool")));
            } catch (ApiError e) {
                result = new ChannelSender.Result(false, e.getMessage(), null);
            }
            finishItem(
                    id,
                    seq,
                    result.accepted() ? "accepted" : "rejected",
                    result.accepted() ? "" : trim(result.reason()),
                    result.targetId());
        }
        db.write(
                "UPDATE mk_send_task t SET processed=(SELECT COUNT(*) FROM mk_send_item i WHERE i.task_id=t.id AND i.state<>'claimed'),"
                        + "accepted=(SELECT COUNT(*) FROM mk_send_item i WHERE i.task_id=t.id AND i.state='accepted'),"
                        + "rejected=(SELECT COUNT(*) FROM mk_send_item i WHERE i.task_id=t.id AND i.state='rejected'),"
                        + "held_out=(SELECT COUNT(*) FROM mk_send_item i WHERE i.task_id=t.id AND i.state='held_out') WHERE t.tenant_id=? AND t.id=?",
                tenant,
                id);
    }

    private static String trim(String s) {
        if (s == null || s.isBlank()) return "未知原因";
        return s.length() > 255 ? s.substring(0, 255) : s;
    }

    private void finishItem(String task, long seq, String state, String reason, String target) {
        db.write(
                "UPDATE mk_send_item SET state=?,reason=?,target_id=? WHERE task_id=? AND sequence=? AND state='claimed'",
                state,
                reason,
                target,
                task,
                seq);
    }

    public Map<String, Object> options(long tenant) {
        return JsonSupport.map(
                "campaigns",
                db.rows(
                        "SELECT id,name,status,mode FROM campaign WHERE tenant_id=? AND status<>'deleted' ORDER BY id DESC LIMIT 200",
                        tenant),
                "script_templates",
                db.rows(
                        "SELECT id,name FROM scripttemplate WHERE tenant_id=? AND is_active=TRUE ORDER BY id DESC LIMIT 200",
                        tenant),
                "flow_versions",
                db.rows(
                        "SELECT id,script_template_id,name,version FROM scriptflowversion WHERE tenant_id=? AND LOWER(status)='published' ORDER BY id DESC LIMIT 200",
                        tenant),
                "sms_resources",
                db.rows(
                        "SELECT id,kind,name FROM msg_resource WHERE tenant_id=? AND kind IN ('product','template','sender') AND state='APPROVED' ORDER BY created_at DESC LIMIT 300",
                        tenant),
                "sms_applications",
                db.rows(
                        "SELECT id,name FROM msg_application WHERE tenant_id=? AND enabled=TRUE ORDER BY created_at DESC LIMIT 100",
                        tenant),
                "links_available",
                links.linksAvailable());
    }
}
