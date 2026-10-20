package com.duozhi.outbound;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/v1/knowledge")
public class Knowledge {
    private final Store store;private final Contract contract;
    public Knowledge(Store store,Contract contract){this.store=store;this.contract=contract;}
    @GetMapping public List<Map<String,Object>> list(HttpServletRequest request){return store.query("knowledgeitem","allKnowledge",Values.map("tenantId",Identity.from(request).tenant())).stream().map(row->contract.output("KnowledgeItemOut",row)).collect(java.util.stream.Collectors.toList());}
    @AdminPermission("knowledge:write") @PostMapping @Transactional
    public Map<String,Object> create(@RequestBody Map<String,Object> body,HttpServletRequest request){
        com.duozhi.outbound.Identity actor=Identity.from(request);actor.admin("knowledge:write");java.util.Map<java.lang.String,java.lang.Object> values=contract.input("KnowledgeItemCreate",body,true);values.put("tenant_id",actor.tenant());values.put("created_by",actor.userId());
        return contract.output("KnowledgeItemOut",store.insert("knowledgeitem",values));
    }
    @AdminPermission("knowledge:write") @PutMapping("/{id}") @Transactional
    public Map<String,Object> update(@PathVariable long id,@RequestBody Map<String,Object> body,HttpServletRequest request){
        com.duozhi.outbound.Identity actor=Identity.from(request);actor.admin("knowledge:write");java.util.Map<java.lang.String,java.lang.Object> values=contract.input("KnowledgeItemUpdate",body,false);
        java.util.Map<java.lang.String,java.lang.Object> row=store.one("knowledgeitem","lockById",Values.map("tenantId",actor.tenant(),"id",id));if(row==null)throw new ApiError(404,"knowledge item not found");
        values.put("version",((Number)row.get("version")).intValue()+1);return contract.output("KnowledgeItemOut",store.update("knowledgeitem",actor.tenant(),id,values));
    }
    @AdminPermission("knowledge:write") @DeleteMapping("/{id}") @Transactional
    public Map<String,Object> delete(@PathVariable long id,HttpServletRequest request){return update(id,Values.map("is_active",false),request);}
}
