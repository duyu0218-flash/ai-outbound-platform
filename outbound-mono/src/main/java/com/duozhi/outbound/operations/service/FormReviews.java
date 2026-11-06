package com.duozhi.outbound.operations.service;

import static com.duozhi.outbound.workspace.service.WorkspaceDocuments.*;

import com.duozhi.outbound.auth.domain.Identity;
import com.duozhi.outbound.common.dao.Store;
import com.duozhi.outbound.common.support.JsonSupport;
import com.duozhi.outbound.workspace.service.WorkspaceDocuments;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class FormReviews {
    private final WorkspaceDocuments docs;
    private final Store store;

    public FormReviews(WorkspaceDocuments docs, Store store) {
        this.docs = docs;
        this.store = store;
    }

    public void assign(
            Identity actor, Object form, Object contact, Map<String, Object> fields, String event) {
        var rule =
                docs.list(actor.tenant(), "form_assignment", null).stream()
                        .map(d -> map(d.get("published")))
                        .filter(
                                c ->
                                        number(c.get("form_id")) == number(form)
                                                && !Boolean.FALSE.equals(c.get("enabled")))
                        .findFirst()
                        .orElse(Map.of());
        if (!(rule.get("reviewer_ids") instanceof List<?> reviewers) || reviewers.isEmpty()) return;
        var eligible = new ArrayList<Object>();
        for (var id : reviewers)
            if (docs.one(
                            "SELECT id FROM sys_user WHERE tenant_id=? AND id=? AND enabled=1",
                            actor.tenant(),
                            id)
                    != null) eligible.add(id);
        if (eligible.isEmpty()) return;
        String id = form.toString();
        var cursor = docs.find(actor.tenant(), "form_review_cursor", id);
        int count = cursor == null ? 0 : number(map(cursor.get("draft")).get("count"));
        Object user = eligible.get(Math.floorMod(count, eligible.size()));
        var c = store.require("contact", actor.tenant(), contact);
        store.insert(
                "productworkitem",
                JsonSupport.map(
                        "tenant_id",
                        actor.tenant(),
                        "event_key",
                        "form-review:" + actor.tenant() + ":" + event,
                        "kind",
                        "form_review",
                        "state",
                        "open",
                        "phone",
                        c.get("phone"),
                        "assigned_to",
                        user,
                        "detail_json",
                        docs.json.json(
                                Map.of(
                                        "contact_id",
                                        contact,
                                        "form_id",
                                        form,
                                        "fields",
                                        fields,
                                        "submitted_by",
                                        actor.userId()))));
        docs.save(
                actor,
                "form_review_cursor",
                id,
                "",
                Map.of("name", "表单质检轮询", "count", count == Integer.MAX_VALUE ? 0 : count + 1),
                cursor == null ? 0 : number(cursor.get("revision")));
    }
}
