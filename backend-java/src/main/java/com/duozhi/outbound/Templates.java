package com.duozhi.outbound;

import javax.servlet.http.HttpServletRequest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/v1/script-templates")
public class Templates {
    private final Store store; private final Contract contract;
    public Templates(Store store,Contract contract){this.store=store;this.contract=contract;}
    private Identity actor(HttpServletRequest request){com.duozhi.outbound.Identity actor=Identity.from(request);actor.admin();return actor;}
    @GetMapping
    public List<Map<String,Object>> list(HttpServletRequest request,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="50")int size,@RequestParam(defaultValue="false")boolean active_only,@RequestParam(required=false)String category){
        com.duozhi.outbound.Identity actor=actor(request);int offset=Contract.page(page,size);java.util.ArrayList<java.lang.Object> args=new ArrayList<Object>();args.add(actor.tenant());String where="WHERE tenant_id=?";
        if(active_only){where+=" AND is_active=?";args.add(true);}if(category!=null&&!category.isEmpty()){where+=" AND category=?";args.add(category);}
        args.add(size);args.add(offset);
        return store.list("scripttemplate",where+" ORDER BY updated_at DESC,id DESC LIMIT ? OFFSET ?",args.toArray()).stream().map(row->contract.output("ScriptTemplateOut",row)).collect(java.util.stream.Collectors.toList());
    }
    @GetMapping("/{id}")
    public Map<String,Object> get(@PathVariable long id,HttpServletRequest request){return contract.output("ScriptTemplateOut",store.require("scripttemplate",actor(request).tenant(),id));}
    @PostMapping
    @Transactional
    public Map<String,Object> create(@RequestBody Map<String,Object> body,HttpServletRequest request){
        com.duozhi.outbound.Identity actor=actor(request);java.util.Map<java.lang.String,java.lang.Object> values=contract.input("ScriptTemplateCreate",body,true);values.put("tenant_id",actor.tenant());values.put("version",1);
        return contract.output("ScriptTemplateOut",store.insert("scripttemplate",values));
    }
    @PutMapping("/{id}")
    @Transactional
    public Map<String,Object> update(@PathVariable long id,@RequestBody Map<String,Object> body,HttpServletRequest request){
        com.duozhi.outbound.Identity actor=actor(request);java.util.Map<java.lang.String,java.lang.Object> data=contract.input("ScriptTemplateUpdate",body,false);
        java.util.Map<java.lang.String,java.lang.Object> row=store.one("scripttemplate","WHERE tenant_id=? AND id=?"+store.lock(),actor.tenant(),id);
        if(row==null)throw new ApiError(404,"template not found");
        data.values().removeIf(Objects::isNull);data.put("version",((Number)row.get("version")).intValue()+1);
        return contract.output("ScriptTemplateOut",store.update("scripttemplate",actor.tenant(),id,data));
    }
    @DeleteMapping("/{id}")
    @Transactional
    public Map<String,String> delete(@PathVariable long id,HttpServletRequest request){store.update("scripttemplate",actor(request).tenant(),id,Values.map("is_active",false));return Values.map("result","deleted");}
}
