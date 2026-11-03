package com.duozhi.outbound.workspace.service;

import static com.duozhi.outbound.workspace.service.WorkspaceDocuments.*;

import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.marketing.dataset.Segments;
import java.util.*;
import org.springframework.stereotype.Service;

/** Resolves owned, immutable sources. Control groups and unlinked encrypted numbers never dial. */
@Service
public class WorkspaceAudience {
    private final WorkspaceDocuments docs;
    private final ContactVariables variables;
    private final Segments segments;

    public WorkspaceAudience(
            WorkspaceDocuments docs, ContactVariables variables, Segments segments) {
        this.docs = docs;
        this.variables = variables;
        this.segments = segments;
    }

    public record Selection(
            LinkedHashMap<Long, Map<String, Object>> members,
            int excluded,
            int control,
            int unlinked) {}

    private List<?> list(Map<String, Object> body, String key, int limit) {
        Object v = body.getOrDefault(key, List.of());
        if (!(v instanceof List<?> l) || l.size() > limit)
            throw new ApiError(422, "名单来源格式或数量不正确：" + key);
        return (List<?>) v;
    }

    public Selection resolve(long tenant, Map<String, Object> body) {
        var values = new LinkedHashMap<Long, Map<String, Object>>();
        int control = 0, unlinked = 0;
        for (Object raw : list(body, "contact_ids", 200000)) {
            if (!(raw instanceof Number n) || n.longValue() < 1)
                throw new ApiError(422, "联系人标识不正确");
            values.put(n.longValue(), variables.current(tenant, n.longValue()));
        }
        for (Object raw : list(body, "batch_ids", 50)) {
            String id = raw.toString();
            if (docs.one(
                            "SELECT id FROM platform_contact_batch WHERE tenant_id=? AND id=?",
                            tenant,
                            id)
                    == null) throw new ApiError(404, "名单批次不存在");
            for (var row :
                    docs.jdbc.queryForList(
                            "SELECT contact_id,variables_json FROM platform_contact_batch_member WHERE tenant_id=? AND batch_id=? ORDER BY contact_id",
                            tenant,
                            id)) {
                long cid = ((Number) row.get("contact_id")).longValue();
                var v =
                        row.get("variables_json") == null
                                ? new LinkedHashMap<String, Object>()
                                : docs.json.object(row.get("variables_json").toString());
                if (values.containsKey(cid)
                        && !values.get(cid).isEmpty()
                        && !v.isEmpty()
                        && !values.get(cid).equals(v))
                    throw new ApiError(422, "多个来源的同一客户变量冲突，请只保留一个来源：" + cid);
                if (!v.isEmpty() || !values.containsKey(cid)) values.put(cid, v);
            }
        }
        for (Object raw : list(body, "segment_snapshot_ids", 20)) {
            String id = raw.toString();
            segments.snapshot(tenant, id);
            long after = 0;
            while (true) {
                var page = segments.page(tenant, id, after, 1000);
                if (page.isEmpty()) break;
                for (var m : page) {
                    after = m.sequence();
                    if (!"test".equals(m.group())) {
                        control++;
                        continue;
                    }
                    if (m.contactId() == null) {
                        unlinked++;
                        continue;
                    }
                    values.putIfAbsent(m.contactId(), variables.current(tenant, m.contactId()));
                }
                if (values.size() > 200000) throw new ApiError(422, "名单超过200000人");
            }
        }
        int excluded = 0;
        for (Object raw : list(body, "excluded_contact_ids", 200000)) {
            if (!(raw instanceof Number n)) throw new ApiError(422, "排除客户标识不正确");
            if (values.remove(n.longValue()) != null) excluded++;
        }
        if (values.size() > 200000) throw new ApiError(422, "名单超过200000人");
        // Reject a foreign/deleted direct contact before any recipient snapshot is saved.
        for (var chunk : chunks(new ArrayList<>(values.keySet()), 500)) {
            var args = new ArrayList<Object>();
            args.add(tenant);
            args.addAll(chunk);
            var rows =
                    docs.jdbc.queryForList(
                            "SELECT id,name,phone FROM contact WHERE tenant_id=? AND id IN ("
                                    + String.join(",", Collections.nCopies(chunk.size(), "?"))
                                    + ")",
                            args.toArray());
            if (rows.size() != chunk.size()) throw new ApiError(404, "名单含不存在或无权访问的客户");
            for (var row : rows) {
                long id = ((Number) row.get("id")).longValue();
                var v = new LinkedHashMap<>(values.get(id));
                v.put("name", Objects.toString(row.get("name"), ""));
                v.put("phone", row.get("phone"));
                values.put(id, v);
            }
        }
        return new Selection(values, excluded, control, unlinked);
    }

