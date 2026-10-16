package com.duozhi.outbound;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.util.*;

@RestController
@RequestMapping("/api/v1/admin/lines")
public class AdminLines {
    private final Store store;private final Contract contract;private final Config config;private final Audit audit;
    public AdminLines(Store store,Contract contract,Config config,Audit audit){this.store=store;this.contract=contract;this.config=config;this.audit=audit;}
    @GetMapping
    public List<Map<String,Object>> list(HttpServletRequest request){return store.list("telephonyline","WHERE tenant_id=? ORDER BY priority,created_at",Identity.from(request).tenant()).stream().map(row->contract.output("TelephonyLineOut",row)).collect(java.util.stream.Collectors.toList());}
    private void validate(Map<String,Object> values){
        String provider=Objects.toString(values.get("provider"),"").trim().transform(Ascii::lower);String gateway=Objects.toString(values.get("gateway_url"),"").trim();
        if(!Values.set("http","mock","freeswitch","opensips").contains(provider))throw new ApiError(400,"invalid provider");
        if(provider.equals("http")&&gateway.isEmpty())throw new ApiError(400,"HTTP bridge requires gateway_url");
        if(!gateway.isEmpty())try{Settings.endpoint(gateway,false);}catch(ApiError e){throw new ApiError(400,"gateway_url must point to an HTTP bridge");}
        String credential=Objects.toString(values.get("credential_ref"),"").trim();
        if(!credential.isEmpty()&&!credential.matches("[A-Za-z0-9_]{1,128}"))throw new ApiError(400,"invalid credential reference");
    }
    private void approval(HttpServletRequest request,boolean real){
        if(!real&&!config.production()&&config.get("TELEPHONY_PROVIDER","mock").equals("mock"))return;
        String expected=config.get("OUTBOUND_SECURITY_APPROVAL_TOKEN","");
        if(expected.length()<32||!Crypto.equal(expected,request.getHeader("x-security-approval")))throw new ApiError(403,"独立安全审批凭据缺失：真实线路及安全策略变更须由安全管理员批准");
    }
    @PostMapping @Transactional
    public Map<String,Object> create(@RequestBody Map<String,Object> body,HttpServletRequest request){
        com.duozhi.outbound.Identity actor=Identity.from(request);java.util.Map<java.lang.String,java.lang.Object> data=contract.input("TelephonyLineCreate",body,true);validate(data);approval(request,!"mock".equals(data.get("provider")));data.put("tenant_id",actor.tenant());
        java.util.Map<java.lang.String,java.lang.Object> row=store.insert("telephonyline",data);audit.record(actor,"create","telephony_line",row.get("id"),"line created");return contract.output("TelephonyLineOut",row);
    }
    @PutMapping("/{id}") @Transactional
    public Map<String,Object> update(@PathVariable long id,@RequestBody Map<String,Object> body,HttpServletRequest request){
        com.duozhi.outbound.Identity actor=Identity.from(request);java.util.Map<java.lang.String,java.lang.Object> data=contract.input("TelephonyLineUpdate",body,false);java.util.Map<java.lang.String,java.lang.Object> row=store.require("telephonyline",actor.tenant(),id);java.util.LinkedHashMap<java.lang.String,java.lang.Object> combined=new LinkedHashMap<>(row);combined.putAll(data);validate(combined);approval(request,!"mock".equals(row.get("provider"))||!"mock".equals(combined.get("provider")));
        java.util.Map<java.lang.String,java.lang.Object> result=store.update("telephonyline",actor.tenant(),id,data);audit.record(actor,"update","telephony_line",id,"fields="+data.keySet());return contract.output("TelephonyLineOut",result);
    }
    @DeleteMapping("/{id}") @Transactional
    public Map<String,String> disable(@PathVariable long id,HttpServletRequest request){com.duozhi.outbound.Identity actor=Identity.from(request);store.update("telephonyline",actor.tenant(),id,Values.map("enabled",false));audit.record(actor,"disable","telephony_line",id,"line disabled");return Values.map("result","disabled");}
}
