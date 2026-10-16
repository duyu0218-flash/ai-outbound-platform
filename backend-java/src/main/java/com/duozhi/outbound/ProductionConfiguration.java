package com.duozhi.outbound;

import java.util.*;
import java.net.URI;
import tools.jackson.databind.ObjectMapper;

/** Port of baseline production configuration checks; does not approve a release. */
final class ProductionConfiguration {
    private static boolean weak(String value,int minimum){String s=value.trim().toLowerCase(Locale.ROOT);return s.length()<minimum||Arrays.asList("change-me","replace-me","example","dev-","your-").stream().anyMatch(s::contains);}
    static void validate(Config c) {
        if(!c.production())return;
        List<String> errors=new ArrayList<>();
        for(String key:Arrays.asList("SECRET_KEY","JWT_SECRET","TELEPHONY_WEBHOOK_SECRET","VOICE_COMMAND_SECRET","OUTBOUND_SECURITY_APPROVAL_TOKEN"))if(weak(c.get(key,""),32))errors.add(key);
        for(String key:Arrays.asList("TELEPHONY_SERVICE_TOKEN","AI_AGENT_SERVICE_TOKEN"))if(weak(c.get(key,""),24))errors.add(key);
        if(c.get("JWT_SECRET","").equals(c.get("SECRET_KEY","")))errors.add("JWT_SECRET must differ from SECRET_KEY");
        Set<String> voice=new HashSet<>();for(String key:Arrays.asList("TELEPHONY_SERVICE_TOKEN","VOICE_COMMAND_SECRET","TELEPHONY_WEBHOOK_SECRET","OUTBOUND_SECURITY_APPROVAL_TOKEN"))voice.add(c.get(key,""));if(voice.size()!=4)errors.add("voice secrets must be distinct");
        if(c.get("AI_AGENT_SERVICE_TOKEN","").equals(c.get("TELEPHONY_SERVICE_TOKEN","")))errors.add("internal service tokens must differ");
        boolean tenantKeys=false;String configured=c.get("TENANT_API_KEYS_JSON","");if(!configured.trim().isEmpty())try{
            Map<?,?> values=new ObjectMapper().readValue(configured,Map.class);tenantKeys=!values.isEmpty();for(Map.Entry<?,?> key:values.entrySet())tenantKeys&=key.getKey().toString().matches("[1-9][0-9]*")&&!weak(Objects.toString(key.getValue(),""),24);if(!tenantKeys)errors.add("TENANT_API_KEYS_JSON");
        }catch(Exception e){errors.add("TENANT_API_KEYS_JSON");}
        if(!tenantKeys&&weak(c.get("API_KEY",""),24))errors.add("API_KEY");
        for(String key:Arrays.asList("DEMO_USERS_ENABLED","AUTO_MIGRATE","JAVA_INITIALIZE_SCHEMA","TASK_INLINE_EXECUTION_ENABLED"))if(c.flag(key,false))errors.add(key+" must be false");
        if(c.get("TRUSTED_HOSTS","").trim().isEmpty()||Arrays.stream(c.get("TRUSTED_HOSTS","").split(",")).map(String::trim).anyMatch("*"::equals))errors.add("TRUSTED_HOSTS");
        if(Arrays.stream(c.get("CORS_ALLOW_ORIGINS","").split(",")).map(String::trim).anyMatch("*"::equals))errors.add("CORS_ALLOW_ORIGINS wildcard");
        if(c.get("TELEPHONY_WEBHOOK_TOKEN","").trim().isEmpty())errors.add("TELEPHONY_WEBHOOK_TOKEN");
        if(!c.get("spring.datasource.url",c.get("JAVA_DATABASE_URL","")).startsWith("jdbc:postgresql:"))errors.add("PostgreSQL is required");
        if("mock".equals(c.get("TELEPHONY_PROVIDER","mock")))errors.add("TELEPHONY_PROVIDER=mock");
        try{URI redis=URI.create(c.get("REDIS_URL",""));String credentials=Objects.toString(redis.getUserInfo(),"");String password=credentials.contains(":")?credentials.substring(credentials.indexOf(':')+1):"";if(!Values.set("redis","rediss").contains(redis.getScheme())||weak(password,16))errors.add("REDIS_URL");}catch(RuntimeException e){errors.add("REDIS_URL");}
        String prefixes=c.get("OUTBOUND_ALLOWED_PHONE_PREFIXES","");if(prefixes.trim().isEmpty()||Arrays.stream(prefixes.split(",")).anyMatch(v->!v.trim().matches("[0-9]{1,15}")))errors.add("OUTBOUND_ALLOWED_PHONE_PREFIXES");
        int daily=c.number("OUTBOUND_DAILY_CALL_LIMIT",10000);if(daily<1||daily>10000000)errors.add("OUTBOUND_DAILY_CALL_LIMIT");
        if(c.number("RECORDING_RETENTION_DAYS", 90)>0)for(String kind:Arrays.asList("RECORDING_INGEST","RECORDING_DELETE")){if(c.get(kind+"_ENDPOINT","").trim().isEmpty())errors.add(kind+"_ENDPOINT");if(weak(c.get(kind+"_SERVICE_TOKEN",""),24))errors.add(kind+"_SERVICE_TOKEN");}
        if("http".equals(c.get("SMS_PROVIDER","mock"))){for(String key:Arrays.asList("SMS_CALLBACK_URL","SMS_WEBHOOK_TOKEN"))if(c.get(key,"").trim().isEmpty())errors.add(key);if(weak(c.get("SMS_WEBHOOK_SECRET",""),32))errors.add("SMS_WEBHOOK_SECRET");}
        if(c.flag("WEBRTC_ENABLED",false)){if(!c.get("WEBRTC_WSS_URL","").startsWith("wss://"))errors.add("WEBRTC_WSS_URL");for(String key:Arrays.asList("WEBRTC_SIP_DOMAIN","TURN_URLS"))if(c.get(key,"").trim().isEmpty())errors.add(key);for(String key:Arrays.asList("TURN_SHARED_SECRET","FREESWITCH_DIRECTORY_TOKEN"))if(weak(c.get(key,""),24))errors.add(key);}
        String metrics=c.get("METRICS_TOKEN","");if(!c.get("METRICS_TOKEN_FILE","").isEmpty())try{metrics=new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(c.get("METRICS_TOKEN_FILE",""))),java.nio.charset.StandardCharsets.UTF_8).trim();}catch(Exception e){errors.add("METRICS_TOKEN_FILE");}if(weak(metrics,24))errors.add("METRICS_TOKEN");
        if(!c.flag("JAVA_RELEASE_ACCEPTED",false))errors.add("JAVA_RELEASE_ACCEPTED requires separate release acceptance");
        if(!errors.isEmpty())throw new IllegalStateException("Production configuration rejected: "+String.join(", ",errors));
    }
}
