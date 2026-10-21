package com.duozhi.outbound;

import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Allocate a monotonic K fence, then wait for a durable gateway installation
 * acknowledgement outside this transaction. This never authorizes from TTL alone. */
@Service
public class DialAuthorizationService {
 public record Request(Map<String,Object> call,Map<String,Object> payload){}
 private final JdbcTemplate jdbc;private final Transactions tx;private final Config config;private final JsonSupport json;
 @org.springframework.beans.factory.annotation.Autowired private Store store;
 public DialAuthorizationService(JdbcTemplate jdbc,Transactions tx,Config config,JsonSupport json){this.jdbc=jdbc;this.tx=tx;this.config=config;this.json=json;int protocol=config.number("JAVA_DIAL_AUTH_PROTOCOL",1);if(protocol<1||protocol>2)throw new IllegalArgumentException("unsupported dial authorization protocol");if(protocol==2&&config.get("VOICE_COMMAND_SECRET","").length()<32)throw new IllegalArgumentException("dial protocol 2 requires an independent strong signing secret");}
 void prepare(List<Request> requests){
  if(requests.isEmpty())return;
  // This transaction only locks journal/reservation identities. Conditional
  // owner/fence writes provide its authority; it never acquires call resources.
  tx.run(()->{
   TaskWorker.permitted();
   var control=jdbc.queryForMap("SELECT phase,epoch FROM dial_admission_control WHERE id=1 FOR SHARE");
   long tenant=CallLifecycle.tenant(requests.get(0).call());
   var ids=requests.stream().map(r->r.call().get("id").toString()).distinct().sorted().toList();var args=new ArrayList<Object>();args.add(tenant);args.addAll(ids);
   String in=String.join(",",Collections.nCopies(ids.size(),"?"));
   var journals=new HashMap<String,Map<String,Object>>();for(var row:jdbc.queryForList("SELECT * FROM dial_command_journal WHERE tenant_id=? AND call_id IN ("+in+") ORDER BY call_id,attempt FOR UPDATE",args.toArray()))journals.put(row.get("call_id")+":"+row.get("attempt"),row);
   var reservations=new HashMap<String,Map<String,Object>>();for(var row:jdbc.queryForList("SELECT * FROM dial_reservation WHERE tenant_id=? AND call_id IN ("+in+") AND state='RESERVED' ORDER BY call_id,attempt FOR UPDATE",args.toArray()))if(reservations.put(row.get("call_id")+":"+row.get("attempt"),row)!=null)throw new ApiError(503,"multiple live reservations for one dial identity");
   var journalWrites=new ArrayList<Object[]>();var reservationWrites=new ArrayList<Object[]>();
   for(var request:requests){
    var call=request.call();if(CallLifecycle.tenant(call)!=tenant)throw new IllegalArgumentException("authorization cohort crosses tenants");
    String key=call.get("id")+":"+call.get("attempts");var journal=journals.get(key);var reservation=reservations.get(key);
    if(journal==null||reservation==null||((Number)journal.get("protocol_version")).intValue()!=2)throw new TaskWorker.Deferred();
    String phase=control.get("phase").toString();
    if(!phase.equals("RUNNING")&&!(phase.equals("ADMISSION_CLOSED")&&jdbc.queryForObject("SELECT COUNT(*) FROM dial_drain_member WHERE epoch=? AND tenant_id=? AND call_id=? AND attempt=?",Long.class,control.get("epoch"),tenant,call.get("id").toString().replace("-",""),call.get("attempts"))==1))throw new TaskWorker.Deferred();
    var semantics=new LinkedHashMap<>(request.payload());semantics.remove("authorization");String digest=JsonSupport.sha(json.canonical(semantics,false));
    if(journal.get("wire_digest")!=null&&!journal.get("wire_digest").equals(digest))throw new ApiError(409,"wire semantics differ from the frozen dial command");
    String owner=TaskWorker.ownerToken();if(owner==null)owner=Objects.toString(call.computeIfAbsent("_dial_owner",k->"request:"+UUID.randomUUID()),"");
    long epoch=((Number)control.get("epoch")).longValue(),fence=((Number)reservation.get("fence")).longValue();
    boolean change=!owner.equals(reservation.get("owner"))||((Number)reservation.get("epoch")).longValue()!=epoch;
    if(change&&!owner.equals(reservation.get("owner"))&&ownerAlive(reservation))throw new TaskWorker.Deferred();
    if(change)fence=Math.addExact(((Number)journal.get("current_fence")).longValue(),1);
    else if(fence!=((Number)journal.get("current_fence")).longValue())throw new ApiError(503,"dial owner fence is inconsistent");
    long expires=System.currentTimeMillis()/1000+60;
    var grant=JsonSupport.map("reservation_id",reservation.get("reservation_id"),"epoch",epoch,"owner",owner,"fence",fence,"expires_at",expires,"semantic_digest",digest);
    request.payload().put("authorization",grant);
    journalWrites.add(new Object[]{digest,fence,tenant,call.get("id"),call.get("attempts")});
    reservationWrites.add(new Object[]{owner,fence,epoch,Store.now().plusSeconds(Math.max(2,config.number("TASK_LEASE_SEC",30))),change?"INSTALLING":reservation.get("authorization_state"),reservation.get("reservation_id")});
   }
   requireCount(jdbc.update("UPDATE dial_command_journal j JOIN ("+derived(journalWrites,List.of("digest","fence","tid","cid","att"))+") r ON j.tenant_id=r.tid AND j.call_id=r.cid AND j.attempt=r.att SET j.wire_digest=r.digest,j.current_fence=r.fence",flatten(journalWrites)),journalWrites.size());
   requireCount(jdbc.update("UPDATE dial_reservation d JOIN ("+derived(reservationWrites,List.of("owner","fence","epoch","expires","auth_state","rid"))+") r ON d.reservation_id=r.rid SET d.owner=r.owner,d.fence=r.fence,d.epoch=r.epoch,d.owner_expires_at=r.expires,d.authorization_state=r.auth_state WHERE d.state='RESERVED'",flatten(reservationWrites)),reservationWrites.size());return null;
  });
 }
 private boolean ownerAlive(Map<String,Object> row){
  String owner=Objects.toString(row.get("owner"),"");
  if(owner.equals("lock_compat"))return false;
  Object cutoff=Store.now().minusSeconds(Math.max(2,config.number("TASK_LEASE_SEC",30)));
  if(owner.startsWith("batch:"))return jdbc.queryForObject("SELECT COUNT(*) FROM taskoutbox WHERE tenant_id=? AND lease_token=? AND state='PROCESSING' AND locked_at>=?",Long.class,row.get("tenant_id"),owner.substring(6),cutoff)>0;
  if(owner.startsWith("task:")){
   var parts=owner.split(":",3);
   return parts.length==3&&jdbc.queryForObject("SELECT COUNT(*) FROM taskoutbox WHERE tenant_id=? AND id=? AND lease_token=? AND state='PROCESSING' AND locked_at>=?",Long.class,row.get("tenant_id"),parts[1],parts[2],cutoff)>0;
  }
  Object expiry=row.get("owner_expires_at");if(expiry==null)return true;
  LocalDateTime until=expiry instanceof java.sql.Timestamp stamp?stamp.toLocalDateTime():expiry instanceof LocalDateTime time?time:null;
  if(until==null)throw new ApiError(503,"dial owner expiry is invalid");return until.isAfter(Store.now());
 }
 @SuppressWarnings("unchecked")
 void acknowledge(List<Request> requests,List<com.duozhi.outbound.embedded.VoiceController.CommandResult> replies){
  if(requests.size()!=replies.size())throw new DialService.PendingConfirmation();
  tx.run(()->{
   TaskWorker.permitted();var writes=new ArrayList<Object[]>();
   for(int index=0;index<requests.size();index++){
    var grant=(Map<String,Object>)requests.get(index).payload().get("authorization");var reply=replies.get(index);
    if(reply.status()!=200||reply.response()==null||!"authorization_installed".equals(reply.response().get("result")))throw new DialService.PendingConfirmation();
    for(String field:List.of("reservation_id","epoch","owner","fence","semantic_digest"))if(!Objects.toString(grant.get(field),"").equals(Objects.toString(reply.response().get(field),"")))throw new ApiError(409,"gateway installed a different dial authorization");
    writes.add(new Object[]{grant.get("reservation_id"),grant.get("owner"),grant.get("fence"),grant.get("epoch")});
   }
   if(!writes.isEmpty())requireCount(jdbc.update("UPDATE dial_reservation d JOIN ("+derived(writes,List.of("rid","owner","fence","epoch"))+") r ON d.reservation_id=r.rid AND d.owner=r.owner AND d.fence=r.fence AND d.epoch=r.epoch SET d.authorization_state='INSTALLED' WHERE d.state='RESERVED'",flatten(writes)),writes.size());return null;
  });
 }
 private static void requireCount(int count,int expected){if(count!=expected)throw new IllegalStateException("dial authorization update lost its reservation");}
 private static String derived(List<Object[]> rows,List<String> fields){String select="SELECT "+String.join(",",fields.stream().map(f->"? AS `"+f+"`").toList());return String.join(" UNION ALL ",Collections.nCopies(rows.size(),select));}
 private static Object[] flatten(List<Object[]> rows){var parameters=new ArrayList<Object>();for(var row:rows)Collections.addAll(parameters,row);return parameters.toArray();}
 boolean rejected(Map<String,Object> call,Map<String,Object> proof){
  if(proof==null||!"rejected_before_dispatch".equals(proof.get("result")))return false;
  var signed=new LinkedHashMap<>(proof);String supplied=Objects.toString(signed.remove("proof_signature"),"");
  String secret=config.get("VOICE_COMMAND_SECRET","");String expected=JsonSupport.hmac(secret,json.canonical(signed,false).getBytes(java.nio.charset.StandardCharsets.UTF_8));
  if(secret.length()<32||!java.security.MessageDigest.isEqual(supplied.getBytes(java.nio.charset.StandardCharsets.US_ASCII),expected.getBytes(java.nio.charset.StandardCharsets.US_ASCII)))throw new ApiError(409,"unverified rejection proof");
  for(var identity:Map.of("tenant_id",call.get("tenant_id"),"call_id",call.get("id"),"attempt",call.get("attempts")).entrySet())if(!Objects.toString(identity.getValue(),"").equals(Objects.toString(proof.get(identity.getKey()),"")))throw new ApiError(409,"rejection proof identifies another dial command");
  if(!"cancelled_before_dispatch".equals(proof.get("reason"))||!Objects.toString(proof.get("proof_id"),"").matches("[0-9a-f-]{36}"))throw new ApiError(409,"invalid rejection proof identity");
  return tx.serialized(()->{
   tx.platform();var latest=tx.call(call.get("id"));
   var journal=jdbc.queryForMap("SELECT * FROM dial_command_journal WHERE tenant_id=? AND call_id=? AND attempt=? FOR UPDATE",call.get("tenant_id"),call.get("id"),call.get("attempts"));
   if(journal.get("rejection_proof")!=null&&json.object(journal.get("rejection_proof").toString()).get("proof_id").equals(proof.get("proof_id")))return true;
   if(journal.get("state").equals("ACCEPTED")||!Objects.equals(journal.get("wire_digest"),proof.get("semantic_digest")))throw new ApiError(409,"rejection conflicts with the frozen command");
   var rows=jdbc.queryForList("SELECT * FROM dial_reservation WHERE reservation_id=? AND tenant_id=? AND call_id=? AND attempt=? FOR UPDATE",proof.get("reservation_id"),call.get("tenant_id"),call.get("id"),call.get("attempts"));
   if(rows.size()!=1)throw new ApiError(409,"rejection has no original reservation");var reservation=rows.get(0);
   for(String field:List.of("owner","epoch","fence"))if(!Objects.toString(reservation.get(field),"").equals(Objects.toString(proof.get(field),"")))throw new ApiError(409,"rejection fence differs from its original reservation");
   if(!reservation.get("state").equals("RESERVED"))throw new ApiError(409,"rejection reservation was already released");
   if(latest==null||!latest.get("status").equals("dialing")||CallLifecycle.number(latest,"attempts")!=CallLifecycle.number(call,"attempts"))throw new ApiError(409,"rejection cannot roll back an advanced call");
   jdbc.update("UPDATE dial_reservation SET authorization_state='REJECTED' WHERE reservation_id=?",proof.get("reservation_id"));
   jdbc.update("UPDATE dial_command_journal SET state='REJECTED',rejection_proof=? WHERE tenant_id=? AND call_id=? AND attempt=?",json.canonical(proof,false),call.get("tenant_id"),call.get("id"),call.get("attempts"));
   store.update("callsession",CallLifecycle.tenant(call),call.get("id"),JsonSupport.map("status","queued","attempts",CallLifecycle.number(call,"attempts")-1,"started_at",null,"finished_at",null,"next_attempt_at",Store.now().plusSeconds(1),"telephony_call_id",null,"ai_session_id",null,"last_error","gateway proved cancellation before dispatch; awaiting same-identity readmission"));
   store.insert("callevent",JsonSupport.map("call_session_id",call.get("id"),"event_type","dial_rejection_proven","source","telephony","payload",json.canonical(proof,false)));return true;
  });
 }
}
