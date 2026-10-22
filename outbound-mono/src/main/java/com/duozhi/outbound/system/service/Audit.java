package com.duozhi.outbound.system.service;

import com.duozhi.outbound.auth.domain.Identity;
import com.duozhi.outbound.common.dao.Store;
import java.util.LinkedHashMap;
import org.springframework.stereotype.Component;

@Component
public class Audit {
    private final Store store;

    public Audit(Store store) {
        this.store = store;
    }

    public void record(Identity actor, String action, String resource, Object id, String detail) {
        LinkedHashMap<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("tenant_id", actor.tenant());
        row.put("actor_user_id", actor.userId());
        row.put("actor_username", actor.actor());
        row.put("action", action);
        row.put("resource_type", resource);
        row.put("resource_id", id == null ? null : id.toString());
        row.put("detail", detail.substring(0, Math.min(detail.length(), 4000)));
        store.insert("auditlog", row);
    }
}
