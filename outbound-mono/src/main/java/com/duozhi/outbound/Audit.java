package com.duozhi.outbound;

import org.springframework.stereotype.Component;
import java.util.LinkedHashMap;

@Component
public class Audit {
    private final Store store;
    public Audit(Store store) {this.store=store;}
    public void record(Identity actor,String action,String resource,Object id,String detail) {
        java.util.LinkedHashMap<java.lang.String,java.lang.Object> row=new LinkedHashMap<String,Object>();
        row.put("tenant_id",actor.tenant());row.put("actor_user_id",actor.userId());row.put("actor_username",actor.actor());
        row.put("action",action);row.put("resource_type",resource);row.put("resource_id",id==null?null:id.toString());
        row.put("detail",detail.substring(0,Math.min(detail.length(),4000)));
        store.insert("auditlog",row);
    }
}
