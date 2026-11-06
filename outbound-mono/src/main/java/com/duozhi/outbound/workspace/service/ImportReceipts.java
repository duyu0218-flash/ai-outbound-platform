package com.duozhi.outbound.workspace.service;

import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class ImportReceipts {
    private final WorkspaceDocuments docs;

    public ImportReceipts(WorkspaceDocuments docs) {
        this.docs = docs;
    }

    public void completed(long tenant, Object user, Map<String, Object> result) {
        if (user == null || result.get("batch_id") == null) return;
        Object batch = result.get("batch_id");
        long count =
                docs.jdbc.queryForObject(
                        "SELECT COUNT(*) FROM platform_contact_batch_member m JOIN contact c ON c.tenant_id=m.tenant_id AND c.id=m.contact_id WHERE m.tenant_id=? AND m.batch_id=? AND c.dnc=1",
                        Long.class,
                        tenant,
                        batch);
        String title =
                (count > 0 ? "黑名单导入" : "号码导入")
                        + "完成：传入"
                        + result.get("total")
                        + "个，成功"
                        + ((Number) result.getOrDefault("created", 0)).longValue()
                        + "个新增、"
                        + result.getOrDefault("updated", 0)
                        + "个更新，禁呼"
                        + count
                        + "个；失败"
                        + result.getOrDefault("failed", 0)
                        + "个";
        docs.jdbc.update(
                "INSERT IGNORE INTO ops_notice(tenant_id,user_id,event_key,title,path) VALUES(?,?,?,?,?)",
                tenant,
                user,
                "contact-import:" + batch,
                title,
                "/operations/notices?receipt=" + batch);
    }
}
