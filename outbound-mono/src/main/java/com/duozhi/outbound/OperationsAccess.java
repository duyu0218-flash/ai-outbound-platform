package com.duozhi.outbound;

import java.util.*;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;

@Component
public class OperationsAccess {
    private final OperationsStore db;
    public OperationsAccess(OperationsStore db){this.db=db;}
    public Map<String,Object> scope(Identity i){
        i.requireUser();
        boolean admin="admin".equals(i.user().get("role"));
        var p=JsonSupport.map("tenantId",i.tenant(),"userId",i.userId(),"allScope",admin);
        // A supervisor without a configured team retains the original whole-tenant contract.
        boolean all=admin || Boolean.TRUE.equals(i.user().get("is_supervisor"))&&db.rows("teams",p).isEmpty()&&db.one("permissions",p)==null;
        p.put("allScope",all);return p;
    }
    public boolean all(Identity i){return Boolean.TRUE.equals(scope(i).get("allScope"));}
    public void admin(Identity i){i.requireUser();i.admin("operations:write");}
    public boolean flag(Identity i,String key){
        if("admin".equals(i.requireUser().get("role")))return true;
        var policy=db.one("permissions",scope(i));
        return policy==null?key.equals("can_record"):truth(policy.get(key));
    }
    static boolean truth(Object x){return Boolean.TRUE.equals(x)||x instanceof Number n&&n.intValue()!=0;}
    public void call(Identity i,Object id){
        if(i.user()==null)return;var p=scope(i);p.put("id",id.toString());
        var existence=new LinkedHashMap<>(p);existence.put("allScope",true);if(db.count("callCount",existence)==0)throw new ApiError(404,"通话不存在");
        if(db.count("callCount",p)==0)throw new ApiError(403,"通话不在授权范围");
    }
    public List<Map<String,Object>> users(Identity i){return db.rows("users",scope(i));}
    public boolean legacyPickup(Identity i,Object id){
        if(i.user()==null||!"agent".equals(i.user().get("role")))return false;
        var p=scope(i);if(!db.rows("teams",p).isEmpty())return false;
        p.put("id",id.toString());p.put("allScope",true);p.put("limit",1);p.put("offset",0);
        var rows=db.rows("calls",p);return !rows.isEmpty()&&rows.get(0).get("owner_id")==null;
    }
    public boolean manager(Identity i){return all(i)||db.rows("teams",scope(i)).stream().filter(t->truth(t.get("enabled"))).anyMatch(t->db.rows("members",JsonSupport.map("tenantId",i.tenant(),"team_id",t.get("id"))).stream().anyMatch(m->Objects.toString(m.get("user_id"),"").equals(i.userId().toString())&&truth(m.get("is_lead"))));}
    public boolean target(Identity i,Object id){return users(i).stream().anyMatch(u->Objects.toString(u.get("id"),"").equals(id.toString())&&truth(u.get("enabled")));}
    public boolean managed(Identity i){return i.user()!=null&&!"admin".equals(i.user().get("role"))&&!all(i);}
}
