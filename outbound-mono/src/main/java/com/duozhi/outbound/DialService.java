package com.duozhi.outbound;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class DialService {
    private final Store store;
    private final Transactions tx;
    private final Config config;
    private final Settings settings;
    private final CallPolicy policy;
    private final CallLifecycle lifecycle;
    private final TelephonyClient client;
    private final Outbox outbox;
    private final JsonSupport json;
    private final AgentMedia media;
    private final GatewayCluster gateways;
    private final CallbackInbox inbox;
    private final ConversationPolicy conversation;
    private final HumanRouting routing;
    public DialService(Store store, Transactions tx, Config config, Settings settings, CallPolicy policy,
            CallLifecycle lifecycle, TelephonyClient client, Outbox outbox, JsonSupport json, AgentMedia media, GatewayCluster gateways, CallbackInbox inbox, ConversationPolicy conversation, HumanRouting routing) {
        this.store = store; this.tx = tx; this.config = config; this.settings = settings; this.policy = policy;
        this.lifecycle = lifecycle; this.client = client; this.outbox = outbox; this.json = json; this.media = media; this.gateways = gateways; this.inbox = inbox; this.conversation = conversation;this.routing=routing;
    }
    public void available() {
        if (!config.flag("JAVA_DISPATCH_ENABLED", false)) throw new ApiError(503, "Java dispatch is not enabled");
    }
    public Map<String, Object> create(Identity identity, Map<String, Object> input) {
        available();
        Map<String, Object> call = tx.serialized(() -> {
            tx.platform(); tx.tenant(identity.tenant());
            Map<String, Object> campaign = input.get("campaign_id") == null ? null : store.require("campaign", identity.tenant(), input.get("campaign_id"));
            Map<String, Object> contact = input.get("contact_id") == null ? null : store.require("contact", identity.tenant(), input.get("contact_id"));
            Object rawPhone = input.get("phone");
            if ((rawPhone == null || rawPhone.toString().isEmpty()) && contact != null) rawPhone = contact.get("phone");
            String phone = Contacts.phone(rawPhone), reason = policy.check(identity.tenant(), phone, campaign != null, null);
            if (!reason.isEmpty()) throw new ApiError(403, reason);
            if (identity.user() != null && "agent".equals(identity.user().get("role")) && "human_only".equals(input.get("mode")) && config.flag("WEBRTC_ENABLED", false) && !media.registered(identity.tenant(), identity.userId()))
                throw new ApiError(409, "agent browser SIP endpoint is not registered");
            Map<String, Object> row = JsonSupport.map("tenant_id", identity.tenant(), "phone", phone, "mode", input.get("mode"), "campaign_id", input.get("campaign_id"),
                    "contact_id", input.get("contact_id"), "max_attempts", input.getOrDefault("max_attempts", 1), "status", "queued", "voice_ai_pipeline", "pending");
            if (identity.user() != null && "agent".equals(identity.user().get("role"))) row.put("human_agent_id", identity.userId());
            if (campaign != null && campaign.get("script_flow_version_id") != null) {
                Map<String, Object> flow = store.require("scriptflowversion", identity.tenant(), campaign.get("script_flow_version_id"));
                if ("published".equals(flow.get("status"))) {
                    row.put("script_flow_version_id", flow.get("id"));
                    for (Object raw : (List<?>)json.object(flow.get("graph_json").toString()).get("nodes")) {
                        Map<?, ?> node = (Map<?, ?>)raw;
                        if ("start".equals(node.get("type"))) { row.put("flow_node_key", node.get("id")); break; }
                    }
                }
            }
            return store.insert("callsession", row);
        });
        dispatch(call.get("id"));
        return store.require("callsession", identity.tenant(), call.get("id"));
    }
    private long active(Long tenant,Object line,Object campaign) {
        var batch=DialBatchScope.current();String key="active:"+tenant+":"+line+":"+campaign;if(batch!=null&&batch.counts.containsKey(key))return batch.counts.get(key);
        var filters=JsonSupport.map("line",line,"campaign",campaign);long count;if(tenant==null)count=store.sumAcross("callsession","activeDialCount",filters);else{filters.put("tenantId",tenant);count=store.scalar("callsession","activeDialCount",filters);}if(batch!=null)batch.counts.put(key,count);return count;
    }
    private boolean productHours(Map<String, Object> call) {
        var batch=DialBatchScope.current();String key=call.get("tenant_id")+":"+call.get("campaign_id");if(batch!=null&&batch.hours.containsKey(key))return batch.hours.get(key);
        boolean result=productHoursUncached(call);if(batch!=null)batch.hours.put(key,result);return result;
    }
    private boolean productHoursUncached(Map<String, Object> call) {
        Map<String, Object> version = store.one("scenarioversion","sourceDialService62_1",Store.queryParameters(0,CallLifecycle.tenant(call),call.get("campaign_id")));
        if (version == null) return true;
        Map<String, Object> value = json.object(version.get("policy_json").toString());
        ZonedDateTime local = (DialBatchScope.current()==null?Instant.now():DialBatchScope.current().instant).atZone(ZoneId.of(Objects.toString(value.get("timezone"), "Asia/Shanghai")));
        Collection<?> weekdays = (Collection<?>)value.getOrDefault("weekdays", Arrays.asList(0, 1, 2, 3, 4, 5, 6));
        Collection<?> holidays = (Collection<?>)value.getOrDefault("holidays", Collections.emptyList());
        return weekdays.contains(local.getDayOfWeek().getValue() - 1) && !holidays.contains(local.toLocalDate().toString())
                && local.getHour() >= JsonSupport.integer(value.get("start_hour"), 9) && local.getHour() < JsonSupport.integer(value.get("end_hour"), 20);
    }
    private Map<String, Object> claim(Object id) {
        return tx.serialized(() -> {
            tx.platform();
            var batch=DialBatchScope.current();Map<String, Object> initial = batch!=null?batch.calls.get(id.toString()):store.locate("callsession",store.encode("callsession", "id", id),false);
            if (initial == null) return null;
            long tenant = CallLifecycle.tenant(initial);
            Map<String, Object> tenantRow = batch!=null?batch.tenants.computeIfAbsent(""+tenant,k->tx.tenant(tenant)):tx.tenant(tenant), call = batch!=null?initial:tx.call(id);
            if (!Boolean.TRUE.equals(tenantRow.get("enabled")) || !Values.set("created", "queued").contains(call.get("status"))) return null;
            if (CallLifecycle.number(call, "attempts") >= CallLifecycle.number(call, "max_attempts")) {
                store.update("callsession", tenant, id, JsonSupport.map("status", "failed", "next_attempt_at", null, "last_error", "reach max attempts")); lifecycle.completeCampaign(call); return null;
            }
            if (!productHours(call) || active(null,null,null) >= Math.max(1, config.number("OUTBOUND_PLATFORM_MAX_CONCURRENT", 20))) return null;
            // Admission holds the platform lock: two campaigns/direct calls cannot
            // both acquire the same tenant/phone while a previous call is active.
            var facts=batch==null?null:batch.facts.get(tenant+":"+call.get("phone"));
            if((facts==null?store.scalar("callsession","activePhone",JsonSupport.map("tenantId",tenant,"phone",call.get("phone"),"id",store.encode("callsession","id",id))):((Number)facts.get("active")).longValue())>0)return null;
            if (config.flag("CALLBACK_INBOX_ENABLED", false) && !(batch==null?Boolean.TRUE.equals(inbox.snapshot().get("ready")):batch.hours.computeIfAbsent("inbox-ready",k->Boolean.TRUE.equals(inbox.snapshot().get("ready"))))) return null;
            String reason = policy.check(tenant, call.get("phone").toString(), call.get("campaign_id") != null, id);
            Map<String, Object> compliance = settings.get(tenant, "compliance");
            ZonedDateTime local = (batch==null?Instant.now():batch.instant).atZone(ZoneId.of(Objects.toString(compliance.get("timezone"), "Asia/Shanghai")));
            Object day = store.encode("callsession", "started_at", LocalDateTime.ofInstant(local.toLocalDate().atStartOfDay(local.getZone()).toInstant(), ZoneOffset.UTC));
            String dailyKey="daily:"+tenant+":"+day;
            long legacy=batch!=null&&batch.counts.containsKey(dailyKey)?batch.counts.get(dailyKey):store.value("callsession","rawDialService88_0",Store.queryParameters(0,tenant,day),Long.class);
            long recorded=batch!=null&&batch.counts.containsKey(dailyKey)?batch.counts.get(dailyKey):store.value("callevent","rawDialService89_1",Store.queryParameters(0,tenant,day),Long.class);
            if(batch!=null)batch.counts.put(dailyKey,Math.max(legacy,recorded));
            int daily = Math.max(1, Math.min(config.number("OUTBOUND_DAILY_CALL_LIMIT", 10000), JsonSupport.integer(compliance.get("max_calls_per_day"), 10000)));
            if (Math.max(legacy, recorded) >= daily) reason = "tenant_daily_call_limit";
            if (!reason.isEmpty()) { store.update("callsession", tenant, id, JsonSupport.map("status", "failed", "next_attempt_at", null, "last_error", "precheck failed: " + reason)); lifecycle.completeCampaign(call); return null; }
            int tenantLimit = Math.min(Math.max(1, config.number("OUTBOUND_PLATFORM_MAX_CONCURRENT", 20)), Math.max(1, JsonSupport.integer(settings.get(tenant, "capacity").get("max_concurrent_calls"), 20)));
            List<Map<String, Object>> lines = batch==null?store.query("telephonyline","sourceDialService94_3",Store.queryParameters(0,tenant,true)):batch.lines.computeIfAbsent(tenant,k->store.query("telephonyline","sourceDialService94_3",Store.queryParameters(0,tenant,true)));
            Map<String, Object> selected = null; double best = Double.MAX_VALUE; int priority = Integer.MAX_VALUE, lineCapacity = 0;
            for (Map<String, Object> line : lines) {
                int capacity = Math.max(1, CallLifecycle.number(line, "max_concurrency")); lineCapacity += capacity;
                long occupied = active(tenant,line.get("id"),null);
                double load = occupied / (double)(capacity * Math.max(1, CallLifecycle.number(line, "weight")));
                int linePriority = CallLifecycle.number(line, "priority");
                if (occupied < capacity && (linePriority < priority || linePriority == priority && load < best)) { selected = line; priority = linePriority; best = load; }
            }
            if (!lines.isEmpty()) { if (selected == null) return null; tenantLimit = Math.min(tenantLimit, lineCapacity); }
            else if ("tenant".equals(config.get("TELEPHONY_PROVIDER", "mock"))) return null;
            if (active(tenant,null,null) >= tenantLimit || !routing.permits(call)) return null;
            Map<String, Object> campaign = null;
            if (call.get("campaign_id") != null) {
                Object campaignId=call.get("campaign_id");campaign = batch==null?store.require("campaign", tenant, campaignId):batch.campaigns.computeIfAbsent(tenant+":"+campaignId,k->store.require("campaign",tenant,campaignId));
                if (!"running".equals(campaign.get("status")) || !Boolean.TRUE.equals(campaign.get("dispatch_enabled")) || active(tenant,null,campaign.get("id")) >= Math.max(1, CallLifecycle.number(campaign, "concurrency"))) return null;
            }
            Map<String, Object> changes = JsonSupport.map("status", "dialing", "attempts", CallLifecycle.number(call, "attempts") + 1, "started_at", Store.now(), "finished_at", null,
                    "next_attempt_at", null, "last_error", null, "telephony_line_id", selected == null ? null : selected.get("id"), "gateway_node_id", null, "gateway_endpoint", null, "telephony_call_id", null, "ai_session_id", null);
            Map<String,Object> gateway;
            try { gateway = gateways.choose(tenant, selected == null ? null : selected.get("id"), 1.0); }
            catch (TaskWorker.Deferred wait) { return null; }
            if (gateway != null) { changes.put("gateway_node_id", gateway.get("id")); changes.put("gateway_endpoint", gateway.get("endpoint")); }
            String pipeline = Objects.toString(call.get("voice_ai_pipeline"), "pending");
            if (!Values.set("legacy", "pipecat").contains(pipeline)) {
                Map<String, Object> ai = settings.get(tenant, "ai");
                String override = campaign == null ? "inherit" : Objects.toString(campaign.get("voice_ai_pipeline"), "inherit");
                if (Values.set("legacy", "pipecat").contains(override)) pipeline = override;
                else {
                    long bucket = (java.nio.ByteBuffer.wrap(JsonSupport.digest(call.get("id").toString())).getInt() & 0xffffffffL) % 100;
                    pipeline = "pipecat".equals(ai.get("voice_ai_pipeline")) || bucket < Math.max(0, Math.min(100, JsonSupport.integer(ai.get("pipecat_canary_percent"), 0))) ? "pipecat" : "legacy";
                }
            }
            changes.put("voice_ai_pipeline", pipeline);
            Map<String, Object> probe = new LinkedHashMap<>(call); probe.putAll(changes); client.validate(probe);
            call = batch==null?store.update("callsession", tenant, id, changes):store.patchLoaded("callsession",call,changes);
            conversation.state(call);
            store.insert("callevent", JsonSupport.map("call_session_id", id, "event_type", "dial_attempt_claimed", "source", "platform", "payload", json.json(JsonSupport.map("attempt", call.get("attempts")))));
            if(batch!=null){batch.calls.put(id.toString(),call);batch.counts.merge(dailyKey,1L,Long::sum);if(facts!=null){facts.put("active",((Number)facts.get("active")).longValue()+1);facts.put("legacy_attempts",((Number)facts.get("legacy_attempts")).longValue()+1);facts.put("event_attempts",((Number)facts.get("event_attempts")).longValue()+1);}for(var key:new ArrayList<>(batch.counts.keySet()))if(key.equals("active:null:null:null")||key.equals("active:"+tenant+":null:null")||key.equals("active:"+tenant+":"+call.get("telephony_line_id")+":null")||call.get("campaign_id")!=null&&key.equals("active:"+tenant+":null:"+call.get("campaign_id")))batch.counts.merge(key,1L,Long::sum);}
            return call;
        });
    }
    public boolean dispatch(Object id) {
        available();
        Map<String, Object> call = claim(id);
        if (call == null) return false;
        return sendClaimed(call,metadataFor(call));
    }
    Map<String,Object> metadataFor(Map<String,Object> call){
        long tenant = CallLifecycle.tenant(call); int attempt = CallLifecycle.number(call, "attempts");
        Map<String, Object> ai = settings.get(tenant, "ai"), compliance = settings.get(tenant, "compliance");
        String base = config.get("TELEPHONY_WEBHOOK_BASE", "http://localhost:8000").replaceAll("/+$", "");
        Map<String, Object> metadata = JsonSupport.map("tenant_id", tenant, "campaign_id", call.get("campaign_id"), "contact_id", call.get("contact_id"), "mode", call.get("mode"), "human_agent_id", call.get("human_agent_id"), "attempt", attempt, "telephony_line_id",call.get("telephony_line_id"),"gateway_node_id", call.get("gateway_node_id"));
        for (String kind : Arrays.asList("transcript", "speech", "media", "recording", "dtmf")) metadata.put(kind + "_webhook_url", base + "/api/v1/webhooks/telephony/" + kind);
        for (String key : Arrays.asList("asr_provider", "tts_provider", "voice", "language")) metadata.put(key, ai.get(key));
        metadata.put("recording_notice", compliance.get("recording_notice")); metadata.put("recording_notice_text", compliance.get("recording_notice_text"));
        Map<String,Object> scenario = tx.serialized(() -> { tx.platform(); return conversation.policy(conversation.state(call)); });
        scenario.keySet().removeIf(key -> key.startsWith("_"));
        metadata.put("scenario_policy", scenario);
        metadata.put("voice_ai_pipeline", call.get("voice_ai_pipeline"));
        var batch=DialBatchScope.current();
        if (call.get("telephony_line_id") != null) metadata.put("caller_id", batch==null?store.require("telephonyline", tenant, call.get("telephony_line_id")).get("caller_id"):batch.lines.getOrDefault(tenant,List.of()).stream().filter(l->l.get("id").toString().equals(call.get("telephony_line_id").toString())).findFirst().orElseThrow(()->new ApiError(409,"claimed line disappeared")).get("caller_id"));
        if (call.get("campaign_id") != null) {
            Map<String, Object> campaign = batch==null?store.require("campaign", tenant, call.get("campaign_id")):batch.campaigns.get(tenant+":"+call.get("campaign_id"));
            metadata.put("recording_enabled", campaign.get("recording_enabled")); metadata.put("hangup_sms_enabled", campaign.get("hangup_sms_enabled"));
        }
        return metadata;
    }
    /** Called after durable dial tasks are leased. All reads use this transaction's
     * facts; all new reservations and frozen command payloads commit before send. */
    List<Map<String,Object>> prepareTasks(List<Map<String,Object>> tasks,DialBatchStore db){
        available();long started=System.nanoTime();return tx.serialized(()->{long acquired=System.nanoTime();TaskWorker.permitted();long tenant=CallLifecycle.tenant(tasks.get(0));
            try(var scope=new DialBatchScope()){
                var ids=tasks.stream().map(t->t.get("aggregate_id").toString()).toList();db.prepare(scope,tenant,ids);long facts=System.nanoTime();
                scope.tenants.put(""+tenant,tx.tenant(tenant));settings.get(tenant,"ai");settings.get(tenant,"capacity");settings.get(tenant,"compliance");
                scope.lines.put(tenant,store.query("telephonyline","sourceDialService94_3",Store.queryParameters(0,tenant,true)));
                active(null,null,null);active(tenant,null,null);for(var line:scope.lines.get(tenant))active(tenant,line.get("id"),null);
                for(var c:scope.calls.values()){productHours(c);conversation.current(tenant,c.get("campaign_id"));if(c.get("campaign_id")!=null){Object campaignId=c.get("campaign_id");scope.campaigns.computeIfAbsent(tenant+":"+campaignId,k->store.require("campaign",tenant,campaignId));active(tenant,null,campaignId);}}
                // Resolve shared readiness and daily counters before buffering.
                if(config.flag("CALLBACK_INBOX_ENABLED",false))scope.hours.put("inbox-ready",Boolean.TRUE.equals(inbox.snapshot().get("ready")));
                var compliance=settings.get(tenant,"compliance");var local=scope.instant.atZone(ZoneId.of(Objects.toString(compliance.get("timezone"),"Asia/Shanghai")));Object day=store.encode("callsession","started_at",LocalDateTime.ofInstant(local.toLocalDate().atStartOfDay(local.getZone()).toInstant(),ZoneOffset.UTC));
                scope.counts.put("daily:"+tenant+":"+day,Math.max(store.value("callsession","rawDialService88_0",Store.queryParameters(0,tenant,day),Long.class),store.value("callevent","rawDialService89_1",Store.queryParameters(0,tenant,day),Long.class)));
                long warmed=System.nanoTime();long[] loopEnd=new long[1];var prepared=store.bufferWrites(()->{
                    var result=new ArrayList<Map<String,Object>>();
                    for(var task:tasks){TaskWorker.permitted();var payload=json.object(task.get("payload_json").toString());var current=scope.calls.get(task.get("aggregate_id").toString());Map<String,Object> call=null,metadata=null;
                        if(payload.get("_dial_claimed") instanceof Map<?,?> frozen){
                            var saved=(Map<String,Object>)frozen;var old=(Map<String,Object>)saved.get("call");
                            if(current!=null&&!CallLifecycle.TERMINAL.contains(current.get("status"))&&CallLifecycle.number(current,"attempts")==CallLifecycle.number(old,"attempts")){call=old;metadata=(Map<String,Object>)saved.get("metadata");}
                        }else if(current!=null&&"queued".equals(current.get("status"))&&CallLifecycle.number(current,"attempts")==JsonSupport.integer(payload.get("attempt"),-1)){
                            call=claim(current.get("id"));if(call!=null){metadata=metadataFor(call);var frozen=new LinkedHashMap<String,Object>();for(String key:List.of("id","tenant_id","phone","attempts","telephony_line_id","gateway_node_id","gateway_endpoint","voice_ai_pipeline"))frozen.put(key,call.get(key));payload.put("_dial_claimed",JsonSupport.map("call",frozen,"metadata",metadata));store.writeLocked("taskoutbox",tenant,task.get("id"),JsonSupport.map("payload_json",json.json(payload)));}
                        }
                        boolean deferred=call==null&&current!=null&&"queued".equals(current.get("status"));result.add(JsonSupport.map("task",task,"payload",payload,"call",call,"metadata",metadata,"deferred",deferred));
                    }loopEnd[0]=System.nanoTime();return result;
                });
                if(config.flag("JAVA_DIAL_PROFILE",false))org.slf4j.LoggerFactory.getLogger(DialService.class).info("dial_prepare size={} lock_wait_ms={} facts_ms={} config_ms={} loop_ms={} flush_ms={}",tasks.size(),(acquired-started)/1_000_000.0,(facts-acquired)/1_000_000.0,(warmed-facts)/1_000_000.0,(loopEnd[0]-warmed)/1_000_000.0,(System.nanoTime()-loopEnd[0])/1_000_000.0);return prepared;
            }
        });
    }
    boolean sendClaimed(Map<String,Object> call,Map<String,Object> metadata){
        Object id=call.get("id");long tenant=CallLifecycle.tenant(call);int attempt=CallLifecycle.number(call,"attempts");String base=config.get("TELEPHONY_WEBHOOK_BASE","http://localhost:8000").replaceAll("/+$","");
        try {
            Map<String, Object> response = client.command(call, "dial", JsonSupport.map("phone", call.get("phone"), "caller_id", metadata.getOrDefault("caller_id", ""), "webhook_url", base + "/api/v1/webhooks/telephony/status", "metadata", metadata));
            tx.serialized(() -> {
                tx.platform(); Map<String, Object> latest = tx.call(id);
                if (latest != null && CallLifecycle.number(latest, "attempts") == attempt)
                    store.update("callsession", tenant, id, JsonSupport.map("telephony_call_id", response.get("provider_call_id"), "ai_session_id", response.get("provider_call_id"), "voice_ai_pipeline", metadata.get("voice_ai_pipeline")));
                if(latest!=null&&CallLifecycle.number(latest,"attempts")==attempt&&store.scalar("callevent","dialAcceptedAttempt",JsonSupport.map("tenantId",tenant,"id",store.encode("callsession","id",id),"attempt",attempt))==0)
                    store.insert("callevent",JsonSupport.map("call_session_id",id,"event_type","dial_command_accepted","source","telephony","payload",json.json(JsonSupport.map("attempt",attempt,"provider_call_id",response.get("provider_call_id")))));
                return null;
            });
            if (client.mock(call)) {
                tx.run(() -> {
                    for (String state : Arrays.asList("answered", "completed")) {
                        Map<String, Object> task = outbox.enqueue(tenant, "mock_status", id, "mock:" + id + ":" + attempt + ":" + state,
                                JsonSupport.map("call_id", id, "kind", "status", "payload", JsonSupport.map("status", state, "attempt", attempt, "event_id", "mock:" + id + ":" + attempt + ":" + state)));
                        store.update("taskoutbox", tenant, task.get("id"), JsonSupport.map("available_at", Store.now().plusSeconds("answered".equals(state) ? 1 : 3)));
                    }
                    return null;
                });
            }
            return true;
        } catch (TelephonyClient.Failure e) {
            if (e.rejectedBeforeAdmission) {
                mutateAttempt(call, JsonSupport.map("status", "queued", "attempts", attempt - 1, "started_at", null, "telephony_call_id", null, "ai_session_id", null, "last_error", "gateway capacity/CPS deferred before admission")); return false;
            }
            if (!e.definitive()) { mutateAttempt(call, JsonSupport.map("last_error", "dial outcome unknown; awaiting PBX reconciliation")); return true; }
            lifecycle.status(JsonSupport.map("call_id", id, "kind", "status", "payload", JsonSupport.map("attempt", attempt, "event_id", "dial-rejected:" + id + ":" + attempt, "status", "failed", "hangup_reason", e.getMessage())));
            return true;
        }
    }
    @SuppressWarnings("unchecked")
    void sendTasks(List<Map<String,Object>> entries,DialBatchStore db){
        var pending=new ArrayList<Map<String,Object>>();var routes=new HashMap<String,Boolean>();
        for(var entry:entries){var call=(Map<String,Object>)entry.get("call");if(call==null)continue;
            boolean mock=routes.computeIfAbsent(call.get("tenant_id")+":"+call.get("telephony_line_id")+":"+call.get("gateway_node_id"),k->client.mock(call));
            if(mock){try{if(!sendClaimed(call,(Map<String,Object>)entry.get("metadata"))){entry.put("deferred",true);((Map<String,Object>)entry.get("payload")).remove("_dial_claimed");}}catch(RuntimeException failure){entry.put("error",failure);}continue;}pending.add(entry);
        }
        if(pending.isEmpty())return;
        List<com.duozhi.outbound.embedded.VoiceController.CommandResult> replies;
        try{replies=client.dialBatch(pending);}catch(RuntimeException failure){for(var entry:pending)entry.put("error",failure);return;}
        if(replies.size()!=pending.size())throw new IllegalStateException("incomplete dial batch acknowledgement");
        for(int i=0;i<pending.size();i++){var entry=pending.get(i);var reply=replies.get(i);var call=(Map<String,Object>)entry.get("call");
            if(reply.status()==200){entry.put("response",reply.response());continue;}
            var failure=new TelephonyClient.Failure(reply.status(),reply.rejectedBeforeAdmission());
            if(failure.rejectedBeforeAdmission){mutateAttempt(call,JsonSupport.map("status","queued","attempts",CallLifecycle.number(call,"attempts")-1,"started_at",null,"telephony_call_id",null,"ai_session_id",null,"last_error","gateway capacity/CPS deferred before admission"));entry.put("deferred",true);((Map<String,Object>)entry.get("payload")).remove("_dial_claimed");}
            else if(failure.definitive())lifecycle.status(JsonSupport.map("call_id",call.get("id"),"payload",JsonSupport.map("attempt",call.get("attempts"),"event_id","dial-rejected:"+call.get("id")+":"+call.get("attempts"),"status","failed","hangup_reason",failure.getMessage())));
            else{mutateAttempt(call,JsonSupport.map("last_error","dial outcome unknown; awaiting idempotent retry/PBX reconciliation"));entry.put("error",failure);}
        }
        var successful=pending.stream().filter(e->e.containsKey("response")).toList();if(successful.isEmpty())return;
        tx.serialized(()->{TaskWorker.permitted();long tenant=CallLifecycle.tenant((Map<String,Object>)successful.get(0).get("call"));var ids=successful.stream().map(e->((Map<?,?>)e.get("call")).get("id").toString()).toList();
            var current=new HashMap<String,Map<String,Object>>();for(var raw:db.calls(tenant,ids))current.put(raw.get("id").toString(),raw);
            var acknowledged=new HashSet<String>();for(var ack:db.rows("dialAcks",JsonSupport.map("tenantId",tenant,"ids",ids)))acknowledged.add(ack.get("call_session_id")+":"+ack.get("attempt"));
            return store.bufferWrites(()->{for(var entry:successful){var call=(Map<String,Object>)entry.get("call");var latest=current.get(call.get("id").toString());if(latest==null||CallLifecycle.number(latest,"attempts")!=CallLifecycle.number(call,"attempts"))continue;
                var response=(Map<String,Object>)entry.get("response");store.writeLocked("callsession",tenant,call.get("id"),JsonSupport.map("telephony_call_id",response.get("provider_call_id"),"ai_session_id",response.get("provider_call_id")));
                if(acknowledged.add(call.get("id")+":"+call.get("attempts")))store.insert("callevent",JsonSupport.map("call_session_id",call.get("id"),"event_type","dial_command_accepted","source","telephony","payload",json.json(JsonSupport.map("attempt",call.get("attempts"),"provider_call_id",response.get("provider_call_id")))));
            }return null;});
        });
    }
    private void mutateAttempt(Map<String, Object> call, Map<String, Object> changes) {
        tx.serialized(() -> { tx.platform(); Map<String, Object> latest = tx.call(call.get("id"));
            if (latest != null && "dialing".equals(latest.get("status")) && CallLifecycle.number(latest, "attempts") == CallLifecycle.number(call, "attempts"))
                store.update("callsession", CallLifecycle.tenant(call), call.get("id"), changes);
            return null;
        });
    }
    public Map<String, Object> retry(Map<String, Object> call) {
        available();
        tx.serialized(() -> { tx.platform(); Map<String, Object> latest = tx.call(call.get("id"));
            if (!CallLifecycle.TERMINAL.contains(latest.get("status"))) throw new ApiError(409, "call is not in terminal state");
            if (CallLifecycle.number(latest, "attempts") >= CallLifecycle.number(latest, "max_attempts")) throw new ApiError(409, "reach max attempts");
            store.update("callsession", CallLifecycle.tenant(call), call.get("id"), JsonSupport.map("status", "queued", "next_attempt_at", null, "last_error", null)); return null;
        });
        dispatch(call.get("id")); return store.require("callsession", CallLifecycle.tenant(call), call.get("id"));
    }
    public Map<String, Object> hangup(Map<String, Object> call, String reason) {
        if (CallLifecycle.TERMINAL.contains(call.get("status"))) return call;
        Map<String, Object> result;
        try { result = client.command(call, "hangup", JsonSupport.map("reason", reason)); }
        catch (TelephonyClient.Failure e) { throw new ApiError(e.status == 409 ? 409 : 502, "telephony hangup failed"); }
        if (!Boolean.TRUE.equals(result.get("ended"))) throw new ApiError(503, "hangup requested; PBX termination is not yet confirmed");
        lifecycle.status(JsonSupport.map("call_id", call.get("id"), "kind", "status", "payload", JsonSupport.map("status", "completed", "attempt", call.get("attempts"), "event_id", "manual-hangup:" + call.get("id") + ":" + call.get("attempts"), "hangup_reason", reason)));
        return store.require("callsession", CallLifecycle.tenant(call), call.get("id"));
    }
}
