package com.duozhi.outbound;

import java.util.*;
import org.springframework.stereotype.Service;

/** Provider events and durable side effects commit in the same call transaction. */
@Service
public class VoiceCallbacks {
    private final Store store;
    private final Transactions tx;
    private final JsonSupport json;
    private final CallLifecycle lifecycle;
    private final Outbox outbox;
    private final Settings settings;
    private final Config config;
    private final Contract contract;
    private final ConversationPolicy product;
    public VoiceCallbacks(Store store, Transactions tx, JsonSupport json, CallLifecycle lifecycle, Outbox outbox, Settings settings, Config config, Contract contract, ConversationPolicy product) {
        this.store=store; this.tx=tx; this.json=json; this.lifecycle=lifecycle; this.outbox=outbox; this.settings=settings; this.config=config; this.contract=contract; this.product=product;
    }
    public Map<String,Object> handle(String kind, Map<String,Object> body) {
        if ("telephony_status".equals(kind)) return lifecycle.status(body);
        return tx.run(() -> {
            tx.platform();
            if ("sms_status".equals(kind)) return sms(body);
            Map<String,Object> call=tx.call(body.get("call_id"));
            if (call==null) return JsonSupport.map("result","ignore");
            switch(kind) {
                case "telephony_speech":return speech(call,body);
                case "telephony_transcript":return transcript(call,body);
                case "telephony_media":return media(call,body);
                case "telephony_recording":return recording(call,body);
                case "telephony_dtmf":return dtmf(call,body);
                default:throw new IllegalArgumentException("unsupported callback kind");
            }
        });
    }
    public Map<String,Object> realtime(Map<String,Object> call) {
        Map<String,Object> row=store.one("realtimesession","WHERE call_session_id=?",store.encode("realtimesession","call_session_id",call.get("id")));
        return row==null?store.insert("realtimesession",JsonSupport.map("tenant_id",CallLifecycle.tenant(call),"call_session_id",call.get("id"))):row;
    }
    @SuppressWarnings("unchecked")
    private Map<String,Object> payload(Map<String,Object> body) { return (Map<String,Object>)body.getOrDefault("payload",Collections.emptyMap()); }
    private boolean stale(Map<String,Object> call,Object attempt) { return attempt!=null&&JsonSupport.integer(attempt,-1)!=CallLifecycle.number(call,"attempts"); }
    private Map<String,Object> ignored(String reason) { return JsonSupport.map("result","ignored","reason",reason); }
    private Map<String,Object> transcript(Map<String,Object> call,Map<String,Object> body) {
        Map<String,Object> p=payload(body);
        if(lifecycle.event(call,"transcript","telephony",p))return JsonSupport.map("result","ok");
        if(stale(call,p.get("attempt")))return ignored("stale_attempt");
        Map<String,Object> speech=new LinkedHashMap<>(p);
        speech.put("call_id",call.get("id")); speech.put("transcript",Objects.toString(body.get("transcript"),""));
        speech.put("event_id",Objects.toString(p.get("event_id"),JsonSupport.sha(call.get("id")+":transcript:telephony:"+json.canonical(p,true))));
        speech.putIfAbsent("is_final",true);speech.putIfAbsent("speaker_role","customer");speech.putIfAbsent("asr_provider","telephony");
        Map<String,Object> result=speech(call,contract.input("SpeechWebhookEvent",speech,true));
        outbox.callback(call,"call.transcript",JsonSupport.map("turn_id",result.get("turn_id"),"transcript",speech.get("transcript")),"callback:transcript:"+result.get("turn_id"));
        return JsonSupport.map("result","ok");
    }
    private Map<String,Object> speech(Map<String,Object> call,Map<String,Object> body) {
        if(stale(call,body.get("attempt")))return ignored("stale_attempt");
        long tenant=CallLifecycle.tenant(call);Object id=call.get("id");
        String key=JsonSupport.sha(id+":speech:"+body.get("event_id"));
        Map<String,Object> existing=store.one("speechturn","WHERE call_session_id=? AND provider_event_key=?",store.encode("speechturn","call_session_id",id),key);
        if(existing!=null)return JsonSupport.map("result","ok","duplicate",true,"turn_id",existing.get("id"));
        boolean last=Boolean.TRUE.equals(body.get("is_final"));
        String transcript=Objects.toString(body.get("transcript"),""),normalized=transcript.trim().replaceAll("(?U)\\s+"," ");
        Map<String,Object> rt=realtime(call);
        int sequence=CallLifecycle.number(rt,"turn_sequence")+(last?1:0);
        Map<String,Object> change=JsonSupport.map("turn_sequence",sequence);
        if(!CallLifecycle.TERMINAL.contains(call.get("status"))&&!"closed".equals(rt.get("state")))change.put("state",last?"thinking":"listening");
        if(rt.get("started_at")==null)change.put("started_at",Store.now());
        store.update("realtimesession",tenant,rt.get("id"),change);
        Map<String,Object> state=store.one("conversationstate","WHERE call_id=? AND attempt=?",store.encode("conversationstate","call_id",id),call.get("attempts"));
        if(state!=null) {
            Map<String,Object> data=json.object(state.get("data_json").toString());if(last)data.put("speech_event_id",body.get("event_id"));
            store.update("conversationstate",tenant,state.get("id"),JsonSupport.map("generation",CallLifecycle.number(state,"generation")+1,"deadline",null,"timer_kind","","data_json",json.json(data)));
        }
        Map<String,Object> turn=JsonSupport.map("tenant_id",tenant,"call_session_id",id,"provider_event_key",key,"turn_index",sequence,"attempt",call.get("attempts"),"transcript",transcript,"normalized_transcript",normalized);
        for(String name:Arrays.asList("speaker_role","channel_id","is_final","confidence","start_ms","end_ms","asr_provider"))if(body.containsKey(name))turn.put(name,body.get(name));
        turn=store.insert("speechturn",turn);
        String detail=body.get("confidence")==null?"":"confidence="+body.get("confidence");
        if(body.get("start_ms")!=null&&body.get("end_ms")!=null)detail+=(detail.isEmpty()?"":";")+"audio_span_ms="+Math.max(0,JsonSupport.integer(body.get("end_ms"),0)-JsonSupport.integer(body.get("start_ms"),0));
        store.insert("callmetric",JsonSupport.map("tenant_id",tenant,"call_session_id",id,"stage",last?"asr.final":"asr.partial","provider",body.getOrDefault("asr_provider",""),"duration_ms",body.get("latency_ms"),"success",!last||!normalized.isEmpty(),"error_code",last&&normalized.isEmpty()?"EMPTY_FINAL_TRANSCRIPT":null,"detail",detail));
        store.insert("callevent",JsonSupport.map("call_session_id",id,"event_type",last?"speech_final":"speech_partial","source","asr","payload",json.json(body)));
        if(last)store.update("callsession",tenant,id,JsonSupport.map("last_transcript",transcript,"summary",normalized.isEmpty()?call.get("summary"):(Objects.toString(call.get("summary"),"").trim()+"\n客户："+normalized).trim()));
        if(last&&"waiting_human".equals(call.get("status"))) {
            Map<String,Object> policy=product.policy(product.state(call));
            if((body.get("confidence")==null||((Number)body.get("confidence")).doubleValue()>=((Number)policy.get("confidence_threshold")).doubleValue())&&Values.set("stop_contact","wrong_person","decline_handoff","end","callback").contains(DialogueRules.classify(transcript))) {
                product.releaseWaiting(call);store.update("callsession",tenant,id,JsonSupport.map("status","in_ai","human_agent_id",null));
            }
        }
        if(Boolean.TRUE.equals(body.get("barge_in")))outbox.enqueue(tenant,"interrupt_playback",id,"interrupt:"+id+":"+body.get("event_id"),JsonSupport.map("call_id",id,"attempt",call.get("attempts"),"turn_sequence",sequence,"playback_id",rt.get("playback_id"),"speech_event_id",body.get("event_id")));
        if(last&&!"human_only".equals(call.get("mode")))outbox.enqueue(tenant,"ai_turn",id,"ai:"+id+":speech:"+turn.get("id"),JsonSupport.map("call_id",id,"attempt",call.get("attempts"),"turn_sequence",sequence,"speech_event_id",body.get("event_id"),"transcript",transcript));
        outbox.callback(call,last?"call.speech_final":"call.speech_partial",JsonSupport.map("turn_id",turn.get("id"),"transcript",transcript,"is_final",last,"confidence",body.get("confidence")),"callback:speech:"+turn.get("id"));
        return JsonSupport.map("result","ok","duplicate",false,"turn_id",turn.get("id"));
    }
    private Map<String,Object> media(Map<String,Object> call,Map<String,Object> body) {
        if(lifecycle.event(call,"media","media_gateway",body))return JsonSupport.map("result","ok","duplicate",true);
        if(stale(call,body.get("attempt")))return ignored("stale_attempt");
        Map<String,Object> rt=realtime(call);int attempt=CallLifecycle.number(call,"attempts");String state=body.get("state").toString();
        Map<String,Object> result=JsonSupport.map("result","ok","realtime_session_id",rt.get("id"),"state",rt.get("state"));
        if(attempt>1&&body.get("attempt")==null||CallLifecycle.TERMINAL.contains(call.get("status"))&&!"closed".equals(state))return result;
        Map<String,Object> change=new LinkedHashMap<>();
        if(CallLifecycle.number(rt,"attempt")==attempt) {
            if("closed".equals(rt.get("state"))||body.get("event_sequence")!=null&&rt.get("last_event_sequence")!=null&&JsonSupport.integer(body.get("event_sequence"),0)<=CallLifecycle.number(rt,"last_event_sequence"))return result;
        }else change.putAll(JsonSupport.map("started_at",null,"ended_at",null,"last_event_sequence",null,"provider_session_id",null));
        change.put("attempt",attempt);change.put("state",state);
        if(body.get("event_sequence")!=null)change.put("last_event_sequence",body.get("event_sequence"));
        if(body.get("provider_session_id")!=null&&!body.get("provider_session_id").toString().isEmpty())change.put("provider_session_id",body.get("provider_session_id"));
        for(String name:Arrays.asList("playback_id","codec","sample_rate","channel_count"))if(body.containsKey(name))change.put(name,body.get(name));
        if((rt.get("started_at")==null||CallLifecycle.number(rt,"attempt")!=attempt)&&!"created".equals(state))change.put("started_at",Store.now());
        if("closed".equals(state))change.put("ended_at",Store.now());
        long tenant=CallLifecycle.tenant(call);store.update("realtimesession",tenant,rt.get("id"),change);
        if(Values.set("listening","interrupted","closed").contains(state)) {
            for(Map<String,Object> task:store.list("taskoutbox","WHERE tenant_id=? AND aggregate_id=? AND task_type='after_playback' AND state='PENDING'",tenant,call.get("id").toString())) {
                Map<String,Object> p=json.object(task.get("payload_json").toString());
                if(!p.containsKey("product_kind")&&JsonSupport.integer(p.get("attempt"),-1)==attempt&&Objects.equals(p.get("playback_id"),rt.get("playback_id"))&&!Objects.equals(body.get("playback_id"),rt.get("playback_id")))store.update("taskoutbox",tenant,task.get("id"),JsonSupport.map("available_at",Store.now()));
            }
        }
        store.insert("callmetric",JsonSupport.map("tenant_id",tenant,"call_session_id",call.get("id"),"stage","media."+state,"provider",body.getOrDefault("provider",""),"duration_ms",body.get("duration_ms"),"success",body.get("error_code")==null||body.get("error_code").toString().isEmpty(),"error_code",body.get("error_code")));
        store.insert("callevent",JsonSupport.map("call_session_id",call.get("id"),"event_type","media_state","source","media_gateway","payload",json.json(body)));
        product.media(call,body);
        result.put("state",state);return result;
    }
    private Map<String,Object> recording(Map<String,Object> call,Map<String,Object> body) {
        Map<String,Object> p=payload(body);Object raw=p.get("attempt");int attempt=CallLifecycle.number(call,"attempts");
        if(raw==null&&attempt>1)return ignored("ambiguous_attempt");
        if(raw!=null&&(!(raw instanceof Integer||raw instanceof Long)||((Number)raw).longValue()<0||((Number)raw).longValue()>attempt))return ignored("invalid_attempt");
        int recorded=raw==null?attempt:((Number)raw).intValue();
        if(lifecycle.event(call,"recording","telephony",p))return JsonSupport.map("result","ok");
        long tenant=CallLifecycle.tenant(call);Object id=call.get("id");
        if(call.get("campaign_id")!=null&&!Boolean.TRUE.equals(store.require("campaign",tenant,call.get("campaign_id")).get("recording_enabled")))return ignored("recording_disabled");
        String url=Objects.toString(p.get("url"),"");
        if(!url.isEmpty()) {
            if(recorded==attempt)store.update("callsession",tenant,id,JsonSupport.map("recording_url",url));
            Map<String,Object> asset=store.one("recordingasset","WHERE call_session_id=? AND provider_url=? AND attempt=?",store.encode("recordingasset","call_session_id",id),url,recorded);
            if(asset==null) {
                int days=Math.max(1,Math.min(3650,JsonSupport.integer(settings.get(tenant,"compliance").get("recording_retention_days"),config.number("RECORDING_RETENTION_DAYS", 90))));
                asset=store.insert("recordingasset",JsonSupport.map("tenant_id",tenant,"call_session_id",id,"attempt",recorded,"provider_recording_id",p.get("recording_id"),"provider_url",url,"storage_uri",Objects.toString(p.get("storage_uri"),""),"state",Objects.toString(p.get("state"),"available"),"duration_sec",p.get("duration_sec"),"media_format",Objects.toString(p.get("format"),""),"channel_count",Math.max(1,JsonSupport.integer(p.get("channel_count"),1)),"checksum_sha256",p.get("checksum_sha256"),"retention_until",Store.now().plusDays(days)));
            }
            if(recorded==attempt&&CallLifecycle.TERMINAL.contains(call.get("status")))outbox.enqueue(tenant,"call_analysis",id,"analysis-recording:"+id+":"+recorded+":"+asset.get("id"),JsonSupport.map("call_id",id,"attempt",recorded));
            if(Objects.toString(asset.get("storage_uri"),"").isEmpty()&&!config.get("RECORDING_INGEST_ENDPOINT","").isEmpty())outbox.enqueue(tenant,"recording_ingest",asset.get("id"),"recording-ingest:"+asset.get("id"),JsonSupport.map("recording_asset_id",asset.get("id")));
            outbox.callback(call,"call.recording",JsonSupport.map("url",url,"attempt",recorded),"callback:recording:"+asset.get("id"));
        }
        return JsonSupport.map("result","ok");
    }
    private Map<String,Object> sms(Map<String,Object> body) {
        if(body.get("sms_log_id")==null&&Objects.toString(body.get("provider_message_id"),"").isEmpty())return ignored("missing_message_identifier");
        Map<String,Object> row=body.get("sms_log_id")==null?null:store.one("smslog","WHERE id=?"+store.lock(),body.get("sms_log_id"));
        if(row==null&&body.get("provider_message_id")!=null)row=store.one("smslog","WHERE provider_message_id=? ORDER BY created_at DESC LIMIT 1"+store.lock(),body.get("provider_message_id"));
        if(row==null)return JsonSupport.map("result","ignore");
        String state=body.get("state").toString().trim().transform(Ascii::lower);
        if(Values.set("delivered","failed","rejected","expired").contains(row.get("state"))&&!state.equals(row.get("state")))return ignored("terminal_state");
        Map<String,Object> change=JsonSupport.map("state",state,"provider_error",body.get("error"));
        if(body.get("provider_message_id")!=null&&Objects.toString(row.get("provider_message_id"),"").isEmpty())change.put("provider_message_id",body.get("provider_message_id"));
        if(Values.set("sent","delivered").contains(state)&&row.get("sent_at")==null)change.put("sent_at",Store.now());
        store.update("smslog",CallLifecycle.tenant(row),row.get("id"),change);return JsonSupport.map("result","ok");
    }
    private Map<String,Object> dtmf(Map<String,Object> call,Map<String,Object> body) {
        Map<String,Object> p=payload(body);
        if(p.get("attempt")==null||stale(call,p.get("attempt"))||!Values.set("answered","in_ai").contains(call.get("status")))return JsonSupport.map("result","ignored");
        String digit=Objects.toString(p.get("digit"),""),event=Objects.toString(p.get("event_id"),"");
        if(!digit.matches("[0-9*#]")||event.isEmpty())throw new ApiError(422,"invalid DTMF event");
        if(lifecycle.event(call,"dtmf","freeswitch",p))return JsonSupport.map("result","ok","duplicate",true);
        Map<String,Object> state=product.state(call),policy=product.policy(state),data=json.object(state.get("data_json").toString());
        boolean active=false;for(Object raw:(List<?>)policy.get("slots")){Map<?,?> slot=(Map<?,?>)raw;if(Objects.equals(slot.get("key"),data.get("active_slot"))&&"digits".equals(slot.get("kind")))active=true;}
        if(!active)return ignored("no_digit_field");
        String digits=Objects.toString(data.get("dtmf_buffer"),"");if(digit.matches("[0-9]"))digits+=digit;
        if(digits.length()>JsonSupport.integer(policy.get("dtmf_max_digits"),20)){data.put("dtmf_buffer","");product.save(state,data);return JsonSupport.map("result","rejected","reason","too_many_digits");}
        if(digit.equals(policy.get("dtmf_end"))&&!digits.isEmpty()){data.put("dtmf_buffer","");product.save(state,data);return speech(call,contract.input("SpeechWebhookEvent",JsonSupport.map("call_id",call.get("id"),"event_id","dtmf:"+event,"transcript",digits,"is_final",true,"attempt",call.get("attempts"),"confidence",1.0,"speaker_role","customer"),true));}
        data.put("dtmf_buffer",digits);state.put("deadline",null);product.save(state,data);product.arm(call,"silence");return JsonSupport.map("result","collecting","digit_count",digits.length());
    }
}
