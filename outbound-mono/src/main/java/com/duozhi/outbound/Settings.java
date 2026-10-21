package com.duozhi.outbound;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.net.URI;
import java.util.*;

@Service
public class Settings {
    private final Store store;private final Config config;private final ObjectMapper mapper;
    private final Map<String,Map<String,Object>> defaults;
    public Settings(Store store,Config config,ObjectMapper mapper)throws IOException{
        this.store=store;this.config=config;this.mapper=mapper;
        try(java.io.InputStream in=new ClassPathResource("contracts/setting-defaults.json").getInputStream()){defaults=mapper.readValue(in,new TypeReference<Map<String,Map<String,Object>>>(){});}
        override("capacity","max_concurrent_calls","MAX_CONCURRENT_CALLS");
        for(java.util.Map.Entry<java.lang.String,java.lang.String> entry:Values.<String,String>map("agent_url","AI_AGENT_URL","llm_provider","LLM_PROVIDER","llm_model","OPENAI_MODEL").entrySet())override("ai",entry.getKey(),entry.getValue());
        for(java.util.Map.Entry<java.lang.String,java.lang.String> entry:Values.<String,String>map("provider","SMS_PROVIDER","endpoint","SMS_PROVIDER_ENDPOINT","sender_id","SMS_SENDER_ID").entrySet())override("sms",entry.getKey(),entry.getValue());
        for(String key:Values.list("recording_retention_days","partial_transcript_retention_hours","final_transcript_retention_days","call_sensitive_data_retention_days"))override("compliance",key,key.transform(Ascii::upper));
        override("compliance","allowed_phone_prefixes","OUTBOUND_ALLOWED_PHONE_PREFIXES");override("compliance","max_calls_per_day","OUTBOUND_DAILY_CALL_LIMIT");
    }
    private void override(String section,String key,String env){Object old=defaults.get(section).get(key);defaults.get(section).put(key,old instanceof Number?config.number(env,((Number)old).intValue()):config.get(env,old.toString()));}
    public Map<String,Object> defaults(String section){if(!defaults.containsKey(section))throw new ApiError(404,"setting section not found");return new LinkedHashMap<>(defaults.get(section));}
    public Map<String,Object> get(long tenant,String section){
        var batch=DialBatchScope.current();String cacheKey=tenant+":"+section;
        if(batch!=null&&batch.settings.containsKey(cacheKey))return new LinkedHashMap<>(batch.settings.get(cacheKey));
        java.util.Map<java.lang.String,java.lang.Object> result=defaults(section);java.util.Map<java.lang.String,java.lang.Object> record=store.one("adminsetting","findBySection",Values.map("tenantId",tenant,"section",section));
        if(record!=null)try{Map<String,Object> saved=mapper.readValue(record.get("data_json").toString(),new TypeReference<Map<String,Object>>(){});saved.forEach((key,value)->{if(result.containsKey(key))result.put(key,value);});}catch(Exception ignored){}
        if(section.equals("ai"))result.put("agent_url",defaults.get(section).get("agent_url"));if(section.equals("sms"))result.put("endpoint",defaults.get(section).get("endpoint"));if(batch!=null)batch.settings.put(cacheKey,new LinkedHashMap<>(result));return result;
    }
    private void range(Map<String,Object> data,String key,int min,int max){Object value=data.get(key);if(!(value instanceof Number)||((Number)value).longValue()<min||((Number)value).longValue()>max)throw new ApiError(400,"invalid "+key);}
    static String endpoint(String value,boolean origin){
        try{
            if(value.chars().anyMatch(c->c<33||c==127))throw new IllegalArgumentException();
            URI uri=URI.create(value);if(!Values.set("http","https").contains(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getFragment()!=null||(!origin&&uri.getQuery()!=null))throw new IllegalArgumentException();
            int port=uri.getPort()<0?(uri.getScheme().equals("https")?443:80):uri.getPort();if(port<1||port>65535)throw new IllegalArgumentException();
            String host=uri.getHost().transform(Ascii::lower).replaceAll("\\.$","");return uri.getScheme()+"://"+host+":"+port+(origin?"":Objects.toString(uri.getPath(),"").replaceAll("/+$",""));
        }catch(IllegalArgumentException e){throw new ApiError(422,"invalid service endpoint");}
    }
    public Map<String,Object> validate(String section,Map<String,Object> input){
        java.util.Map<java.lang.String,java.lang.Object> data=defaults(section);
        for(java.util.Map.Entry<java.lang.String,java.lang.Object> entry:input.entrySet()){
            if(!data.containsKey(entry.getKey()))throw new ApiError(400,"unsupported setting fields: "+entry.getKey());
            Object expected=data.get(entry.getKey()),value=entry.getValue();
            boolean same=value!=null&&(expected instanceof Integer?(value instanceof Integer||value instanceof Long):expected.getClass().isInstance(value));
            if(!same)throw new ApiError(400,"invalid setting type: "+entry.getKey());
            if(value instanceof String&&((String)value).length()>4000)throw new ApiError(400,"setting value too large: "+entry.getKey());data.put(entry.getKey(),value);
        }
        try{if(mapper.writeValueAsString(data).length()>20000)throw new ApiError(400,"setting payload too large");}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new ApiError(400,"invalid settings");}
        if(section.equals("capacity"))range(data,"max_concurrent_calls",1,100000);
        if(section.equals("compliance")){
            range(data,"allowed_start_hour",0,23);range(data,"allowed_end_hour",0,23);range(data,"max_attempts_per_day",1,20);range(data,"min_attempt_interval_sec",0,604800);range(data,"max_calls_per_day",1,10000000);
            range(data,"recording_retention_days",1,3650);range(data,"partial_transcript_retention_hours",1,720);range(data,"final_transcript_retention_days",1,3650);range(data,"call_sensitive_data_retention_days",1,3650);Contacts.timezone(data.get("timezone"));
            for(String prefix:data.get("allowed_phone_prefixes").toString().split(","))if(!prefix.trim().isEmpty()&&!prefix.trim().matches("[0-9]{1,15}"))throw new ApiError(400,"invalid allowed phone prefixes");
            if(config.production()&&(!Boolean.TRUE.equals(data.get("dnc_enforced"))||!Boolean.TRUE.equals(data.get("require_explicit_consent"))||!Boolean.TRUE.equals(data.get("require_explicit_consent_for_direct_calls"))||data.get("allowed_phone_prefixes").toString().trim().isEmpty()))throw new ApiError(400,"production requires consent, DNC and allowed phone prefixes");
        }
        if(section.equals("ai")){
            if(!Values.set("rule","openai-compatible").contains(data.get("llm_provider")))throw new ApiError(400,"unsupported llm provider");
            if(data.get("llm_provider").equals("openai-compatible")&&!Boolean.TRUE.equals(data.get("external_llm_enabled")))throw new ApiError(400,"external LLM requires explicit tenant approval");
            if(Boolean.TRUE.equals(data.get("enabled"))&&data.get("agent_url").toString().trim().isEmpty())throw new ApiError(400,"enabled AI requires agent_url");
            range(data,"conversation_history_turns",1,50);range(data,"max_reply_chars",20,2000);range(data,"pipecat_canary_percent",0,100);
            if(data.get("fallback_reply").toString().trim().isEmpty()||!Values.set("legacy","pipecat").contains(data.get("voice_ai_pipeline")))throw new ApiError(400,"invalid AI policy");
            if(!data.get("agent_url").toString().trim().isEmpty()&&!endpoint(data.get("agent_url").toString(),false).equals(endpoint(defaults.get("ai").get("agent_url").toString(),false)))throw new ApiError(422,"AI endpoint is managed by the platform");
        }
        if(section.equals("sms")){
            if(!Values.set("mock","http").contains(data.get("provider")))throw new ApiError(400,"unsupported SMS provider");
            if(Boolean.TRUE.equals(data.get("enabled"))&&data.get("provider").equals("http")&&data.get("endpoint").toString().trim().isEmpty())throw new ApiError(400,"HTTP SMS provider requires endpoint");
            if(!data.get("endpoint").toString().trim().isEmpty()&&!endpoint(data.get("endpoint").toString(),false).equals(endpoint(defaults.get("sms").get("endpoint").toString(),false)))throw new ApiError(422,"SMS endpoint is managed by the platform");
        }
        if(section.equals("integration")){
            range(data,"webhook_timeout_sec",1,120);range(data,"webhook_retry_times",0,10);range(data,"webhook_retry_backoff_sec",1,60);
            String secret=data.get("webhook_secret_ref").toString(),url=data.get("webhook_base_url").toString();
            if(!secret.trim().isEmpty()&&!secret.matches("[A-Z0-9_]+"))throw new ApiError(400,"invalid webhook secret reference");
            if(Boolean.TRUE.equals(data.get("callback_enabled"))&&(url.trim().isEmpty()||(config.production()&&secret.trim().isEmpty())))throw new ApiError(400,"callback destination and signing reference required");
            if(!url.trim().isEmpty()){
                String origin=endpoint(url,true);boolean allowed=Arrays.stream(config.get("BUSINESS_CALLBACK_ALLOWED_ORIGINS","").split(",")).filter(s->!s.trim().isEmpty()).map(s->endpoint(s.trim(),false)).anyMatch(origin::equals);
                if(!allowed)throw new ApiError(422,"callback origin is not registered by the platform");if(config.production()&&!url.startsWith("https://"))throw new ApiError(422,"production callbacks require HTTPS");
            }
        }
        return data;
    }
}