    static <T> List<List<T>> chunks(List<T> rows, int size) {
        var result = new ArrayList<List<T>>();
        for (int n = 0; n < rows.size(); n += size)
            result.add(rows.subList(n, Math.min(n + size, rows.size())));
        return result;
    }

    public void freeze(long tenant, String snapshot, Selection selected) {
        docs.jdbc.update(
                "DELETE FROM platform_audience_member WHERE tenant_id=? AND snapshot_id=?",
                tenant,
                snapshot);
        docs.jdbc.batchUpdate(
                "INSERT INTO platform_audience_member(tenant_id,snapshot_id,contact_id,variables_json) VALUES(?,?,?,?)",
                selected.members.entrySet(),
                500,
                (ps, e) -> {
                    ps.setLong(1, tenant);
                    ps.setString(2, snapshot);
                    ps.setLong(3, e.getKey());
                    ps.setString(4, docs.json.json(e.getValue()));
                });
    }

    public Map<String, Object> preview(long tenant, Map<String, Object> body, int page, int size) {
        if (page < 1 || size < 1 || size > 100) throw new ApiError(422, "分页参数不正确");
        var s = resolve(tenant, body);
        var keys = new ArrayList<>(s.members.keySet());
        long start = (long) (page - 1) * size;
        var rows = new ArrayList<Map<String, Object>>();
        var positions = new HashMap<Long, Integer>();
        for (int n = 0; n < keys.size(); n++) positions.put(keys.get(n), n);
        int dnc = 0, noConsent = 0, missing = 0;
        Map<String, Object> schema =
                variables.schema(
                        tenant,
                        Objects.toString(
                                body.get("script_id"),
                                Objects.toString(body.get("script_template_id"), "")),
                        Objects.toString(body.get("template_id"), ""));
        for (var chunk : chunks(keys, 500)) {
            var args = new ArrayList<Object>();
            args.add(tenant);
            args.addAll(chunk);
            for (var row :
                    docs.jdbc.queryForList(
                            "SELECT id,name,phone,dnc,consent_state FROM contact WHERE tenant_id=? AND id IN ("
                                    + String.join(",", Collections.nCopies(chunk.size(), "?"))
                                    + ") ORDER BY id",
                            args.toArray())) {
                long id = ((Number) row.get("id")).longValue();
                boolean bad = false;
                var v = s.members.get(id);
                try {
                    var text = new HashMap<String, String>();
                    v.forEach((k, val) -> text.put(k, Objects.toString(val, "")));
                    variables.values(schema, text);
                } catch (ApiError e) {
                    bad = true;
                }
                if (Boolean.TRUE.equals(row.get("dnc"))) dnc++;
                if (!"consented".equalsIgnoreCase(Objects.toString(row.get("consent_state"), "")))
                    noConsent++;
                if (bad) missing++;
                int position = positions.get(id);
                if (position >= start && position < start + size) {
                    row.put(
                            "consent_state",
                            Objects.toString(row.get("consent_state"), "unknown")
                                    .toLowerCase(Locale.ROOT));
                    row.put("variables", v);
                    row.put("missing_variables", bad);
                    rows.add(row);
                }
            }
        }
        return com.duozhi.outbound.common.support.JsonSupport.map(
                "rows",
                rows,
                "total",
                keys.size(),
                "excluded",
                s.excluded,
                "control_group",
                s.control,
                "unlinked",
                s.unlinked,
                "flags",
                Map.of("dnc", dnc, "not_consented", noConsent, "missing_variables", missing));
    }
}
