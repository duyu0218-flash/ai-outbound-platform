package com.duozhi.outbound.operations.service;

import static com.duozhi.outbound.workspace.service.WorkspaceDocuments.*;

import com.duozhi.outbound.auth.domain.Identity;
import com.duozhi.outbound.common.support.JsonSupport;
import com.duozhi.outbound.job.worker.TaskWorker;
import com.duozhi.outbound.workspace.service.WorkspaceDocuments;
import java.util.*;
import org.springframework.stereotype.Service;

/** Results are routed once, outside the analysis call lock. Existing owners always win. */
@Service
public class SharedLeadAssignment {
    private final WorkspaceDocuments docs;

    public SharedLeadAssignment(WorkspaceDocuments docs) {
        this.docs = docs;
    }

    public void execute(Map<String, Object> task, Map<String, Object> event) {
        long tenant = number(task.get("tenant_id"));
        docs.tx.serialized(
                () -> {
                    TaskWorker.permitted();
                    var snapshot = docs.campaignSnapshot(tenant, event.get("campaign_id"));
                    if (snapshot == null || event.get("contact_id") == null) return null;
                    var policy = map(snapshot.getOrDefault("share_policy", Map.of()));
                    String key = JsonSupport.sha(event.get("call_id") + ":" + event.get("attempt"));
                    if (policy.isEmpty() || docs.find(tenant, "share_assignment", key) != null)
                        return null;
                    var call =
                            docs.one(
                                    "SELECT status,attempts,contact_id,campaign_id FROM callsession WHERE tenant_id=? AND id=?",
                                    tenant,
                                    event.get("call_id"));
                    if (call == null
                            || number(call.get("attempts")) != number(event.get("attempt"))
                            || !Objects.equals(
                                    call.get("contact_id").toString(),
                                    event.get("contact_id").toString())
                            || !Objects.toString(call.get("campaign_id"), "")
                                    .equals(event.get("campaign_id").toString())
                            || !Set.of("COMPLETED", "FAILED", "BUSY", "NO_ANSWER", "CANCELED")
                                    .contains(call.get("status"))) return null;
                    int groupIndex = -1;
                    Map<String, Object> group = Map.of();
                    String reference =
                            Objects.toString(
                                    event.get(Objects.toString(policy.get("reference"), "grade")),
                                    "");
                    var groups = (List<?>) policy.getOrDefault("intention_groups", List.of());
                    for (int n = 0; n < groups.size(); n++) {
                        var candidate = map(groups.get(n));
                        if (((List<?>) candidate.get("values")).isEmpty()
                                || ((List<?>) candidate.get("values")).contains(reference)) {
                            groupIndex = n;
                            group = candidate;
                            break;
                        }
                    }
                    if (groupIndex < 0) return null;
                    var eligible = new ArrayList<Object>();
                    for (var id : (List<?>) group.get("user_ids"))
                        if (docs.one(
                                        "SELECT id FROM sys_user WHERE tenant_id=? AND id=? AND enabled=1",
                                        tenant,
                                        id)
                                != null) eligible.add(id);
                    if (eligible.isEmpty()) return null;
                    String script = snapshot.get("script_id").toString();
                    var cursor = docs.find(tenant, "share_cursor", script);
                    var counts =
                            cursor == null
                                    ? new LinkedHashMap<String, Object>()
                                    : new LinkedHashMap<>(
                                            map(map(cursor.get("draft")).get("counts")));
                    String bucket = policy.get("revision") + ":" + groupIndex;
                    int count = number(counts.get(bucket));
                    Object target = eligible.get(Math.floorMod(count, eligible.size()));
                    docs.jdbc.update(
                            "INSERT IGNORE INTO ops_lead(tenant_id,contact_id,fields_json,tags_json) VALUES(?,?,JSON_OBJECT(),JSON_ARRAY())",
                            tenant,
                            event.get("contact_id"));
                    int assigned =
                            docs.jdbc.update(
                                    "UPDATE ops_lead SET owner_id=?,revision=revision+1,updated_at=UTC_TIMESTAMP(3) WHERE tenant_id=? AND contact_id=? AND owner_id IS NULL",
                                    target,
                                    tenant,
                                    event.get("contact_id"));
                    var actor = new Identity(tenant, null, Set::of);
                    if (assigned > 0) {
                        counts.put(bucket, count == Integer.MAX_VALUE ? 0 : count + 1);
                        docs.save(
                                actor,
                                "share_cursor",
                                script,
                                "",
                                Map.of("name", "话术分享分配计数", "counts", counts),
                                cursor == null ? 0 : number(cursor.get("revision")));
                        docs.jdbc.update(
                                "INSERT IGNORE INTO ops_notice(tenant_id,user_id,event_key,title,path) VALUES(?,?,?,?,?)",
                                tenant,
                                target,
                                "share:" + key,
                                "话术意向分组分配了新线索",
                                "/leads");
                    }
                    docs.save(
                            actor,
                            "share_assignment",
                            key,
                            script,
                            Map.of(
                                    "name",
                                    "话术分享分配结果",
                                    "assigned",
                                    assigned > 0,
                                    "contact_id",
                                    event.get("contact_id"),
                                    "owner_id",
                                    target),
                            0);
                    return null;
                });
    }
}
