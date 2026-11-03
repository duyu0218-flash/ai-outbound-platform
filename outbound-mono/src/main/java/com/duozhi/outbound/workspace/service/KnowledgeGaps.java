package com.duozhi.outbound.workspace.service;

import static com.duozhi.outbound.workspace.service.WorkspaceDocuments.*;

import com.duozhi.outbound.auth.domain.Identity;
import com.duozhi.outbound.common.dao.Store;
import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.common.support.JsonSupport;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.stereotype.Service;

/**
 * Evidence-backed candidates only: a miss is never automatically promoted to approved knowledge.
 */
@Service
public class KnowledgeGaps {
    private final WorkspaceDocuments docs;
    private final KnowledgeDrafts entries;

    public KnowledgeGaps(WorkspaceDocuments docs, KnowledgeDrafts entries) {
        this.docs = docs;
        this.entries = entries;
    }

    public static String sha(String value) {
        return sha(value.getBytes(StandardCharsets.UTF_8));
    }

    public static String sha(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public void capture(
            long tenant,
            String type,
            String source,
            Object campaign,
            String question,
            Map<String, Object> context) {
        if (!Set.of("call", "debug", "manual").contains(type)
                || source == null
                || source.length() > 64) throw new ApiError(422, "问题来源不正确");
        String text = question.trim();
        if (text.isBlank()) return;
        text = text.substring(0, Math.min(2000, text.length()));
        String payload = docs.json.json(context);
        if (payload.length() > 20000) throw new ApiError(422, "问题来源信息过长");
        docs.jdbc.update(
                "INSERT INTO platform_knowledge_gap(tenant_id,id,source_type,source_id,question_sha,question,campaign_id,context_json,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE id=id",
                tenant,
                UUID.randomUUID().toString(),
                type,
                source,
                sha(text),
                text,
                campaign,
                payload,
                Store.now(),
                Store.now());
    }

    public Map<String, Object> require(long tenant, String id) {
        var row =
                docs.one(
                        "SELECT * FROM platform_knowledge_gap WHERE tenant_id=? AND id=?",
                        tenant,
                        id);
        if (row == null) throw new ApiError(404, "待整理问题不存在");
        return output(row);
    }

    private Map<String, Object> output(Map<String, Object> row) {
        var out = new LinkedHashMap<>(row);
        out.put("context", docs.json.object(out.remove("context_json").toString()));
        return out;
    }

    public Object list(
            long tenant, String state, String source, String keyword, int page, int size) {
        if (!Set.of("pending", "adopted", "dismissed", "all").contains(state)
                || !Set.of("call", "debug", "manual", "all").contains(source))
            throw new ApiError(422, "问题筛选状态不正确");
        int offset = com.duozhi.outbound.common.web.Contract.page(page, size);
        String where =
                "tenant_id=? AND (?='all' OR state=?) AND (?='all' OR source_type=?) AND question LIKE ?";
        Object[] args = {tenant, state, state, source, source, "%" + keyword + "%"};
        var values = new ArrayList<>(Arrays.asList(args));
        values.add(size);
        values.add(offset);
        var rows =
                docs.jdbc.queryForList(
                        "SELECT * FROM platform_knowledge_gap WHERE "
                                + where
                                + " ORDER BY created_at DESC,id LIMIT ? OFFSET ?",
                        values.toArray());
        return JsonSupport.map(
                "rows",
                rows.stream().map(this::output).toList(),
                "total",
                docs.jdbc.queryForObject(
                        "SELECT COUNT(*) FROM platform_knowledge_gap WHERE " + where,
                        Long.class,
                        args));
    }

    public Object adopt(Identity actor, String id, Map<String, Object> body) {
        if (!Boolean.TRUE.equals(body.get("confirmed"))) throw new ApiError(422, "请先人工核对问题来源与标准答案");
        String library = text(body, "library_id", 64, true);
        var value = entries.normalize(map(body.get("draft")));
        String hash = sha(library + "\n" + docs.json.json(value));
        return docs.tx.serialized(
                () -> {
                    var row = require(actor.tenant(), id);
                    if ("adopted".equals(row.get("state"))) {
                        if (!library.equals(row.get("library_id"))
                                || !hash.equals(row.get("resolution_sha")))
                            throw new ApiError(409, "该问题已按其他答案整理");
                        return JsonSupport.map(
                                "item_id",
                                row.get("item_id"),
                                "library_id",
                                library,
                                "replayed",
                                true);
                    }
                    if (!"pending".equals(row.get("state"))
                            || number(body.get("expected_revision")) != number(row.get("revision")))
                        throw new ApiError(409, "问题状态已变化，请刷新");
                    String item = UUID.randomUUID().toString();
                    entries.save(actor, library, item, value, 0);
                    docs.jdbc.update(
                            "UPDATE platform_knowledge_gap SET state='adopted',revision=revision+1,library_id=?,item_id=?,resolution=?,resolution_sha=?,resolved_by=?,updated_at=? WHERE tenant_id=? AND id=?",
                            library,
                            item,
                            "人工核对后生成知识草稿，尚未发布",
                            hash,
                            actor.userId(),
                            Store.now(),
                            actor.tenant(),
                            id);
                    docs.recordTransition(actor, "knowledge_gap", id, "adopted_as_draft");
                    return JsonSupport.map(
                            "item_id", item, "library_id", library, "replayed", false);
                });
    }

    public Object transition(Identity actor, String id, Map<String, Object> body, boolean reopen) {
        String note = text(body, "reason", 2000, true);
        return docs.tx.serialized(
                () -> {
                    var row = require(actor.tenant(), id);
                    String prior = reopen ? "dismissed" : "pending";
                    if (!prior.equals(row.get("state"))
                            || number(row.get("revision")) != number(body.get("expected_revision")))
                        throw new ApiError(409, "问题状态已变化，请刷新");
                    String next = reopen ? "pending" : "dismissed";
                    docs.jdbc.update(
                            "UPDATE platform_knowledge_gap SET state=?,revision=revision+1,resolution=?,resolved_by=?,updated_at=? WHERE tenant_id=? AND id=?",
                            next,
                            note,
                            actor.userId(),
                            Store.now(),
                            actor.tenant(),
                            id);
                    docs.recordTransition(actor, "knowledge_gap", id, next);
                    return require(actor.tenant(), id);
                });
    }
}
