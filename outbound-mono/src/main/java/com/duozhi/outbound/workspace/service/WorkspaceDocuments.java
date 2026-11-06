package com.duozhi.outbound.workspace.service;

import com.duozhi.outbound.auth.domain.Identity;
import com.duozhi.outbound.common.dao.Store;
import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.common.service.Transactions;
import com.duozhi.outbound.common.support.JsonSupport;
import com.duozhi.outbound.system.service.Audit;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Tenant-bound drafts and immutable publications shared by the business workspaces. */
@Service
public class WorkspaceDocuments {
    public final JdbcTemplate jdbc;
    public final JsonSupport json;
    public final Transactions tx;
    private final Audit audit;

    public WorkspaceDocuments(DataSource source, JsonSupport json, Transactions tx, Audit audit) {
        this.jdbc = new JdbcTemplate(source);
        this.json = json;
        this.tx = tx;
        this.audit = audit;
    }

    public Map<String, Object> one(String sql, Object... args) {
        var rows = jdbc.queryForList(sql, args);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public Map<String, Object> find(long tenant, String kind, String id) {
        var row =
                one(
                        "SELECT * FROM platform_document WHERE tenant_id=? AND kind=? AND object_id=?",
                        tenant,
                        kind,
                        id);
        return row == null ? null : output(row);
    }

    public Map<String, Object> require(long tenant, String kind, String id) {
        var row = find(tenant, kind, id);
        if (row == null) throw new ApiError(404, "业务配置不存在");
        return row;
    }

    Map<String, Object> output(Map<String, Object> row) {
        var out = new LinkedHashMap<>(row);
        out.put("draft", json.object(out.remove("draft_json").toString()));
        Object published = out.remove("published_json");
        out.put("published", published == null ? null : json.object(published.toString()));
        return out;
    }

    public List<Map<String, Object>> list(long tenant, String kind, String parent) {
        return jdbc
                .queryForList(
                        "SELECT * FROM platform_document WHERE tenant_id=? AND kind=? AND (? IS NULL OR parent_id=?) ORDER BY updated_at DESC,object_id LIMIT 1000",
                        tenant,
                        kind,
                        parent,
                        parent)
                .stream()
                .map(this::output)
                .toList();
    }

    /**
     * Growth histories opt into bounded server-side paging instead of the configuration list cap.
     */
    public Map<String, Object> historyPage(long tenant, String kind, Map<String, String> query) {
        int page, size;
        try {
            page = Integer.parseInt(query.getOrDefault("page", "1"));
            size = Integer.parseInt(query.getOrDefault("size", "20"));
        } catch (NumberFormatException invalid) {
            throw new ApiError(422, "分页参数无效");
        }
        if (page < 1 || page > 1000000 || size < 1 || size > 200) throw new ApiError(422, "分页参数无效");
        var args = new ArrayList<Object>();
        args.add(tenant);
        args.add(kind);
        String where = " WHERE tenant_id=? AND kind=?";
        String state = query.getOrDefault("state", "");
        if (!state.isBlank()) {
            if (!state.matches("[a-z_]{1,32}")) throw new ApiError(422, "状态筛选无效");
            where += " AND draft_json->>'$.state'=?";
            args.add(state);
        }
        String request = query.getOrDefault("request_id", "");
        if (!request.isBlank()) {
            if (request.length() > 160) throw new ApiError(422, "请求ID过长");
            where += " AND object_id=?";
            args.add(request);
        }
        var total =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM platform_document" + where,
                        Long.class,
                        args.toArray());
        args.add(size);
        args.add((long) (page - 1) * size);
        var rows =
                jdbc
                        .queryForList(
                                "SELECT * FROM platform_document"
                                        + where
                                        + " ORDER BY updated_at DESC,object_id LIMIT ? OFFSET ?",
                                args.toArray())
                        .stream()
                        .map(this::output)
                        .toList();
        return Map.of(
                "rows", rows, "total", total == null ? 0L : total, "page", page, "size", size);
    }

