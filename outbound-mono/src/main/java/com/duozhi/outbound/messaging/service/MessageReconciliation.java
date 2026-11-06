package com.duozhi.outbound.messaging.service;

import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.common.service.Transactions;
import com.duozhi.outbound.integration.support.Support;
import com.duozhi.outbound.messaging.dao.MessageStore;
import java.util.*;
import org.springframework.stereotype.Service;

/** Supplier bills are immutable evidence. Differences never silently alter customer balances. */
@Service
public class MessageReconciliation {
    private final MessageStore db;
    private final Transactions tx;
    private final MessageChannels channels;

    public MessageReconciliation(MessageStore db, Transactions tx, MessageChannels channels) {
        this.db = db;
        this.tx = tx;
        this.channels = channels;
    }

    public Object create(long tenant, Map<String, Object> input) {
        String channel = Messaging.required(input, "channel_id", 64),
                reference = Messaging.required(input, "external_ref", 128);
        if (!reference.matches("[A-Za-z0-9_.:/-]{1,128}")) throw new ApiError(422, "账单编号格式无效");
        if (!(input.get("items") instanceof List<?> items)
                || items.isEmpty()
                || items.size() > 1000) throw new ApiError(422, "单次对账须包含 1 至 1000 条记录");
        if (Support.json(input).length > 512 * 1024) throw new ApiError(413, "账单过大");
        String hash = Support.hex(Support.sha(Support.canonical(input)));
        return tx.run(
                () -> {
                    db.one("SELECT id FROM tenant WHERE id=? FOR UPDATE", tenant);
                    channels.internal(tenant, channel);
                    var prior =
                            db.one(
                                    "SELECT id,request_hash FROM msg_reconcile_batch WHERE tenant_id=? AND channel_id=? AND external_ref=?",
                                    tenant,
                                    channel,
                                    reference);
                    if (prior != null) {
                        if (!hash.equals(prior.get("request_hash")))
                            throw new ApiError(409, "同一账单编号对应不同内容");
                        return get(tenant, prior.get("id").toString());
                    }
                    String id = Messaging.id("bill");
                    long total = 0;
                    var rows = new ArrayList<Map<String, Object>>();
                    var seen = new HashSet<String>();
                    for (Object item : items) {
                        var entry = Messaging.object(item);
                        String provider = Messaging.required(entry, "provider_id", 128);
                        if (!seen.add(provider)) throw new ApiError(422, "账单内供应商消息编号重复");
                        long billed = Messaging.number(entry, "amount", 0, 1000000000000L);
                        total = Math.addExact(total, billed);
                        var message =
                                db.one(
                                        "SELECT m.id,IF(EXISTS(SELECT 1 FROM msg_cost_event c WHERE c.message_id=m.id),m.purchase_amount,NULL) AS purchase_amount FROM msg_message m JOIN msg_delivery d ON d.message_id=m.id WHERE m.tenant_id=? AND d.channel_id=? AND d.provider_id=?",
                                        tenant,
                                        channel,
                                        provider);
                        // CMPP bills can identify an individual segment; aggregate recorded cost is
                        // compared only for single-part messages.
                        if (message == null)
                            message =
                                    db.one(
                                            "SELECT m.id,IF(d.part_count=1 AND EXISTS(SELECT 1 FROM msg_cost_event c WHERE c.message_id=m.id),m.purchase_amount,NULL) AS purchase_amount FROM msg_message m JOIN msg_delivery d ON d.message_id=m.id JOIN msg_delivery_part p ON p.message_id=m.id WHERE m.tenant_id=? AND p.channel_id=? AND p.provider_id=?",
                                            tenant,
                                            channel,
                                            provider);
                        var r = new LinkedHashMap<String, Object>();
                        r.put("provider_id", provider);
                        r.put("billed", billed);
                        r.put("message_id", message == null ? null : message.get("id"));
                        r.put("recorded", message == null ? null : message.get("purchase_amount"));
                        r.put(
                                "state",
                                message == null
                                        ? "UNMATCHED"
                                        : message.get("purchase_amount") == null
                                                ? "SEGMENT_REVIEW"
                                                : ((Number) message.get("purchase_amount"))
                                                                        .longValue()
                                                                == billed
                                                        ? "MATCHED"
                                                        : "DIFFERENCE");
                        rows.add(r);
                    }
                    db.jdbc()
                            .update(
                                    "INSERT INTO msg_reconcile_batch(id,tenant_id,channel_id,external_ref,request_hash,total_amount,item_count) VALUES(?,?,?,?,?,?,?)",
                                    id,
                                    tenant,
                                    channel,
                                    reference,
                                    hash,
                                    total,
                                    rows.size());
                    for (int i = 0; i < rows.size(); i++) {
                        var r = rows.get(i);
                        db.jdbc()
                                .update(
                                        "INSERT INTO msg_reconcile_item(batch_id,item_no,message_id,provider_id,billed_amount,recorded_amount,state) VALUES(?,?,?,?,?,?,?)",
                                        id,
                                        i + 1,
                                        r.get("message_id"),
                                        r.get("provider_id"),
                                        r.get("billed"),
                                        r.get("recorded"),
                                        r.get("state"));
                    }
                    return get(tenant, id);
                });
    }

    public Object list(long tenant) {
        return db.jdbc()
                .queryForList(
                        "SELECT id,channel_id,external_ref,total_amount,item_count,created_at FROM msg_reconcile_batch WHERE tenant_id=? ORDER BY created_at DESC LIMIT 100",
                        tenant);
    }

    public Object get(long tenant, String id) {
        var row =
                db.one(
                        "SELECT id,channel_id,external_ref,total_amount,item_count,created_at FROM msg_reconcile_batch WHERE tenant_id=? AND id=?",
                        tenant,
                        id);
        if (row == null) throw new ApiError(404, "账单不存在");
        row.put(
                "items",
                db.jdbc()
                        .queryForList(
                                "SELECT item_no,message_id,provider_id,billed_amount,recorded_amount,state FROM msg_reconcile_item WHERE batch_id=? ORDER BY item_no",
                                id));
        return row;
    }
}
