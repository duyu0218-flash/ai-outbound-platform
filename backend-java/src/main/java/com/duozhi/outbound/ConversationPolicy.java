package com.duozhi.outbound;

import java.time.*;
import java.util.*;
import java.util.regex.*;
import org.springframework.stereotype.Service;
import org.springframework.core.io.ClassPathResource;

@Service
public class ConversationPolicy {
    private final Store store;
    private final JsonSupport json;
    private final Settings settings;
    private final Outbox outbox;
    private final Map<String,Object> defaults;
    public ConversationPolicy(Store store,JsonSupport json,Settings settings,Outbox outbox,tools.jackson.databind.ObjectMapper mapper) throws java.io.IOException {
        this.store=store;this.json=json;this.settings=settings;this.outbox=outbox;
        try(java.io.InputStream in=new ClassPathResource("contracts/scenario-defaults.json").getInputStream()) {
            defaults=mapper.readValue(in,new tools.jackson.core.type.TypeReference<Map<String,Object>>(){});
        }
    }
    public Map<String,Object> current(long tenant,Object campaign) {
        Map<String,Object> version=store.one("scenarioversion","WHERE tenant_id=? AND (campaign_id=? OR campaign_id IS NULL) ORDER BY (campaign_id IS NOT NULL) DESC,id DESC LIMIT 1",tenant,campaign);
        Map<String,Object> policy=new LinkedHashMap<>(defaults);
        if(version!=null)policy.putAll(json.object(version.get("policy_json").toString()));
        return JsonSupport.map("policy",policy,"version",version==null?null:version.get("id"));
    }
    @SuppressWarnings("unchecked")
    public Map<String,Object> state(Map<String,Object> call) {
        Map<String,Object> state=store.one("conversationstate","WHERE call_id=? AND attempt=?"+store.lock(),store.encode("conversationstate","call_id",call.get("id")),call.get("attempts"));
        if(state!=null)return state;
        long tenant=CallLifecycle.tenant(call);Map<String,Object> current=current(tenant,call.get("campaign_id"));
        Map<String,Object> policy=(Map<String,Object>)current.get("policy");policy.put("_ai",settings.get(tenant,"ai"));
        String script="";
        if(call.get("campaign_id")!=null) {
            Map<String,Object> campaign=store.require("campaign",tenant,call.get("campaign_id"));script=Objects.toString(campaign.get("script"),"");
            if(script.isEmpty()&&campaign.get("script_template_id")!=null) {
                Map<String,Object> template=store.one("scripttemplate","WHERE tenant_id=? AND id=?",tenant,campaign.get("script_template_id"));
                if(template!=null&&Boolean.TRUE.equals(template.get("is_active")))script=Objects.toString(template.get("content"),"");
            }
        }
        policy.put("_script",script);
        return store.insert("conversationstate",JsonSupport.map("tenant_id",tenant,"call_id",call.get("id"),"attempt",call.get("attempts"),"policy_version_id",current.get("version"),"policy_json",json.json(policy)));
    }
    public Map<String,Object> policy(Map<String,Object> state) {
        Map<String,Object> result=new LinkedHashMap<>(defaults);result.putAll(json.object(state.get("policy_json").toString()));return result;
    }
    public void save(Map<String,Object> state,Map<String,Object> data) {
        state.put("data_json",json.json(data));
        store.update("conversationstate",CallLifecycle.tenant(state),state.get("id"),state);
    }
    public boolean inHours(Map<String,Object> policy,LocalDateTime now) {
        ZonedDateTime local=now.atOffset(ZoneOffset.UTC).atZoneSameInstant(ZoneId.of(policy.get("timezone").toString()));
        return ((Collection<?>)policy.get("weekdays")).contains(local.getDayOfWeek().getValue()-1)
            &&!((Collection<?>)policy.get("holidays")).contains(local.toLocalDate().toString())
            &&local.getHour()>=JsonSupport.integer(policy.get("start_hour"),9)&&local.getHour()<JsonSupport.integer(policy.get("end_hour"),20);
    }
    public static Map<String,Object> reply(String text,String action) {return JsonSupport.map("action",action,"tts_text",text,"handoff_to_human","handoff".equals(action),"escalate_priority",0,"hangup_sms",null,"next_keywords",Collections.emptyList());}
    private Map<String,Object> reply(String text) {return reply(text,"speak");}
    public void work(Map<String,Object> call,String kind,String key,Map<String,Object> detail) {
        String eventKey=CallLifecycle.tenant(call)+":"+key;
        if(store.count("productworkitem","WHERE event_key=?",eventKey)==0)store.insert("productworkitem",JsonSupport.map("tenant_id",CallLifecycle.tenant(call),"call_id",call.get("id"),"phone",call.get("phone"),"kind",kind,"event_key",eventKey,"detail_json",json.json(detail)));
    }
    public void suppress(Map<String,Object> call,String reason) {
        long tenant=CallLifecycle.tenant(call);Object phone=call.get("phone");
        if(store.count("phonesuppression","WHERE tenant_id=? AND phone=?",tenant,phone)==0)store.insert("phonesuppression",JsonSupport.map("tenant_id",tenant,"phone",phone,"reason",reason,"source_call_id",call.get("id")));
        for(Map<String,Object> c:store.list("contact","WHERE tenant_id=? AND phone=?",tenant,phone))store.update("contact",tenant,c.get("id"),JsonSupport.map("dnc",true,"dnc_reason",reason));
        for(Map<String,Object> c:store.list("callsession","WHERE tenant_id=? AND phone=?",tenant,phone)) {
            Map<String,Object> change=new LinkedHashMap<>();
            if(Values.set("created","queued").contains(c.get("status")))change.putAll(JsonSupport.map("status","failed","finished_at",Store.now(),"last_error","CONTACT_DNC","next_attempt_at",null));
            if(c.get("id").equals(call.get("id"))||CallLifecycle.TERMINAL.contains(c.get("status")))change.put("next_attempt_at",null);
            if(!change.isEmpty())store.update("callsession",tenant,c.get("id"),change);
            cancelAppointments(c);
        }
    }
    public void cancelAppointments(Map<String,Object> call) {
        for(Map<String,Object> a:store.list("callbackappointment","WHERE tenant_id=? AND source_call_id=? AND state='confirmed'",CallLifecycle.tenant(call),store.encode("callbackappointment","source_call_id",call.get("id"))))
            store.update("callbackappointment",CallLifecycle.tenant(call),a.get("id"),JsonSupport.map("state","cancelled","revision",CallLifecycle.number(a,"revision")+1));
    }
    public void releaseWaiting(Map<String,Object> call) {
        long tenant=CallLifecycle.tenant(call);
        for(Map<String,Object> h:store.list("handoffrequest","WHERE call_session_id=? AND state='WAITING'",store.encode("handoffrequest","call_session_id",call.get("id")))) {
            store.update("handoffrequest",tenant,h.get("id"),JsonSupport.map("state","rejected"));
            if(h.get("assigned_agent_id")==null)continue;
            Map<String,Object> agent=store.one("user","WHERE id=? AND tenant_id=?"+store.lock(),h.get("assigned_agent_id"),tenant);
            if(agent==null||!"busy".equals(agent.get("agent_status")))continue;
            long active=store.count("callsession","WHERE human_agent_id=? AND id<>? AND status IN "+CallLifecycle.ACTIVE_SQL,agent.get("id"),store.encode("callsession","id",call.get("id")));
            long pending=store.count("handoffrequest","WHERE assigned_agent_id=? AND call_session_id<>? AND state IN ('WAITING','ACCEPTING')",agent.get("id"),store.encode("handoffrequest","call_session_id",call.get("id")));
            if(active==0&&pending==0)store.update("user",tenant,agent.get("id"),JsonSupport.map("agent_status","ready"));
        }
    }
    private Map<String,Object> clarify(Map<String,Object> data,Map<String,Object> policy) {
        int count=JsonSupport.integer(data.get("clarifications"),0)+1;data.put("clarifications",count);
        boolean exhausted=count>JsonSupport.integer(policy.get("max_clarifications"),2);
        return reply(exhausted?"抱歉，暂时无法确认您的意思，本次先结束，再见。":policy.get("clarify_prompt").toString(),exhausted?"hangup":"speak");
    }
    @SuppressWarnings("unchecked")
    private Map<String,Object> dict(Map<String,Object> data,String key) {
        if(!(data.get(key) instanceof Map))data.put(key,new LinkedHashMap<String,Object>());return (Map<String,Object>)data.get(key);
    }
    @SuppressWarnings("unchecked")
    private List<Map<String,Object>> slots(Map<String,Object> policy) {return (List<Map<String,Object>>)policy.get("slots");}
    private Map<String,Object> nextSlot(Map<String,Object> data,Map<String,Object> policy) {
        Map<String,Object> collected=dict(data,"slots");
        for(Map<String,Object> slot:slots(policy)) {
            Object value=collected.get(slot.get("key"));
            if(Boolean.TRUE.equals(slot.getOrDefault("required",true))&&(!(value instanceof Map)||!Boolean.TRUE.equals(((Map<?,?>)value).get("confirmed")))) {
                data.put("active_slot",slot.get("key"));Map<String,Object> proposed=dict(data,"proposed_slots");
                Object candidate=proposed.remove(slot.get("key"));
                if(candidate instanceof Map) {
                    @SuppressWarnings("unchecked") Map<String,Object> p=(Map<String,Object>)candidate;
                    return collect(data,policy,p.get("value").toString(),p.get("evidence"));
                }
                return reply(slot.get("question").toString());
            }
        }
        data.put("active_slot",null);boolean any=false,qualified=true;
        for(Map<String,Object> slot:slots(policy)) {
            Collection<?> allowed=(Collection<?>)slot.getOrDefault("qualifies",Collections.emptyList());
            if(!allowed.isEmpty()) {any=true;Object value=collected.get(slot.get("key"));qualified&=value instanceof Map&&allowed.contains(((Map<?,?>)value).get("value"));}
        }
        data.put("outcome",qualified&&any?"qualified_lead":"information_collected");
        return reply("信息已经记录，感谢您的配合，再见。","hangup");
    }
    private Map<String,Object> collect(Map<String,Object> data,Map<String,Object> policy,String text,Object evidence) {
        Map<String,Object> slot=null;for(Map<String,Object> s:slots(policy))if(Objects.equals(s.get("key"),data.get("active_slot")))slot=s;
        if(slot==null)return nextSlot(data,policy);
        String value=text.trim(),kind=Objects.toString(slot.get("kind"),"text");
        if(Values.set("integer","digits").contains(kind)&&!value.matches("[0-9]{1,20}"))return reply("请用数字回答："+slot.get("question"));
        if("date".equals(kind))try{LocalDate.parse(value);}catch(DateTimeException e){return reply("请按年-月-日说明日期。");}
        if("choice".equals(kind)) {
            List<String> matches=new ArrayList<>();for(Object option:(List<?>)slot.get("choices"))if(DialogueRules.keyword(value,option.toString()))matches.add(option.toString());
            if(matches.size()!=1)return reply(slot.get("question")+" 可选："+String.join("、",((List<?>)slot.get("choices")).stream().map(Object::toString).collect(java.util.stream.Collectors.toList())));
            value=matches.get(0);
        }
        if(value.codePointCount(0,value.length())>500)return clarify(data,policy);
        Map<String,Object> pending=JsonSupport.map("kind","slot","key",slot.get("key"),"value",value,"evidence",evidence);
        if(Boolean.TRUE.equals(slot.getOrDefault("confirm",true))) {data.put("pending",pending);return reply("您说的"+slot.get("label")+"是"+value+"，对吗？");}
        pending.put("confirmed",true);dict(data,"slots").put(slot.get("key").toString(),pending);return nextSlot(data,policy);
    }
    @SuppressWarnings("unchecked")
    public Map<String,Object> prepare(Map<String,Object> call,String transcript) {
        Map<String,Object> state=state(call),policy=policy(state),data=json.object(state.get("data_json").toString());
        Map<String,Object> realtime=store.one("realtimesession","WHERE call_session_id=?",store.encode("realtimesession","call_session_id",call.get("id")));
        int sequence=realtime==null?0:CallLifecycle.number(realtime,"turn_sequence");
        if(JsonSupport.integer(data.get("sequence"),-1)==sequence&&transcript.equals(data.get("transcript"))&&data.get("result") instanceof Map)return (Map<String,Object>)data.get("result");
        if(transcript.trim().isEmpty()&&sequence==0)return null;
        state.put("generation",CallLifecycle.number(state,"generation")+1);state.put("deadline",null);state.put("timer_kind","");
        Map<String,Object> result=null;
        if(transcript.trim().isEmpty()) {
            result=clarify(data,policy);data.putAll(JsonSupport.map("sequence",sequence,"transcript",transcript,"last_intent","unclear","result",result));save(state,data);return result;
        }
        data.put("silence_count",0);
        Map<String,Object> turn=store.one("speechturn","WHERE call_session_id=? AND attempt=? AND is_final=? AND speaker_role='customer' ORDER BY created_at DESC,id DESC LIMIT 1",store.encode("speechturn","call_session_id",call.get("id")),call.get("attempts"),true);
        String intent=DialogueRules.classify(transcript);
        Map<String,Object> pending=data.get("pending") instanceof Map?(Map<String,Object>)data.get("pending"):Collections.emptyMap();
        if("callback_time_request".equals(pending.get("kind"))&&!Values.set("stop_contact","wrong_person","cancel_callback","end").contains(intent))intent="callback";
        Map<String,Object> evidence=JsonSupport.map("text",transcript,"turn_id",turn==null?null:turn.get("id"),"attempt",call.get("attempts"),"start_ms",turn==null?null:turn.get("start_ms"),"end_ms",turn==null?null:turn.get("end_ms"));
        boolean low=turn!=null&&turn.get("confidence") instanceof Number&&((Number)turn.get("confidence")).doubleValue()<((Number)policy.get("confidence_threshold")).doubleValue();
        if(low)result=clarify(data,policy);
        else if(Values.set("stop_contact","wrong_person").contains(intent)) {
            suppress(call,intent);data.put("outcome",intent);data.put("pending",null);result=reply("好的，已停止对这个号码的后续联系，再见。","hangup");
        }else if("cancel_callback".equals(intent)) {
            cancelAppointments(call);data.put("pending",null);result=reply("好的，本次预约回拨已取消。");
        }else if("callback".equals(intent)) {
            LocalDateTime when=DialogueRules.callbackTime(transcript,Instant.now(),policy.get("timezone").toString());
            if(when==null) {data.put("pending",JsonSupport.map("kind","callback_time_request"));result=reply("请告诉我具体日期和上午或下午几点回电。");}
            else if(!inHours(policy,when)) {data.put("pending",JsonSupport.map("kind","callback_time_request"));result=reply("这个时间不在服务时段，请您换一个时间，我会再次确认。");}
            else {data.put("pending",JsonSupport.map("kind","appointment","value",when.toString(),"evidence",evidence));String display=when.atOffset(ZoneOffset.UTC).atZoneSameInstant(ZoneId.of(policy.get("timezone").toString())).format(java.time.format.DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm"));result=reply("您希望在"+display+"回电，对吗？");}
        }else if("machine_candidate".equals(intent)) {
            int hits=JsonSupport.integer(data.get("machine_hits"),0)+1;data.put("machine_hits",hits);data.put("answer_kind","unknown");
            if("end".equals(policy.get("machine_action"))||hits>=2) {data.putAll(JsonSupport.map("answer_kind","machine","outcome","non_human","pending",null));result=reply("本次通话先结束，再见。","hangup");}
            else {data.put("pending",JsonSupport.map("kind","human_confirmation"));result=reply("请问现在是本人在接听吗？");}
        }else if(!pending.isEmpty()&&Values.set("affirm","deny").contains(intent)) {
            data.remove("pending");
            if("deny".equals(intent))result=reply("好的，请您重新说明正确的信息。");
            else if("appointment".equals(pending.get("kind"))) {
                LocalDateTime when=LocalDateTime.parse(pending.get("value").toString());
                if(!when.isAfter(Store.now())||!inHours(policy,when))result=reply("该时间已不可用，请重新选择回电时间。");
                else {
                    String key="appointment:"+call.get("id")+":"+call.get("attempts")+":"+sequence;
                    Map<String,Object> appt=store.one("callbackappointment","WHERE request_key=?",key);
                    if(appt==null)appt=store.insert("callbackappointment",JsonSupport.map("id",uuid5(key),"tenant_id",CallLifecycle.tenant(call),"source_call_id",call.get("id"),"scheduled_at",when,"request_key",key));
                    data.put("outcome","callback_confirmed");result=reply("好的，回拨时间已经保存，再见。","hangup");
                    Map<String,Object> task=outbox.enqueue(CallLifecycle.tenant(call),"after_playback",call.get("id"),"appointment:"+appt.get("id")+":"+appt.get("revision"),JsonSupport.map("product_kind","appointment","appointment_id",appt.get("id"),"revision",appt.get("revision")));
                    schedule(task,when);
                }
            }else if("human_confirmation".equals(pending.get("kind"))) {data.put("answer_kind","human");data.put("machine_hits",0);result=reply("谢谢确认，请问您希望了解什么？");}
            else if("slot".equals(pending.get("kind"))) {pending.put("confirmed",true);pending.put("confirmation",evidence);dict(data,"slots").put(pending.get("key").toString(),pending);result=nextSlot(data,policy);}
        }else if("handoff".equals(intent)) {
            data.put("outcome","handoff_requested");boolean can=Values.set("ai_handoff","mixed_human_first").contains(call.get("mode"));
            result=can?reply(policy.get("handoff_prompt").toString(),"handoff"):reply("当前无法直接转人工，我会记录您的人工服务请求。");
            if(!can)work(call,"human_followup","human:"+call.get("id")+":"+call.get("attempts"),evidence);
        }else if("decline_handoff".equals(intent))result=reply("好的，我们继续。");
        else if("end".equals(intent))result=reply("谢谢，再见。","hangup");
        else if("wait".equals(intent)) {data.put("extended_wait",true);result=reply("好的，我稍等您。");}
        else if("rejected".equals(intent)) {data.put("outcome","rejected");result=reply("好的，感谢您的时间，再见。","hangup");}
        else {
            Map<String,Object> faqs=(Map<String,Object>)policy.get("faqs");String faq=null;
            for(Map.Entry<String,Object> entry:faqs.entrySet())if(DialogueRules.keyword(transcript,entry.getKey())){faq=entry.getValue().toString();break;}
            if(faq!=null) {
                String question="";for(Map<String,Object> slot:slots(policy))if(Objects.equals(slot.get("key"),data.get("active_slot")))question=slot.get("question").toString();result=reply((faq+" "+question).trim());
            }else if(!slots(policy).isEmpty()&&!Values.set("question","unclear").contains(intent)) {
                for(Map<String,Object> slot:slots(policy)) {
                    Matcher correction=Pattern.compile(Pattern.quote(slot.get("label").toString())+"(?:改成|更正为|是|为|[:：])\\s*([^，,。；;]+)").matcher(transcript);
                    if(correction.find()) {dict(data,"proposed_slots").put(slot.get("key").toString(),JsonSupport.map("value",correction.group(1).trim(),"evidence",evidence));dict(data,"slots").remove(slot.get("key"));}
                }
                if(!dict(data,"proposed_slots").isEmpty())result=nextSlot(data,policy);
                else {Matcher correction=Pattern.compile("(?:不是.+?而是|改成|更正为)(.+)$").matcher(transcript);String value=transcript;if("slot".equals(pending.get("kind"))&&correction.find())value=correction.group(1).trim();result=collect(data,policy,value,evidence);}
            }else if("unclear".equals(intent))result=clarify(data,policy);
        }
        if(!Values.set("unclear","machine_candidate").contains(intent)&&!low)data.put("clarifications",0);
        if(Values.set("interested","permission_to_listen","question","affirm").contains(intent)&&!"machine".equals(data.get("answer_kind"))&&!low)data.put("answer_kind","human");
        data.putAll(JsonSupport.map("sequence",sequence,"transcript",transcript,"last_intent",intent,"result",result));save(state,data);return result;
    }
    public void schedule(Map<String,Object> task,LocalDateTime when) {
        // Compacted receipts are tombstones, not mutable outbox records.
        if(task.containsKey("state")&&Values.set("pending","failed").contains(task.get("state")))store.update("taskoutbox",CallLifecycle.tenant(task),task.get("id"),JsonSupport.map("available_at",when));
    }
    public void arm(Map<String,Object> call,String kind) {
        Map<String,Object> state=state(call),policy=policy(state),data=json.object(state.get("data_json").toString());
        if("silence".equals(kind)&&Boolean.TRUE.equals(data.get("model_pending")))return;
        if(state.get("deadline")!=null&&kind.equals(state.get("timer_kind")))return;
        int seconds=JsonSupport.integer(policy.get("handoff".equals(kind)?"handoff_wait_seconds":Boolean.TRUE.equals(data.remove("extended_wait"))?"wait_seconds":"silence_seconds"),10);
        int generation=CallLifecycle.number(state,"generation")+1;LocalDateTime deadline=Store.now().plusSeconds(seconds);
        state.putAll(JsonSupport.map("generation",generation,"timer_kind",kind,"deadline",deadline));save(state,data);
        Map<String,Object> task=outbox.enqueue(CallLifecycle.tenant(call),"after_playback",call.get("id"),"conversation:"+call.get("id")+":"+call.get("attempts")+":"+generation,JsonSupport.map("product_kind","timer","call_id",call.get("id"),"attempt",call.get("attempts"),"generation",generation,"kind",kind));schedule(task,deadline);
    }
    public void media(Map<String,Object> call,Map<String,Object> body) {
        Map<String,Object> state=state(call);
        if(!Values.set("answered","in_ai","waiting_human").contains(call.get("status"))||Values.set("speaking","interrupted","closed").contains(body.get("state"))) {
            state.putAll(JsonSupport.map("generation",CallLifecycle.number(state,"generation")+1,"deadline",null,"timer_kind",""));save(state,json.object(state.get("data_json").toString()));
        }else if("listening".equals(body.get("state"))&&Values.set("answered","in_ai").contains(call.get("status")))arm(call,Objects.toString(body.get("error_code"),"").isEmpty()?"silence":"failure");
    }
    public static UUID uuid5(String name) {
        try {
            UUID ns=UUID.fromString("6ba7b811-9dad-11d1-80b4-00c04fd430c8");java.security.MessageDigest md=java.security.MessageDigest.getInstance("SHA-1");
            md.update(java.nio.ByteBuffer.allocate(16).putLong(ns.getMostSignificantBits()).putLong(ns.getLeastSignificantBits()).array());
            byte[] hash=md.digest(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));hash[6]=(byte)((hash[6]&15)|80);hash[8]=(byte)((hash[8]&63)|128);
            java.nio.ByteBuffer buffer=java.nio.ByteBuffer.wrap(hash);return new UUID(buffer.getLong(),buffer.getLong());
        }catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
}
