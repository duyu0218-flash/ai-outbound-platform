package com.duozhi.outbound;

import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
public class Dashboard {
    private final Store store;
    private static final String INTERESTED="('interested','qualified_lead','positive_lead','appointment','converted')";
    private static final String REACHED="(c.status IN ('ANSWERED','IN_AI','WAITING_HUMAN','HANDOFF_TRANSFERRING','IN_HUMAN','COMPLETED') OR a.result_code IN ('interested','qualified_lead','positive_lead','appointment','converted','rejected','completed'))";
    public Dashboard(Store store){this.store=store;}
    private double rate(long a,long b){return b==0?0:Math.round(a*1000.0/b)/10.0;}
    private long number(Map<String,Object> row,String key){Object value=row.get(key);return value instanceof Number?((Number)value).longValue():0;}
    @GetMapping("/api/v1/agent/dashboard")
    public Map<String,String> agent(HttpServletRequest request){Identity.from(request).requireUser();return Values.map("scope","agent","message","座席工作台");}
    @GetMapping("/api/v1/admin/dashboard")
    public Map<String,Object> admin(HttpServletRequest request,@RequestParam(defaultValue="30")
    int days){
        if(days<1||days>365)throw new ApiError(400,"invalid days");long tenant=Identity.from(request).tenant();LocalDateTime since=Store.now().minusDays(days);Object sqlSince=store.encode("callsession","created_at",since);
        String join=" FROM callsession c LEFT JOIN callanalysis a ON a.call_session_id=c.id AND a.tenant_id=c.tenant_id WHERE c.tenant_id=? AND c.created_at>=?";
        Map<String,Object> counts=store.jdbc().queryForMap("SELECT COUNT(c.id) AS calls,SUM(CASE WHEN "+REACHED+" THEN 1 ELSE 0 END) AS reached,SUM(CASE WHEN c.status='COMPLETED' THEN 1 ELSE 0 END) AS completed,SUM(CASE WHEN c.status IN ('FAILED','NO_ANSWER','BUSY','VOICEMAIL') THEN 1 ELSE 0 END) AS failed,COUNT(a.id) AS analyzed,SUM(CASE WHEN a.result_code IN "+INTERESTED+" THEN 1 ELSE 0 END) AS interested,SUM(CASE WHEN a.review_state<>'reviewed' THEN 1 ELSE 0 END) AS pending_reviews,AVG(a.qa_score) AS average_qa_score"+join,tenant,sqlSince);
        LinkedHashMap<String,Object> period=new LinkedHashMap<String,Object>();period.put("days",days);period.put("since",since.toString());
        for(String key:Values.list("calls","reached","completed","failed","analyzed","interested","pending_reviews"))period.put(key,number(counts,key));
        period.put("average_qa_score",counts.get("average_qa_score") instanceof Number?Math.round(((Number)counts.get("average_qa_score")).doubleValue()*10)/10.0:0);
        period.put("reach_rate",rate(number(counts,"reached"),number(counts,"calls")));period.put("interest_rate",rate(number(counts,"interested"),number(counts,"reached")));period.put("completion_rate",rate(number(counts,"completed"),number(counts,"calls")));
        ArrayList<Map<String,Object>> performance=new ArrayList<Map<String,Object>>();
        List<Map<String,Object>> rows=store.jdbc().queryForList("SELECT c.campaign_id,COUNT(c.id) AS calls,SUM(CASE WHEN "+REACHED+" THEN 1 ELSE 0 END) AS reached,SUM(CASE WHEN a.result_code IN "+INTERESTED+" THEN 1 ELSE 0 END) AS interested"+join+" AND c.campaign_id IS NOT NULL GROUP BY c.campaign_id ORDER BY COUNT(c.id) DESC LIMIT 8",tenant,sqlSince);
        for(Map<String,Object> row:rows){Map<String,Object> campaign=store.one("campaign","WHERE tenant_id=? AND id=?",tenant,row.get("campaign_id"));if(campaign==null)continue;LinkedHashMap<String,Object> out=new LinkedHashMap<>(row);out.put("name",campaign.get("name"));out.put("reach_rate",rate(number(row,"reached"),number(row,"calls")));out.put("interest_rate",rate(number(row,"interested"),number(row,"reached")));performance.add(out);}
        return Values.map("scope","admin","message","管理员控制台","stats",Values.map("contacts",store.count("contact","WHERE tenant_id=?",tenant),"active_scripts",store.count("scripttemplate","WHERE tenant_id=? AND is_active=?",tenant,true),"campaigns",store.count("campaign","WHERE tenant_id=? AND status<>'deleted'",tenant),"calls",store.count("callsession","WHERE tenant_id=?",tenant)),"period",period,"campaign_performance",performance,"metric_definitions",Values.map("reached","当前状态为已接听及后续阶段，或分析/人工复核结果表明已完成有效沟通的通话","interested","自动分析或人工复核结果为 interested、qualified_lead、positive_lead、appointment 或 converted"));
    }
}
