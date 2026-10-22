package com.duozhi.outbound;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/users")
public class AdminUsers {
    private final Store store;private final Contract contract;private final Crypto crypto;private final Audit audit;
    public AdminUsers(Store store,Contract contract,Crypto crypto,Audit audit){this.store=store;this.contract=contract;this.crypto=crypto;this.audit=audit;}
    @GetMapping
    public List<Map<String,Object>> list(HttpServletRequest request,@RequestParam(defaultValue="1")
    int page,@RequestParam(defaultValue="100")
    int size,@RequestParam(required=false)
    String keyword){
        Identity actor=Identity.from(request);int offset=Contract.page(page,size);ArrayList<Object> args=new ArrayList<Object>();args.add(actor.tenant());String where="WHERE tenant_id=?";
        if(keyword!=null){if(keyword.length()>200)throw new ApiError(422,"keyword too long");where+=" AND (username LIKE ? OR full_name LIKE ?)";args.add("%"+keyword+"%");args.add("%"+keyword+"%");}
        args.add(size);args.add(offset);return store.list("user",where+" ORDER BY created_at DESC,id ASC LIMIT ? OFFSET ?",args.toArray()).stream().map(row->contract.output("AdminUserOut",row)).collect(Collectors.toList());
    }
    @PostMapping
    @Transactional
    public Map<String,Object> create(@RequestBody
    Map<String,Object> body,HttpServletRequest request){
        Identity actor=Identity.from(request);Map<String,Object> values=contract.input("AdminUserCreate",body,true);
        if(store.count("user","WHERE username=?",values.get("username"))>0)throw new ApiError(409,"username already exists");
        values.put("password_hash",crypto.password(values.remove("password").toString()));values.put("tenant_id",actor.tenant());
        Map<String,Object> user=store.insert("user",values);audit.record(actor,"create","user",user.get("id"),"username="+user.get("username"));return contract.output("AdminUserOut",user);
    }
    private Map<String,Object> locked(Identity actor,long id){
        // Serialize tenant administrator-count checks across concurrent changes.
        store.one("tenant","WHERE id=?"+store.lock(),actor.tenant());
        Map<String,Object> user=store.one("user","WHERE tenant_id=? AND id=?"+store.lock(),actor.tenant(),id);
        if(user==null)throw new ApiError(404,"user not found");return user;
    }
    private void protect(Identity actor,Map<String,Object> user,Map<String,Object> changes){
        boolean removes=Boolean.FALSE.equals(changes.get("enabled"))||"agent".equals(changes.get("role"));
        if(removes&&Objects.equals(((Number)actor.userId()).longValue(),((Number)user.get("id")).longValue()))throw new ApiError(409,"cannot disable or demote current account");
        if(removes&&"admin".equals(user.get("role"))&&Boolean.TRUE.equals(user.get("enabled"))&&store.count("user","WHERE tenant_id=? AND role='admin' AND enabled=?",actor.tenant(),true)<=1)throw new ApiError(409,"at least one enabled administrator is required");
    }
    @PutMapping("/{id}")
    @Transactional
    public Map<String,Object> update(@PathVariable
    long id,@RequestBody
    Map<String,Object> body,HttpServletRequest request){
        Identity actor=Identity.from(request);Map<String,Object> data=contract.input("AdminUserUpdate",body,false);Map<String,Object> user=locked(actor,id);protect(actor,user,data);
        if(data.containsKey("enabled")||data.containsKey("role")||data.containsKey("is_supervisor"))data.put("token_version",((Number)user.get("token_version")).longValue()+1);
        Map<String,Object> updated=store.update("user",actor.tenant(),id,data);audit.record(actor,"update","user",id,"fields="+data.keySet());return contract.output("AdminUserOut",updated);
    }
    @PostMapping("/{id}/reset-password")
    @Transactional
    public Map<String,String> reset(@PathVariable
    long id,@RequestBody
    Map<String,Object> body,HttpServletRequest request){
        Identity actor=Identity.from(request);Map<String,Object> input=contract.input("AdminPasswordReset",body,true);Map<String,Object> user=locked(actor,id);
        Map<String,Object> data=clearLock(user);data.put("password_hash",crypto.password(input.get("password").toString()));store.update("user",actor.tenant(),id,data);
        audit.record(actor,"reset_password","user",id,"password reset");return Values.map("result","updated");
    }
    private Map<String,Object> clearLock(Map<String,Object> user){LinkedHashMap<String,Object> data=new LinkedHashMap<String,Object>();data.put("failed_login_attempts",0);data.put("locked_until",null);data.put("token_version",((Number)user.get("token_version")).longValue()+1);return data;}
    @PostMapping("/{id}/unlock")
    @Transactional
    public Map<String,String> unlock(@PathVariable
    long id,HttpServletRequest request){
        Identity actor=Identity.from(request);Map<String,Object> user=locked(actor,id);store.update("user",actor.tenant(),id,clearLock(user));audit.record(actor,"unlock","user",id,"account unlocked");return Values.map("result","unlocked");
    }
    @DeleteMapping("/{id}")
    @Transactional
    public Map<String,String> disable(@PathVariable
    long id,HttpServletRequest request){
        Identity actor=Identity.from(request);Map<String,Object> user=locked(actor,id);protect(actor,user,Values.map("enabled",false));store.update("user",actor.tenant(),id,Values.map("enabled",false,"token_version",((Number)user.get("token_version")).longValue()+1));audit.record(actor,"disable","user",id,"account disabled");return Values.map("result","disabled");
    }
}
