package com.duozhi.outbound;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;

/** Single-instance human admission and FIFO reservation. Caller holds platform lock. */
@Service
public class HumanRouting {
    private final Store store;
    private final OperationsStore db;
    private final Transactions tx;
    private final ConversationPolicy conversation;
    private final OperationsWorkforce workforce;
    private final AgentMedia media;
    private final Config config;
    private final JsonSupport json;
    private final AtomicBoolean running=new AtomicBoolean();
    public HumanRouting(Store store,OperationsStore db,Transactions tx,ConversationPolicy conversation,OperationsWorkforce workforce,AgentMedia media,Config config,JsonSupport json){
        this.store=store;this.db=db;this.tx=tx;this.conversation=conversation;this.workforce=workforce;this.media=media;this.config=config;this.json=json;
    }
    public static Map<String,Object> options(Map<String,Object> raw){
        Map<String,Object> result=new LinkedHashMap<>();
        for(String key:List.of("handoff_capacity_control","handoff_auto_callback"))if(raw.containsKey(key)&&!(raw.get(key) instanceof Boolean))throw new ApiError(422,"invalid "+key);
        if(raw.containsKey("handoff_queue_limit")&&!(raw.get("handoff_queue_limit") instanceof Integer||raw.get("handoff_queue_limit") instanceof Long))throw new ApiError(422,"invalid handoff queue limit");
        if(raw.containsKey("handoff_expected_ratio")&&!(raw.get("handoff_expected_ratio") instanceof Number))throw new ApiError(422,"invalid handoff ratio");
        for(String key:List.of("handoff_team_ids","handoff_skills")){
            Object source=raw.getOrDefault(key,List.of());if(!(source instanceof Collection<?> values)||values.size()>100)throw new ApiError(422,"invalid "+key);
            List<Object> clean=new ArrayList<>();for(Object value:values){
                if(key.equals("handoff_team_ids")){long id;try{id=Long.parseLong(value.toString());}catch(RuntimeException bad){throw new ApiError(422,"invalid team ID");}if(id<1||id>Integer.MAX_VALUE)throw new ApiError(422,"invalid team ID");clean.add(id);}
                else{String skill=Objects.toString(value,"").strip();if(skill.isEmpty()||skill.length()>80)throw new ApiError(422,"invalid routing skill");clean.add(skill);}
            }result.put(key,clean.stream().distinct().toList());
        }
        result.put("handoff_capacity_control",!Boolean.FALSE.equals(raw.get("handoff_capacity_control")));
        result.put("handoff_auto_callback",!Boolean.FALSE.equals(raw.get("handoff_auto_callback")));
        long limit=raw.get("handoff_queue_limit") instanceof Number n?n.longValue():20;if(limit<1||limit>500)throw new ApiError(422,"invalid handoff queue limit");result.put("handoff_queue_limit",(int)limit);
        double ratio=raw.get("handoff_expected_ratio") instanceof Number n?n.doubleValue():.1;if(!Double.isFinite(ratio)||ratio<=0||ratio>1)throw new ApiError(422,"invalid handoff ratio");result.put("handoff_expected_ratio",ratio);
        return result;
    }
    Map<String,Object> parameters(long tenant,Map<String,Object> policy){
        return JsonSupport.map("tenantId",tenant,"since",Store.now().minusSeconds(Math.max(30,config.number("AGENT_PRESENCE_TIMEOUT_SEC",90))),
            "allowed",policy.getOrDefault("handoff_agent_ids",List.of()),"teams",policy.getOrDefault("handoff_team_ids",List.of()),"skills",policy.getOrDefault("handoff_skills",List.of()),
            "skillsJson",json.json(policy.getOrDefault("handoff_skills",List.of())));
    }
    public void validate(long tenant,Map<String,Object> policy){
        var teams=db.rows("teams",JsonSupport.map("tenantId",tenant,"userId",0,"allScope",true));
        for(Object id:(Collection<?>)policy.getOrDefault("handoff_team_ids",List.of()))if(teams.stream().noneMatch(t->t.get("id").toString().equals(id.toString())&&Boolean.TRUE.equals(t.get("enabled"))))throw new ApiError(422,"接待团队不属于本租户或已停用");
    }
    private List<Map<String,Object>> candidates(long tenant,Map<String,Object> policy){return db.rows("routingAgents",parameters(tenant,policy));}
    private boolean usable(long tenant,Map<String,Object> agent){return !config.flag("WEBRTC_ENABLED",false)||media.registered(tenant,agent.get("id"));}
    private boolean free(long tenant,Map<String,Object> agent){return "ready".equals(agent.get("agent_status"))&&
        db.count("routingAgentActive",JsonSupport.map("tenantId",tenant,"agentId",agent.get("id")))==0&&
        ((Number)workforce.effective(tenant,agent.get("id")).get("remaining_seconds")).longValue()==0;}
    public Map<String,Object> reserve(Map<String,Object> call){
        long tenant=CallLifecycle.tenant(call);Map<String,Object> state=conversation.state(call),policy=conversation.policy(state);
        if(state.get("policy_version_id")!=null&&!conversation.inHours(policy,Store.now()))return null;
        for(var candidate:candidates(tenant,policy))if(usable(tenant,candidate)&&free(tenant,candidate)){
            store.update("user",tenant,candidate.get("id"),JsonSupport.map("agent_status","busy","last_seen_at",Store.now()));return candidate;
        }return null;
    }
    public boolean eligible(Map<String,Object> call,Object agent){
        long tenant=CallLifecycle.tenant(call);var policy=conversation.policy(conversation.state(call));
        return candidates(tenant,policy).stream().anyMatch(a->a.get("id").toString().equals(agent.toString()))&&
            db.count("routingAgentActive",JsonSupport.map("tenantId",tenant,"agentId",agent,"exclude",store.encode("callsession","id",call.get("id"))))==0&&
            ((Number)workforce.effective(tenant,agent).get("remaining_seconds")).longValue()==0;
    }
    public boolean permits(Map<String,Object> call){
        if(!Values.set("ai_handoff","mixed_human_first").contains(call.get("mode")))return true;
        var policy=conversation.policy(conversation.state(call));if(Boolean.FALSE.equals(policy.get("handoff_capacity_control")))return true;
        long tenant=CallLifecycle.tenant(call);var people=candidates(tenant,policy).stream().filter(a->usable(tenant,a)).toList();if(people.isEmpty())return false;
        int ready=0;for(var a:people)if(free(tenant,a))ready++;
        // Campaigns can share the same desks. Tenant-wide pressure is deliberately
        // conservative so separate campaigns cannot each spend the same reserve.
        var counts=pressure(tenant);
        long waiting=((Number)counts.get("waiting")).longValue(),upstream=((Number)counts.get("upstream")).longValue();
        int limit=JsonSupport.integer(policy.get("handoff_queue_limit"),20);double ratio=policy.get("handoff_expected_ratio") instanceof Number n?n.doubleValue():.1;
        // Keep a bounded reserve for expected transfers; hard queue feedback stops
        // new calls even while technical/AI capacity is otherwise available.
        return waiting<limit&&Math.ceil((upstream+1)*ratio)<=ready+limit-waiting;
    }
    Map<String,Object> pressure(long tenant){
        var batch=DialBatchScope.current();String waiting="human-waiting:"+tenant,upstream="human-upstream:"+tenant;
        if(batch!=null&&batch.counts.containsKey(upstream))return JsonSupport.map("waiting",batch.counts.get(waiting),"upstream",batch.counts.get(upstream));
        var counts=db.one("routingPressure",JsonSupport.map("tenantId",tenant));if(batch!=null){batch.counts.put(waiting,((Number)counts.get("waiting")).longValue());batch.counts.put(upstream,((Number)counts.get("upstream")).longValue());}return counts;
    }
    public int redistribute(){
        return tx.serialized(()->{
            int moved=0;for(var tenant:store.query("tenant","allForMaintenance",Map.of())){
                long tid=((Number)tenant.get("id")).longValue();
                var cursor=JsonSupport.map("tenantId",tid,"afterTime",null,"afterId",0);List<Map<String,Object>> batch;
                do {
                batch=db.rows("routingWaiting",cursor);
                for(var row:batch){
                    var call=store.require("callsession",tid,row.get("call_session_id"));if(!"waiting_human".equals(call.get("status")))continue;
                    Object assigned=row.get("assigned_agent_id");
                    if(assigned!=null){
                        var old=store.require("user",tid,assigned);boolean fresh=old.get("last_seen_at")!=null&&LocalDateTime.parse(old.get("last_seen_at").toString()).isAfter(Store.now().minusSeconds(config.number("AGENT_PRESENCE_TIMEOUT_SEC",90)));
                        if(Boolean.TRUE.equals(old.get("enabled"))&&!"offline".equals(old.get("agent_status"))&&fresh&&usable(tid,old)&&eligible(call,assigned))continue;
                        if("busy".equals(old.get("agent_status")))store.update("user",tid,assigned,JsonSupport.map("agent_status","offline"));
                        store.update("handoffrequest",tid,row.get("id"),JsonSupport.map("assigned_agent_id",null,"target_group","default"));
                        store.update("callsession",tid,call.get("id"),JsonSupport.map("human_agent_id",null));
                    }
                    var agent=reserve(call);if(agent==null)continue;
                    store.update("handoffrequest",tid,row.get("id"),JsonSupport.map("assigned_agent_id",agent.get("id"),"target_group","agent:"+agent.get("id")));
                    store.update("callsession",tid,call.get("id"),JsonSupport.map("human_agent_id",agent.get("id")));moved++;
                }
                if(!batch.isEmpty()){var last=batch.get(batch.size()-1);cursor.put("afterTime",last.get("requested_at"));cursor.put("afterId",last.get("id"));}
                }while(batch.size()==100);
            }return moved;
        });
    }
    @Scheduled(fixedDelayString="${JAVA_HANDOFF_POLL_MS:500}") public void tick(){
        if(!config.flag("JAVA_WORKERS_ENABLED",false)||!running.compareAndSet(false,true))return;
        try{redistribute();}catch(RuntimeException e){org.slf4j.LoggerFactory.getLogger(getClass()).warn("human routing failed: {}",e.getClass().getSimpleName());}finally{running.set(false);}
    }
}
