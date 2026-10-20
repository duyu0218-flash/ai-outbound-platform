package com.duozhi.outbound;

import java.util.*;
import java.time.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class Handoffs {
    private final Store store;private final Transactions tx;private final Contract contract;private final Config config;
    private final TelephonyClient client;private final ConversationPolicy product;private final JsonSupport json;private final AgentMedia media;private final OperationsAccess operations;
    public Handoffs(Store store,Transactions tx,Contract contract,Config config,TelephonyClient client,ConversationPolicy product,JsonSupport json,AgentMedia media,OperationsAccess operations){this.store=store;this.tx=tx;this.contract=contract;this.config=config;this.client=client;this.product=product;this.json=json;this.media=media;this.operations=operations;}
    public void visible(Map<String,Object> call,Identity identity){if(call==null||CallLifecycle.tenant(call)!=identity.tenant())throw new ApiError(404,"call not found");operations.call(identity,call.get("id"));}
    public Map<String,Object> queue(Map<String,Object> call,String reason) {
        long tenant=CallLifecycle.tenant(call);Object id=call.get("id");
        Map<String,Object> existing=store.one("handoffrequest","sourceHandoffs17_0",Store.queryParameters(0,tenant,store.encode("handoffrequest","call_session_id",id)));
        if(existing!=null)return existing;
        Map<String,Object> state=product.state(call),policy=product.policy(state);List<Object> args=new ArrayList<>();args.add(tenant);args.add(true);args.add(store.encode("user","last_seen_at",Store.now().minusSeconds(Math.max(30,config.number("AGENT_PRESENCE_TIMEOUT_SEC",90)))));
        String where="WHERE tenant_id=? AND role='agent' AND enabled=? AND agent_status='ready' AND last_seen_at>=?";
        Collection<?> allowed=(Collection<?>)policy.get("handoff_agent_ids");if(!allowed.isEmpty()){where+=" AND id IN ("+String.join(",",Collections.nCopies(allowed.size(),"?"))+")";args.addAll(allowed);}
        Map<String,Object> agent=store.one("user","readyHandoffAgent",Values.map("tenantId",tenant,"since",store.encode("user","last_seen_at",Store.now().minusSeconds(Math.max(30,config.number("AGENT_PRESENCE_TIMEOUT_SEC",90)))),"allowed",allowed));
        if(state.get("policy_version_id")!=null&&!product.inHours(policy,Store.now()))agent=null;
        Object agentId=agent==null?null:agent.get("id");
        store.update("callsession",tenant,id,JsonSupport.map("status","waiting_human","human_agent_id",agentId,"handoff_reason",reason));
        if(agent!=null)store.update("user",tenant,agentId,JsonSupport.map("agent_status","busy","last_seen_at",Store.now()));
        Map<String,Object> result=store.insert("handoffrequest",JsonSupport.map("tenant_id",tenant,"call_session_id",id,"assigned_agent_id",agentId,"reason",reason,"target_group",agentId==null?"default":"agent:"+agentId));
        Map<String,Object> changed=new LinkedHashMap<>(call);changed.put("status","waiting_human");changed.put("human_agent_id",agentId);product.arm(changed,"handoff");return result;
    }
    private Map<String,Object> get(long tenant,Object call,Object id){Map<String,Object> row=store.require("handoffrequest",tenant,id);if(!row.get("call_session_id").toString().equals(call.toString()))throw new ApiError(404,"handoff not found");return row;}
    public Map<String,Object> transfer(Identity identity,Object id,Object handoffId,Object agentId,String reason){
        if(agentId==null)throw new ApiError(409,"assigned agent is not available in this tenant");
        Map<String,Object> snapshot=tx.run(()->{
            tx.platform();Map<String,Object> call=tx.call(id);
            boolean pickup=handoffId!=null&&get(identity.tenant(),id,handoffId).get("assigned_agent_id")==null&&operations.legacyPickup(identity,id);
            if(!pickup)visible(call,identity);
            if(!Values.set("dialing","answered","in_ai","waiting_human").contains(call.get("status")))throw new ApiError(409,"call status not handover-able");
            Map<String,Object> handoff=handoffId==null?store.one("handoffrequest","sourceHandoffs36_2",Store.queryParameters(0,identity.tenant(),store.encode("handoffrequest","call_session_id",id))):get(identity.tenant(),id,handoffId);
            if(handoff!=null&&!"waiting".equals(handoff.get("state")))throw new ApiError(409,"handoff is not waiting");
            if(handoff!=null&&handoff.get("assigned_agent_id")!=null&&!handoff.get("assigned_agent_id").toString().equals(agentId.toString()))throw new ApiError(403,"handoff is assigned to another agent");
            Map<String,Object> agent=store.one("user","sourceHandoffs39_3",Store.queryParameters(0,identity.tenant(),agentId));
            boolean reserved=handoff!=null&&Objects.toString(handoff.get("assigned_agent_id"),"").equals(agentId.toString());
            if(agent==null||!"agent".equals(agent.get("role"))||!Boolean.TRUE.equals(agent.get("enabled"))||!(reserved?Values.set("ready","busy"):Values.set("ready")).contains(agent.get("agent_status")))throw new ApiError(409,"agent is not ready");
            if(config.flag("WEBRTC_ENABLED",false)&&!media.registered(identity.tenant(),agentId))throw new ApiError(409,"agent browser SIP endpoint is not registered");
            Map<String,Object> change=JsonSupport.map("state","accepting","assigned_agent_id",agentId,"target_group","agent:"+agentId,"reason",reason,"responded_at",null);
            if(handoff==null){change.put("tenant_id",identity.tenant());change.put("call_session_id",id);handoff=store.insert("handoffrequest",change);}else handoff=store.update("handoffrequest",identity.tenant(),handoff.get("id"),change);
            call=store.update("callsession",identity.tenant(),id,JsonSupport.map("status","handoff_transferring","human_agent_id",agentId,"handoff_reason",reason));
            store.update("user",identity.tenant(),agentId,JsonSupport.map("agent_status","busy","last_seen_at",Store.now()));
            return JsonSupport.map("call",call,"handoff",handoff);
        });
        @SuppressWarnings("unchecked") Map<String,Object> call=(Map<String,Object>)snapshot.get("call"),handoff=(Map<String,Object>)snapshot.get("handoff");
        RuntimeException failure=null;try{client.command(call,"transfer",JsonSupport.map("reason",reason,"target_group","agent:"+agentId));}catch(RuntimeException e){failure=e;}
        final boolean failed=failure!=null;
        Map<String,Object> result=tx.run(()->{
            tx.platform();Map<String,Object> latest=tx.call(id),h=get(identity.tenant(),id,handoff.get("id"));
            if(CallLifecycle.number(latest,"attempts")==CallLifecycle.number(call,"attempts")&&"accepting".equals(h.get("state"))&&Objects.equals(h.get("updated_at"),handoff.get("updated_at"))) {
                if(failed&&"handoff_transferring".equals(latest.get("status"))) {
                    store.update("callsession",identity.tenant(),id,JsonSupport.map("status","waiting_human","human_agent_id",null,"last_error","handover failed"));
                    store.update("handoffrequest",identity.tenant(),h.get("id"),JsonSupport.map("state","waiting","assigned_agent_id",null,"target_group","default"));
                    store.update("user",identity.tenant(),agentId,JsonSupport.map("agent_status","ready"));return null;
                }
                if(Values.set("handoff_transferring","in_human").contains(latest.get("status")))return store.update("handoffrequest",identity.tenant(),h.get("id"),JsonSupport.map("state","accepted","responded_at",Store.now()));
            }
            return h;
        });
        if(result==null)throw new ApiError(502,"telephony transfer failed");return result;
    }
    @PostMapping("/calls/{id}/handoffs/{handoff_id}/accept") public Map<String,Object> accept(@PathVariable UUID id,@PathVariable long handoff_id,HttpServletRequest request){Identity identity=Identity.from(request);Map<String,Object> h=get(identity.tenant(),id,handoff_id);Object agent=identity.user()!=null&&"agent".equals(identity.user().get("role"))?identity.userId():h.get("assigned_agent_id");return contract.output("HandoffRequestOut",transfer(identity,id,handoff_id,agent,Objects.toString(h.get("reason"),"agent_accept")));}
    @PostMapping("/calls/{id}/handoffs/{handoff_id}/reject") public Map<String,Object> reject(@PathVariable UUID id,@PathVariable long handoff_id,HttpServletRequest request){Identity identity=Identity.from(request);return tx.run(()->{tx.platform();Map<String,Object> call=tx.call(id);visible(call,identity);Map<String,Object> h=get(identity.tenant(),id,handoff_id);if(!"waiting".equals(h.get("state")))throw new ApiError(409,"handoff is not waiting");if(identity.user()!=null&&!"admin".equals(identity.user().get("role"))&&h.get("assigned_agent_id")!=null&&!h.get("assigned_agent_id").toString().equals(identity.userId().toString()))throw new ApiError(403,"handoff is assigned to another agent");product.releaseWaiting(call);store.update("callsession",identity.tenant(),id,JsonSupport.map("human_agent_id",null));return contract.output("HandoffRequestOut",store.update("handoffrequest",identity.tenant(),handoff_id,JsonSupport.map("state","rejected","responded_at",Store.now())));});}
    @GetMapping("/calls/{id}/handoffs") public List<Map<String,Object>> callHandoffs(@PathVariable UUID id,HttpServletRequest request){Identity identity=Identity.from(request);visible(store.require("callsession",identity.tenant(),id),identity);List<Map<String,Object>> result=new ArrayList<>();for(Map<String,Object> row:store.query("handoffrequest","sourceHandoffs68_4",Store.queryParameters(0,identity.tenant(),store.encode("handoffrequest","call_session_id",id))))result.add(contract.output("HandoffRequestOut",row));return result;}
    @GetMapping("/handoffs") public List<Map<String,Object>> queue(@RequestParam(defaultValue="waiting") String state,HttpServletRequest request){
        Identity identity=Identity.from(request);String encoded=store.encode("handoffrequest","state",state).toString();List<Object> args=new ArrayList<>(Arrays.asList(identity.tenant(),encoded));String where="WHERE tenant_id=? AND state=?";
        if(identity.user()!=null&&"agent".equals(identity.user().get("role"))&&!Boolean.TRUE.equals(identity.user().get("is_supervisor"))){where+=" AND (assigned_agent_id IS NULL OR assigned_agent_id=?)";args.add(identity.userId());}
        List<Map<String,Object>> result=new ArrayList<>();for(Map<String,Object> h:store.query("handoffrequest","handoffQueue",JsonSupport.map("tenantId",identity.tenant(),"state",encoded,"agentId",identity.user()!=null&&"agent".equals(identity.user().get("role"))&&!Boolean.TRUE.equals(identity.user().get("is_supervisor"))?identity.userId():null))) {
            Map<String,Object> call=store.one("callsession","sourceHandoffs73_6",Store.queryParameters(0,identity.tenant(),store.encode("callsession","id",h.get("call_session_id"))));if(call==null)continue;try{operations.call(identity,call.get("id"));}catch(ApiError denied){continue;}
            Map<String,Object> contact=call.get("contact_id")==null?null:store.one("contact","sourceHandoffs74_7",Store.queryParameters(0,identity.tenant(),call.get("contact_id"))),campaign=call.get("campaign_id")==null?null:store.one("campaign","sourceHandoffs74_8",Store.queryParameters(0,identity.tenant(),call.get("campaign_id")));
            Map<String,Object> analysis=store.one("callanalysis","sourceHandoffs75_9",Store.queryParameters(0,identity.tenant(),store.encode("callanalysis","call_session_id",call.get("id"))));
            Map<String,Object> turn=store.one("speechturn","scopedHandoffs76",Store.scopedParameters(CallLifecycle.tenant(call),store.encode("speechturn","call_session_id",call.get("id")),true));
            String summary=Objects.toString(analysis==null?call.get("summary"):analysis.get("summary"),"");
            Map<String,Object> stateRow=store.one("conversationstate","scopedHandoffs78",Store.scopedParameters(CallLifecycle.tenant(call),store.encode("conversationstate","call_id",call.get("id")),call.get("attempts")));
            if(stateRow!=null){Object slots=json.object(stateRow.get("data_json").toString()).get("slots");if(slots instanceof Map)for(Map.Entry<?,?> entry:((Map<?,?>)slots).entrySet())if(entry.getValue() instanceof Map&&Boolean.TRUE.equals(((Map<?,?>)entry.getValue()).get("confirmed")))summary+=" 已确认信息："+entry.getKey()+"："+((Map<?,?>)entry.getValue()).get("value");}
            h.putAll(JsonSupport.map("phone",call.get("phone"),"mode",call.get("mode"),"campaign_id",call.get("campaign_id"),"contact_name",contact==null?null:contact.get("name"),"campaign_name",campaign==null?null:campaign.get("name"),"intent",analysis==null?null:analysis.get("intent"),"summary",summary,"last_customer_utterance",Objects.toString(turn==null?call.get("last_transcript"):turn.get("transcript"),""),"wait_seconds",Math.max(0,Duration.between(LocalDateTime.parse(h.get("requested_at").toString()),Store.now()).getSeconds())));result.add(contract.output("HandoffQueueItemOut",h));
        }return result;
    }
    @PostMapping("/calls/{id}/handover") public Map<String,Object> handover(@PathVariable UUID id,@RequestParam(defaultValue="operator_request") String reason,@RequestParam(required=false) String target_group,HttpServletRequest request){
        Identity identity=Identity.from(request);Object agent=identity.user()!=null&&"agent".equals(identity.user().get("role"))?identity.userId():null;
        if(target_group!=null&&!target_group.equals("default")){if(!target_group.matches("agent:[1-9][0-9]*"))throw new ApiError(409,"transfer target must be an authorized agent ID");long requested=Long.parseLong(target_group.substring(6));if(agent!=null&&!agent.toString().equals(""+requested))throw new ApiError(409,"transfer target does not match assigned agent");agent=requested;}
        transfer(identity,id,null,agent,reason);return contract.output("CallSessionOut",store.require("callsession",identity.tenant(),id));
    }
}