    public Map<String, Object> save(
            Identity actor,
            String kind,
            String id,
            String parent,
            Map<String, Object> draft,
            int expected) {
        String name = text(draft, "name", 160, true);
        String encoded = json.json(draft);
        if (encoded.length() > 600000) throw new ApiError(422, "内容超过 600000 字符，请拆分业务范围");
        return tx.serialized(
                () -> {
                    var old = find(actor.tenant(), kind, id);
                    if (old == null) {
                        if (expected != 0) throw new ApiError(409, "配置已变化，请刷新后重试");
                        jdbc.update(
                                "INSERT INTO platform_document(tenant_id,kind,object_id,name,parent_id,draft_json,updated_by,updated_at) VALUES(?,?,?,?,?,?,?,?)",
                                actor.tenant(),
                                kind,
                                id,
                                name,
                                parent,
                                encoded,
                                actor.userId(),
                                Store.now());
                    } else {
                        if (number(old.get("revision")) != expected)
                            throw new ApiError(409, "其他人已修改，请刷新后重试");
                        jdbc.update(
                                "UPDATE platform_document SET name=?,parent_id=?,draft_json=?,state='draft',revision=revision+1,updated_by=?,updated_at=? WHERE tenant_id=? AND kind=? AND object_id=?",
                                name,
                                parent,
                                encoded,
                                actor.userId(),
                                Store.now(),
                                actor.tenant(),
                                kind,
                                id);
                    }
                    audit.record(actor, "save_draft", "workspace_" + kind, id, "draft saved");
                    return require(actor.tenant(), kind, id);
                });
    }

    public Map<String, Object> publish(
            Identity actor, String kind, String id, int expected, Map<String, Object> payload) {
        return tx.serialized(
                () -> {
                    var doc = require(actor.tenant(), kind, id);
                    if (number(doc.get("revision")) != expected)
                        throw new ApiError(409, "草稿已变化，请重新检查后发布");
                    int version = number(doc.get("published_version")) + 1;
                    var snapshot = new LinkedHashMap<>(payload);
                    snapshot.put("version", version);
                    String encoded = json.json(snapshot);
                    if (encoded.length() > 600000) throw new ApiError(422, "发布内容过多，请拆分知识库");
                    jdbc.update(
                            "INSERT INTO platform_publication(tenant_id,kind,object_id,version,content_json,published_by,published_at) VALUES(?,?,?,?,?,?,?)",
                            actor.tenant(),
                            kind,
                            id,
                            version,
                            encoded,
                            actor.userId(),
                            Store.now());
                    jdbc.update(
                            "UPDATE platform_document SET published_json=?,published_version=?,state='published',revision=revision+1,updated_by=?,updated_at=? WHERE tenant_id=? AND kind=? AND object_id=?",
                            encoded,
                            version,
                            actor.userId(),
                            Store.now(),
                            actor.tenant(),
                            kind,
                            id);
                    audit.record(actor, "publish", "workspace_" + kind, id, "version=" + version);
                    return require(actor.tenant(), kind, id);
                });
    }

    public void recordTransition(Identity actor, String kind, String id, String state) {
        audit.record(actor, "transition", "workspace_" + kind, id, "state=" + state);
    }

    public List<Map<String, Object>> history(long tenant, String kind, String id) {
        require(tenant, kind, id);
        return jdbc.queryForList(
                "SELECT version,published_by,published_at FROM platform_publication WHERE tenant_id=? AND kind=? AND object_id=? ORDER BY version DESC",
                tenant,
                kind,
                id);
    }

    public Map<String, Object> revision(long tenant, String kind, String id, int version) {
        var row =
                one(
                        "SELECT content_json FROM platform_publication WHERE tenant_id=? AND kind=? AND object_id=? AND version=?",
                        tenant,
                        kind,
                        id,
                        version);
        if (row == null) throw new ApiError(404, "发布版本不存在");
        return json.object(row.get("content_json").toString());
    }

    public Map<String, Object> campaignSnapshot(long tenant, Object campaign) {
        if (campaign == null) return null;
        var row =
                one(
                        "SELECT snapshot_json FROM platform_campaign_snapshot WHERE tenant_id=? AND campaign_id=?",
                        tenant,
                        campaign);
        return row == null ? null : json.object(row.get("snapshot_json").toString());
    }

    public static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?>)) throw new ApiError(422, "配置对象格式不正确");
        return new LinkedHashMap<>((Map<String, Object>) value);
    }

    public static int number(Object value) {
        return value instanceof Number n ? n.intValue() : 0;
    }

    public static String text(Map<String, Object> body, String key, int max, boolean required) {
        Object raw = body.get(key);
        if (raw != null && !(raw instanceof String)) throw new ApiError(422, key + " 须为文字");
        String value = Objects.toString(raw, "").trim();
        if (required && value.isBlank() || value.length() > max)
            throw new ApiError(422, key + " 不能为空或超过 " + max + " 字符");
        return value;
    }
}
