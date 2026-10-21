package com.duozhi.outbound;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AsyncDialService {
 static final int MAX_BYTES=1048576;
 static final class Rejected extends ApiError {
  final List<Map<String,Object>> errors;
  Rejected(List<Map<String,Object>> errors){super(422,"batch rejected; no calls queued");this.errors=List.copyOf(errors);}
 }
 private final JdbcTemplate jdbc;private final Store store;private final Contract contract;private final JsonSupport json;
 private final DialService dial;private final Transactions tx;private final Outbox outbox;private final DialDrainControl drain;private final Config config;
 public AsyncDialService(JdbcTemplate jdbc,Store store,Contract contract,JsonSupport json,DialService dial,Transactions tx,Outbox outbox,DialDrainControl drain,Config config){
  this.jdbc=jdbc;this.store=store;this.contract=contract;this.json=json;this.dial=dial;this.tx=tx;this.outbox=outbox;this.drain=drain;this.config=config;
 }
 void enabled(){if(!config.flag("JAVA_DIAL_ASYNC_API_ENABLED",false))throw new ApiError(404,"async dial API is disabled");}
 private String actor(Identity identity){return identity.user()==null?"api":"user:"+identity.userId();}
 @SuppressWarnings("unchecked")
 Map<String,Object> submit(Identity identity,String key,List<?> raw,String entry){
  enabled();dial.available();
  if(key==null||!key.matches("[A-Za-z0-9_.:-]{1,128}"))throw new ApiError(422,"Idempotency-Key is required (1-128 ASCII characters)");
  if(raw.isEmpty()||raw.size()>500)throw new ApiError(422,"batch must contain 1-500 items");
  var inputs=new ArrayList<Map<String,Object>>();var errors=new ArrayList<Map<String,Object>>();
  for(int index=0;index<raw.size();index++){
   try{if(!(raw.get(index) instanceof Map<?,?>))throw new ApiError(422,"item must be an object");inputs.add(contract.input("StartCallRequest",(Map<String,Object>)raw.get(index),true));}
   catch(ApiError invalid){inputs.add(null);errors.add(Map.of("index",index,"status",invalid.status,"reason",invalid.getMessage()));}
  }
  if(!errors.isEmpty())throw new Rejected(errors);
  String digest=JsonSupport.sha(json.canonical(Map.of("entry",entry,"items",inputs),false));
  return tx.serialized(()->{
   if(!drain.acceptingInTransaction())throw new ApiError(503,"dial admission is draining");
   tx.tenant(identity.tenant());
   String batch=UUID.randomUUID().toString();
   jdbc.update("INSERT IGNORE INTO dial_async_batch(tenant_id,idempotency_key,id,actor,request_digest) VALUES(?,?,?,?,?)",identity.tenant(),key,batch,actor(identity),digest);
   var row=jdbc.queryForMap("SELECT * FROM dial_async_batch WHERE tenant_id=? AND idempotency_key=? FOR UPDATE",identity.tenant(),key);
   if(!digest.equals(row.get("request_digest")))throw new ApiError(409,"Idempotency-Key has different request content");
   if(!actor(identity).equals(row.get("actor")))throw new ApiError(403,"batch belongs to a different caller");
   if(row.get("response_json")!=null)return json.object(row.get("response_json").toString());
   for(int index=0;index<inputs.size();index++){
    try{dial.preflightQueued(identity,inputs.get(index));}
    catch(ApiError invalid){errors.add(Map.of("index",index,"status",invalid.status,"reason",invalid.getMessage()));}
   }
   if(!errors.isEmpty())throw new Rejected(errors);
   var ids=new ArrayList<String>();
   for(var input:inputs){
    var call=dial.createQueued(identity,input);String id=call.get("id").toString();ids.add(id);
    outbox.enqueue(identity.tenant(),"dial_call",id,"dial:"+id+":1",Map.of("call_id",id,"attempt",0));
   }
   var response=JsonSupport.map("batch_id",row.get("id"),"call_ids",ids,"accepted_count",ids.size(),"status","queued","status_url","/api/v1/calls/batches/"+row.get("id"));
   if(ids.size()==1)response.put("call_id",ids.get(0));
   jdbc.update("UPDATE dial_async_batch SET response_json=? WHERE tenant_id=? AND idempotency_key=?",json.json(response),identity.tenant(),key);
   return response;
  });
 }
 @SuppressWarnings("unchecked")
 Map<String,Object> status(Identity identity,UUID id){
  enabled();var rows=jdbc.queryForList("SELECT actor,response_json FROM dial_async_batch WHERE tenant_id=? AND id=?",identity.tenant(),id.toString());
  if(rows.isEmpty())throw new ApiError(404,"batch not found");var row=rows.get(0);
  if(!actor(identity).equals(row.get("actor"))&&!(identity.user()!=null&&"admin".equals(identity.user().get("role"))))throw new ApiError(403,"batch belongs to a different caller");
  if(row.get("response_json")==null)throw new ApiError(503,"batch commit is not confirmed");
  var response=json.object(row.get("response_json").toString());var counts=new TreeMap<String,Integer>();var calls=new ArrayList<Map<String,Object>>();
  for(Object callId:(List<?>)response.get("call_ids")){
   var call=store.require("callsession",identity.tenant(),callId);
   counts.merge(call.get("status").toString(),1,Integer::sum);
   calls.add(JsonSupport.map("call_id",callId,"status",call.get("status"),"reason",call.get("last_error")));
  }
  response.put("counts",counts);response.put("calls",calls);return response;
 }
}
