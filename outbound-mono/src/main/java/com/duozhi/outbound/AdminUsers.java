package com.duozhi.outbound;
import com.duozhi.outbound.auth.UserRecords;
import com.duozhi.outbound.entity.*;
import com.duozhi.outbound.mapper.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController @RequestMapping("/api/v1/admin/users") @AdminPermission("admin:users")
public class AdminUsers {
    private final AuthIdentityMapper identities;private final SysUserMapper users;private final AuditLogMapper audits;private final AuthService auth;private final Contract contract;private final Crypto crypto;
    public AdminUsers(AuthIdentityMapper identities,SysUserMapper users,AuditLogMapper audits,AuthService auth,Contract contract,Crypto crypto){this.identities=identities;this.users=users;this.audits=audits;this.auth=auth;this.contract=contract;this.crypto=crypto;}
    @GetMapping public List<Map<String,Object>> list(HttpServletRequest request,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="100")int size,@RequestParam(required=false)String keyword){
        Identity actor=Identity.from(request);int offset=Contract.page(page,size);if(keyword!=null&&keyword.length()>200)throw new ApiError(422,"keyword too long");
        return identities.listUsers((int)actor.tenant(),keyword,offset,size).stream().map(u->contract.output("AdminUserOut",UserRecords.map(u))).toList();
    }
    @PostMapping @Transactional public Map<String,Object> create(@RequestBody Map<String,Object> body,HttpServletRequest request){
        Identity actor=Identity.from(request);var values=contract.input("AdminUserCreate",body,true);
        String password=values.remove("password").toString();SysUser user=new SysUser();UserRecords.patch(user,values);user.setTenantId((int)actor.tenant());user.setPasswordHash(crypto.password(password));
        try{users.insert(user);}catch(DuplicateKeyException e){throw new ApiError(409,"username already exists");}
        audit(actor,"create",user.getId(),"username="+user.getUsername());return contract.output("AdminUserOut",UserRecords.map(users.findById(user.getId(),(int)actor.tenant())));
    }
    private SysUser locked(Identity actor,long id){identities.lockTenantForUserAdmin((int)actor.tenant());SysUser user=identities.findOwnedUser(id,(int)actor.tenant());if(user==null)throw new ApiError(404,"user not found");return user;}
    private void protect(Identity actor,SysUser user,Map<String,Object> data){
        boolean removes=Boolean.FALSE.equals(data.get("enabled"))||"agent".equals(data.get("role"));
        if(removes&&actor.userId()!=null&&((Number)actor.userId()).longValue()==user.getId().longValue())throw new ApiError(409,"cannot disable or demote current account");
        if(removes&&"admin".equals(user.getRole())&&Boolean.TRUE.equals(user.getEnabled())&&identities.enabledAdmins((int)actor.tenant())<=1)throw new ApiError(409,"at least one enabled administrator is required");
    }
    @PutMapping("/{user_id}") @Transactional public Map<String,Object> update(@PathVariable("user_id") long id,@RequestBody Map<String,Object> body,HttpServletRequest request){
        Identity actor=Identity.from(request);var data=contract.input("AdminUserUpdate",body,false);SysUser user=locked(actor,id);protect(actor,user,data);
        if(data.containsKey("enabled")||data.containsKey("role")||data.containsKey("is_supervisor"))data.put("token_version",user.getTokenVersion()+1);
        var updated=auth.patch(id,(int)actor.tenant(),data);audit(actor,"update",id,"fields="+data.keySet());return contract.output("AdminUserOut",updated);
    }
    private Map<String,Object> clearLock(SysUser user){var data=new LinkedHashMap<String,Object>();data.put("failed_login_attempts",0);data.put("locked_until",null);data.put("token_version",user.getTokenVersion()+1);return data;}
    @PostMapping("/{user_id}/reset-password") @Transactional public Map<String,String> reset(@PathVariable("user_id") long id,@RequestBody Map<String,Object> body,HttpServletRequest request){
        Identity actor=Identity.from(request);var input=contract.input("AdminPasswordReset",body,true);SysUser user=locked(actor,id);var data=clearLock(user);data.put("password_hash",crypto.password(input.get("password").toString()));auth.patch(id,(int)actor.tenant(),data);audit(actor,"reset_password",id,"password reset");return Map.of("result","updated");
    }
    @PostMapping("/{user_id}/unlock") @Transactional public Map<String,String> unlock(@PathVariable("user_id") long id,HttpServletRequest request){Identity actor=Identity.from(request);SysUser user=locked(actor,id);auth.patch(id,(int)actor.tenant(),clearLock(user));audit(actor,"unlock",id,"account unlocked");return Map.of("result","unlocked");}
    @DeleteMapping("/{user_id}") @Transactional public Map<String,String> disable(@PathVariable("user_id") long id,HttpServletRequest request){Identity actor=Identity.from(request);SysUser user=locked(actor,id);protect(actor,user,Map.of("enabled",false));auth.patch(id,(int)actor.tenant(),Map.of("enabled",false,"token_version",user.getTokenVersion()+1));audit(actor,"disable",id,"account disabled");return Map.of("result","disabled");}
    private void audit(Identity actor,String action,Object id,String detail){AuditLog row=new AuditLog();row.setTenantId((int)actor.tenant());row.setActorUserId(actor.userId()==null?null:((Number)actor.userId()).intValue());row.setActorUsername(actor.actor());row.setAction(action);row.setResourceType("user");row.setResourceId(id==null?null:id.toString());row.setDetail(detail.substring(0,Math.min(detail.length(),4000)));audits.insert(row);}
}
