package com.duozhi.outbound;

import javax.servlet.http.HttpServletRequest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.*;

@RestController
@RequestMapping("/api/v1/contacts")
public class Contacts {
    private final Store store;private final Contract contract;private final Audit audit;
    public Contacts(Store store,Contract contract,Audit audit) {this.store=store;this.contract=contract;this.audit=audit;}
    static String phone(Object input) {
        String value=Objects.toString(input,"").replaceAll("[^0-9]","");
        if(value.length()<6||value.length()>15) throw new ApiError(400,"phone must contain 6 to 15 digits");
        return value;
    }
    static void timezone(Object value) {
        if(value==null||value.toString().trim().isEmpty()) return;
        try {ZoneId.of(value.toString());} catch(DateTimeException e) {throw new ApiError(400,"invalid contact timezone");}
    }
    private Identity actor(HttpServletRequest request) {com.duozhi.outbound.Identity identity=Identity.from(request);identity.admin();return identity;}
    private void consent(Map<String,Object> data,Identity actor) {
        if(data.containsKey("consent_state")) {
            boolean yes="consented".equals(data.get("consent_state"));
            data.put("consented_at",yes?Store.now():null);data.put("consented_by",yes?actor.actor():null);
        }
    }
    @PostMapping
    @Transactional
    public Map<String,Object> create(@RequestBody Map<String,Object> body,HttpServletRequest request) {
        com.duozhi.outbound.Identity actor=actor(request);java.util.Map<java.lang.String,java.lang.Object> values=contract.input("ContactCreate",body,true);
        values.put("phone",phone(values.get("phone")));timezone(values.get("timezone"));
        values.put("tenant_id",actor.tenant());consent(values,actor);
        if("".equals(values.get("dnc_reason"))) values.put("dnc_reason",null);
        if(store.count("contact","WHERE tenant_id=? AND phone=?",actor.tenant(),values.get("phone"))>0) throw new ApiError(409,"contact phone already exists");
        java.util.Map<java.lang.String,java.lang.Object> row=store.insert("contact",values);audit.record(actor,"create","contact",row.get("id"),"contact created");
        return contract.output("ContactOut",row);
    }
    @GetMapping
    public List<Map<String,Object>> list(HttpServletRequest request,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="100") int size,
            @RequestParam(required=false) String keyword,@RequestParam(required=false) List<Long> ids,@RequestParam(required=false) Boolean dnc,@RequestParam(required=false) String consent) {
        com.duozhi.outbound.Identity actor=actor(request);int offset=Contract.page(page,size);
        java.util.ArrayList<java.lang.Object> args=new ArrayList<Object>();args.add(actor.tenant());String where="WHERE tenant_id=?";
        if(keyword!=null&&!keyword.isEmpty()){where+=" AND (phone LIKE ? OR name LIKE ?)";args.add("%"+keyword+"%");args.add("%"+keyword+"%");}
        if(ids!=null){if(ids.size()>200)throw new ApiError(422,"too many ids");if(ids.isEmpty())return Values.list();where+=" AND id IN ("+String.join(",",Collections.nCopies(ids.size(),"?"))+")";args.addAll(ids);}
        if(dnc!=null){where+=" AND dnc=?";args.add(dnc);}
        if(consent!=null&&!consent.isEmpty()){where+=" AND consent_state=?";args.add(store.encode("contact","consent_state",consent));}
        args.add(size);args.add(offset);
        return store.list("contact",where+" ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",args.toArray()).stream().map(row->contract.output("ContactOut",row)).collect(java.util.stream.Collectors.toList());
    }
    @GetMapping("/{id}")
    public Map<String,Object> get(@PathVariable long id,HttpServletRequest request) {return contract.output("ContactOut",store.require("contact",actor(request).tenant(),id));}
    @PatchMapping("/{id}")
    @Transactional
    public Map<String,Object> update(@PathVariable long id,@RequestBody Map<String,Object> body,HttpServletRequest request) {
        com.duozhi.outbound.Identity actor=actor(request);java.util.Map<java.lang.String,java.lang.Object> data=contract.input("ContactPatch",body,false);timezone(data.get("timezone"));consent(data,actor);
        java.util.Map<java.lang.String,java.lang.Object> row=store.update("contact",actor.tenant(),id,data);audit.record(actor,"update","contact",id,"fields="+data.keySet());
        return contract.output("ContactOut",row);
    }
    @DeleteMapping("/{id}")
    @Transactional
    public Map<String,String> delete(@PathVariable long id,HttpServletRequest request) {
        com.duozhi.outbound.Identity actor=actor(request);store.require("contact",actor.tenant(),id);
        long campaignReferences=Objects.requireNonNull(store.jdbc().queryForObject("SELECT COUNT(*) FROM campaigncontact cc JOIN campaign c ON c.id=cc.campaign_id WHERE cc.contact_id=? AND c.tenant_id=? AND c.status<>'deleted'",Long.class,id,actor.tenant()));
        if(campaignReferences>0||store.count("callsession","WHERE tenant_id=? AND contact_id=?",actor.tenant(),id)>0) throw new ApiError(409,"contact is referenced by campaign or call history; mark it DNC instead");
        audit.record(actor,"delete","contact",id,"contact deleted");store.delete("contact",actor.tenant(),id);return Values.map("result","deleted");
    }
    @PatchMapping("/batch-dnc")
    @Transactional
    public Map<String,Object> batch(@RequestBody Map<String,Object> body,HttpServletRequest request) {
        com.duozhi.outbound.Identity actor=actor(request);java.util.Map<java.lang.String,java.lang.Object> input=contract.input("ContactBatchDncPatch",body,true);
        java.util.TreeSet<java.lang.Long> ids=new TreeSet<Long>();for(Object id:(List<?>)input.get("contact_ids"))ids.add(((Number)id).longValue());
        java.util.ArrayList<java.lang.Long> missing=new ArrayList<Long>();int updated=0;
        for(long id:ids){
            java.util.Map<java.lang.String,java.lang.Object> row=store.one("contact","WHERE tenant_id=? AND id=?",actor.tenant(),id);
            if(row==null){missing.add(id);continue;}
            java.util.LinkedHashMap<java.lang.String,java.lang.Object> change=new LinkedHashMap<String,Object>();change.put("dnc",input.get("dnc"));change.put("dnc_reason",input.get("dnc_reason"));
            if(Boolean.TRUE.equals(input.get("dnc")))change.put("consented_at",null);
            store.update("contact",actor.tenant(),id,change);updated++;
        }
        audit.record(actor,"batch_update","contact",null,"updated="+updated);
        return Values.map("total",ids.size(),"updated",updated,"skipped",missing.size(),"missing_contact_ids",missing);
    }
}
