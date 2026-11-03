package com.duozhi.outbound.workspace.service;

import static com.duozhi.outbound.workspace.service.WorkspaceDocuments.*;

import com.duozhi.outbound.auth.domain.Identity;
import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.common.support.JsonSupport;
import java.util.*;
import org.springframework.stereotype.Service;

/** Directory edits affect drafts; published paths and running-task snapshots remain immutable. */
@Service
public class KnowledgeDirectories {
    private final WorkspaceDocuments docs;

    public KnowledgeDirectories(WorkspaceDocuments docs) {
        this.docs = docs;
    }

    public static String path(String raw) {
        String value = raw.trim();
        if (value.isBlank() || value.length() > 160 || value.equals("未分类") || value.contains("//"))
            throw new ApiError(422, "分类路径须为1至160字符，未分类为保留目录");
        var parts = value.split("/", -1);
        if (parts.length > 6) throw new ApiError(422, "分类最多6级");
        for (String part : parts)
            if (part.isBlank() || !part.equals(part.trim()) || part.length() > 80)
                throw new ApiError(422, "分类名称为空或超过80字符");
        return value;
    }

    public List<Map<String, Object>> list(long tenant, String library) {
        docs.require(tenant, "library", library);
        var result = new ArrayList<>(docs.list(tenant, "knowledge_category", library));
        var known = new HashSet<String>();
        for (var row : result) known.add(row.get("name").toString());
        var entries = docs.list(tenant, "knowledge", library);
        for (var entry : entries) {
            String value = Objects.toString(map(entry.get("draft")).get("category"), "");
            if (!value.isBlank())
                for (String prefix : prefixes(value))
                    if (known.add(prefix))
                        result.add(
                                JsonSupport.map(
                                        "object_id",
                                        null,
                                        "name",
                                        prefix,
                                        "legacy",
                                        true,
                                        "revision",
                                        0));
        }
        for (var row : result)
            row.put(
                    "entry_count",
                    entries.stream()
                            .filter(
                                    e ->
                                            under(
                                                    Objects.toString(
                                                            map(e.get("draft")).get("category"),
                                                            ""),
                                                    row.get("name").toString()))
                            .count());
        result.sort(Comparator.comparing(x -> x.get("name").toString()));
        return result;
    }

    public Object create(Identity actor, String library, Map<String, Object> body) {
        return docs.tx.serialized(
                () -> {
                    var lib = docs.require(actor.tenant(), "library", library);
                    revision(lib, body);
                    String name = path(text(body, "path", 160, true));
                    for (var row : list(actor.tenant(), library))
                        if (name.equals(row.get("name"))) throw new ApiError(409, "分类已存在");
                    var existing = docs.list(actor.tenant(), "knowledge_category", library);
                    var names = new HashSet<String>();
                    for (var row : existing) names.add(row.get("name").toString());
                    var missing = prefixes(name).stream().filter(p -> !names.contains(p)).toList();
                    if (existing.size() + missing.size() > 200)
                        throw new ApiError(422, "每个知识库最多200个分类目录");
                    Map<String, Object> value = null;
                    for (String prefix : missing)
                        value =
                                docs.save(
                                        actor,
                                        "knowledge_category",
                                        UUID.randomUUID().toString(),
                                        library,
                                        JsonSupport.map("name", prefix),
                                        0);
                    touch(actor, lib, library);
                    return value;
                });
    }

    public Object change(Identity actor, String library, Map<String, Object> body, boolean remove) {
        return docs.tx.serialized(
                () -> {
                    var lib = docs.require(actor.tenant(), "library", library);
                    revision(lib, body);
                    String old = path(text(body, "path", 160, true)),
                            next = remove ? "" : path(text(body, "new_path", 160, true));
                    var rows = list(actor.tenant(), library);
                    if (rows.stream().noneMatch(x -> old.equals(x.get("name"))))
                        throw new ApiError(404, "分类不存在");
                    if (!remove && old.equals(next))
                        return JsonSupport.map("changed_entries", 0, "categories", rows);
                    if (!remove
                            && rows.stream()
                                    .anyMatch(x -> next.equals(x.get("name")) && !old.equals(next)))
                        throw new ApiError(409, "目标分类已存在");
                    if (!remove && next.startsWith(old + "/"))
                        throw new ApiError(422, "不能将分类移动到自身子目录");
                    var directoryRows = docs.list(actor.tenant(), "knowledge_category", library);
                    var planned = new HashSet<String>();
                    for (var row : rows) {
                        String name = row.get("name").toString();
                        String target =
                                under(name, old)
                                        ? remove ? null : next + name.substring(old.length())
                                        : name;
                        if (target != null) {
                            path(target);
                            if (!planned.add(target)) throw new ApiError(409, "子目录与现有分类冲突");
                        }
                    }
                    for (var row : directoryRows) {
                        String name = row.get("name").toString();
                        if (!under(name, old)) continue;
                        if (remove)
                            docs.jdbc.update(
                                    "DELETE FROM platform_document WHERE tenant_id=? AND kind='knowledge_category' AND object_id=?",
                                    actor.tenant(),
                                    row.get("object_id"));
                        else
                            docs.save(
                                    actor,
                                    "knowledge_category",
                                    row.get("object_id").toString(),
                                    library,
                                    JsonSupport.map("name", next + name.substring(old.length())),
                                    number(row.get("revision")));
                    }
                    if (!remove && directoryRows.stream().noneMatch(x -> old.equals(x.get("name"))))
                        docs.save(
                                actor,
                                "knowledge_category",
                                UUID.randomUUID().toString(),
                                library,
                                JsonSupport.map("name", next),
                                0);
                    int changed = 0;
                    for (var row : docs.list(actor.tenant(), "knowledge", library)) {
                        var value = map(row.get("draft"));
                        String name = Objects.toString(value.get("category"), "");
                        if (!under(name, old)) continue;
                        value.put("category", remove ? "" : next + name.substring(old.length()));
                        docs.save(
                                actor,
                                "knowledge",
                                row.get("object_id").toString(),
                                library,
                                value,
                                number(row.get("revision")));
                        changed++;
                    }
                    touch(actor, lib, library);
                    docs.recordTransition(
                            actor,
                            "knowledge_category",
                            library,
                            remove ? "removed_to_uncategorized" : "renamed");
                    return JsonSupport.map(
                            "changed_entries",
                            changed,
                            "categories",
                            list(actor.tenant(), library));
                });
    }

    private static boolean under(String value, String parent) {
        return value.equals(parent) || value.startsWith(parent + "/");
    }

    private static List<String> prefixes(String name) {
        var result = new ArrayList<String>();
        int from = 0;
        while ((from = name.indexOf('/', from)) >= 0) {
            if (from > 0) result.add(name.substring(0, from));
            from++;
        }
        result.add(name);
        return result;
    }

    private static void revision(Map<String, Object> lib, Map<String, Object> body) {
        if (number(lib.get("revision")) != number(body.get("expected_revision")))
            throw new ApiError(409, "知识库已变化，请刷新后重试");
    }

    private void touch(Identity actor, Map<String, Object> lib, String library) {
        docs.save(
                actor, "library", library, "", map(lib.get("draft")), number(lib.get("revision")));
    }
}
