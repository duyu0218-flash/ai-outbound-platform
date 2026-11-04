package com.duozhi.outbound.workspace.service;

import static com.duozhi.outbound.workspace.service.WorkspaceDocuments.*;

import com.duozhi.outbound.auth.domain.Identity;
import com.duozhi.outbound.common.exception.ApiError;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeOrder {
    private final WorkspaceDocuments docs;

    public KnowledgeOrder(WorkspaceDocuments docs) {
        this.docs = docs;
    }

    public List<Map<String, Object>> entries(long tenant, String library) {
        var entries = new ArrayList<>(docs.list(tenant, "knowledge", library));
        var sort = docs.find(tenant, "faq_order", library);
        if (sort != null && map(sort.get("draft")).get("ids") instanceof List<?> ids) {
            var ranks = new HashMap<Object, Integer>();
            for (int i = 0; i < ids.size(); i++) ranks.put(ids.get(i), i);
            entries.sort(
                    Comparator.comparingInt(
                            e -> ranks.getOrDefault(e.get("object_id"), Integer.MAX_VALUE)));
        }
        return entries;
    }

    public Object change(Identity i, String library, Map<String, Object> b) {
        return docs.tx.serialized(
                () -> {
                    var lib = docs.require(i.tenant(), "library", library);
                    int revision = number(b.get("revision"));
                    if (number(lib.get("revision")) != revision)
                        throw new ApiError(409, "知识库已更新，请刷新");
                    if (!(b.get("ids") instanceof List<?> ids)
                            || ids.isEmpty()
                            || ids.size() > 500
                            || new HashSet<>(ids).size() != ids.size())
                        throw new ApiError(422, "条目选择无效");
                    var all = entries(i.tenant(), library);
                    var byId = new LinkedHashMap<Object, Map<String, Object>>();
                    for (var item : all) byId.put(item.get("object_id"), item);
                    if (!byId.keySet().containsAll(ids)) throw new ApiError(409, "选择的条目已变化");
                    String op = Objects.toString(b.get("action"), "");
                    if (op.equals("order")) {
                        if (ids.size() != all.size()) throw new ApiError(409, "请提交完整顺序");
                        var old = docs.find(i.tenant(), "faq_order", library);
                        docs.save(
                                i,
                                "faq_order",
                                library,
                                library,
                                Map.of("name", "知识条目顺序", "ids", ids),
                                old == null ? 0 : number(old.get("revision")));
                    } else if (op.equals("delete")) {
                        for (var id : ids) {
                            if ("document".equals(map(byId.get(id).get("draft")).get("kind")))
                                throw new ApiError(422, "文档分段请在文档管理中处理");
                            docs.jdbc.update(
                                    "DELETE FROM platform_document WHERE tenant_id=? AND kind='knowledge' AND parent_id=? AND object_id=?",
                                    i.tenant(),
                                    library,
                                    id);
                        }
                    } else if (op.equals("sync")) {
                        String target = Objects.toString(b.get("target"), "");
                        if (target.equals(library)) throw new ApiError(422, "请选择其他知识库");
                        var destination = docs.require(i.tenant(), "library", target);
                        int count = entries(i.tenant(), target).size(), inserted = 0;
                        for (var id : ids) {
                            var source = map(byId.get(id).get("draft"));
                            if ("document".equals(source.get("kind")))
                                throw new ApiError(422, "只支持同步问答");
                            String key =
                                    UUID.nameUUIDFromBytes(
                                                    ("faq:" + library + ":" + target + ":" + id)
                                                            .getBytes(StandardCharsets.UTF_8))
                                            .toString();
                            var old = docs.find(i.tenant(), "knowledge", key);
                            if (old != null) {
                                if (!source.equals(map(old.get("draft"))))
                                    throw new ApiError(409, "目标有不同内容，请比较后编辑");
                                continue;
                            }
                            if (++count > 500) throw new ApiError(422, "目标库容量不足");
                            docs.save(i, "knowledge", key, target, source, 0);
                            inserted++;
                        }
                        if (inserted > 0)
                            docs.save(
                                    i,
                                    "library",
                                    target,
                                    "",
                                    map(destination.get("draft")),
                                    number(destination.get("revision")));
                        return Map.of("added", inserted);
                    } else throw new ApiError(422, "操作无效");
                    docs.save(i, "library", library, "", map(lib.get("draft")), revision);
                    return Map.of("changed", ids.size());
                });
    }
}
