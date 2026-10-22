package com.duozhi.outbound;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/v1/admin/settings")
public class AdminSettings {
    private final Store store;private final Settings settings;private final Config config;private final Audit audit;private final ObjectMapper mapper;
    public AdminSettings(Store store,Settings settings,Config config,Audit audit,ObjectMapper mapper){this.store=store;this.settings=settings;this.config=config;this.audit=audit;this.mapper=mapper;}
    @GetMapping("/{section}")
    public Map<String,Object> get(@PathVariable
    String section,HttpServletRequest request){
        Identity actor=Identity.from(request);Map<String,Object> data=settings.get(actor.tenant(),section);Map<String,Object> record=store.one("adminsetting","WHERE tenant_id=? AND section=?",actor.tenant(),section);
        LinkedHashMap<String,Object> result=new LinkedHashMap<String,Object>();result.put("section",section);result.put("data",data);result.put("updated_at",record==null?null:record.get("updated_at"));return result;
    }
    @PutMapping("/{section}")
    @Transactional
    public Map<String,Object> update(@PathVariable
    String section,@RequestBody
    Map<String,Object> body,HttpServletRequest request)throws JacksonException{
        Identity actor=Identity.from(request);if(!(body.get("data") instanceof Map))throw new ApiError(400,"data is required");Map<?,?> input=(Map<?,?>)body.get("data");
        LinkedHashMap<String,Object> values=new LinkedHashMap<String,Object>();for(Map.Entry<?,?> entry:input.entrySet())values.put(entry.getKey().toString(),entry.getValue());Map<String,Object> data=settings.validate(section,values);
        if(Values.set("capacity","compliance").contains(section)){
            boolean real=store.count("telephonyline","WHERE tenant_id=? AND enabled=? AND provider<>'mock'",actor.tenant(),true)>0;
            if(real||config.production()||!config.get("TELEPHONY_PROVIDER","mock").equals("mock")){
                String expected=config.get("OUTBOUND_SECURITY_APPROVAL_TOKEN","");if(expected.length()<32||!Crypto.equal(expected,request.getHeader("x-security-approval")))throw new ApiError(403,"independent security approval required");
            }
            if(section.equals("capacity")&&((Number)data.get("max_concurrent_calls")).longValue()>config.number("OUTBOUND_PLATFORM_MAX_CONCURRENT",20))throw new ApiError(422,"租户并发不能超过平台安全上限");
            if(section.equals("compliance")&&((Number)data.get("max_calls_per_day")).longValue()>config.number("OUTBOUND_DAILY_CALL_LIMIT",10000))throw new ApiError(422,"租户日拨号量不能超过平台安全上限");
        }
        store.one("tenant","WHERE id=?"+store.lock(),actor.tenant());Map<String,Object> row=store.one("adminsetting","WHERE tenant_id=? AND section=?",actor.tenant(),section);
        LinkedHashMap<String,Object> saved=new LinkedHashMap<String,Object>();saved.put("data_json",mapper.writeValueAsString(data));saved.put("updated_by",actor.userId());
        if(row==null){saved.put("tenant_id",actor.tenant());saved.put("section",section);store.insert("adminsetting",saved);}else store.update("adminsetting",actor.tenant(),row.get("id"),saved);
        audit.record(actor,"update","setting",section,"fields="+values.keySet());return get(section,request);
    }
}
