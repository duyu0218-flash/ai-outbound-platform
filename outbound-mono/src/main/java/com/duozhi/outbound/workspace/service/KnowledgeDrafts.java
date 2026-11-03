package com.duozhi.outbound.workspace.service;

import static com.duozhi.outbound.workspace.service.WorkspaceDocuments.*;

import com.duozhi.outbound.auth.domain.Identity;
import com.duozhi.outbound.common.exception.ApiError;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.stereotype.Service;

/** One validation and draft-write path for FAQ, document segments and recovered questions. */
@Service
public class KnowledgeDrafts {
    private final WorkspaceDocuments docs;

    public KnowledgeDrafts(WorkspaceDocuments docs) {
        this.docs = docs;
    }

    public Map<String, Object> normalize(Map<String, Object> raw) {
        var value = new LinkedHashMap<String, Object>();
        for (String key :
                List.of(
                        "name",
                        "content",
                        "category",
                        "keywords",
                        "source",
                        "similar_questions",
                        "valid_from",
                        "valid_until"))
            value.put(
                    key,
                    text(
                            raw,
                            key,
                            key.equals("name") ? 160 : key.equals("content") ? 20000 : 4000,
                            key.equals("name") || key.equals("content")));
        String kind = Objects.toString(raw.get("kind"), "faq");
        if (!Set.of("faq", "document").contains(kind)) throw new ApiError(422, "知识类型不正确");
        value.put("kind", kind);
        value.put("enabled", !Boolean.FALSE.equals(raw.get("enabled")));
        if (raw.get("document_id") != null) {
            String id = text(raw, "document_id", 64, true);
            if (!id.matches("[A-Za-z0-9-]+")) throw new ApiError(422, "文档标识不正确");
            value.put("document_id", id);
            int n = number(raw.get("segment_index"));
            if (n < 1 || n > 500) throw new ApiError(422, "文档分段序号不正确");
            value.put("segment_index", n);
        }
        for (String key : List.of("valid_from", "valid_until")) {
            String date = value.get(key).toString();
            if (!date.isBlank())
                try {
                    LocalDateTime.parse(date);
                } catch (Exception e) {
                    throw new ApiError(422, "知识有效期须为 UTC 日期时间");
                }
        }
        String from = value.get("valid_from").toString(),
                until = value.get("valid_until").toString();
        if (!from.isBlank()
                && !until.isBlank()
                && !LocalDateTime.parse(from).isBefore(LocalDateTime.parse(until)))
            throw new ApiError(422, "结束时间必须晚于开始时间");
        return value;
    }

    public Map<String, Object> save(
            Identity actor, String library, String id, Map<String, Object> raw, int expected) {
        if (!id.matches("[A-Za-z0-9-]{1,64}")) throw new ApiError(422, "知识标识不正确");
        return docs.tx.serialized(
                () -> {
                    var lib = docs.require(actor.tenant(), "library", library);
                    var prior = docs.find(actor.tenant(), "knowledge", id);
                    if (prior != null && !library.equals(prior.get("parent_id")))
                        throw new ApiError(404, "知识不存在于此库");
                    if (prior == null
                            && docs.jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM platform_document WHERE tenant_id=? AND kind='knowledge' AND parent_id=?",
                                            Integer.class,
                                            actor.tenant(),
                                            library)
                                    >= 500) throw new ApiError(422, "单库最多500条知识，请拆分知识库");
                    var result =
                            docs.save(actor, "knowledge", id, library, normalize(raw), expected);
                    docs.save(
                            actor,
                            "library",
                            library,
                            "",
                            map(lib.get("draft")),
                            number(lib.get("revision")));
                    return result;
                });
    }
}
