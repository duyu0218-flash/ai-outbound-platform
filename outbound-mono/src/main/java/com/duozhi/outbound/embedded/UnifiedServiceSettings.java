package com.duozhi.outbound.embedded;
import com.duozhi.outbound.Config;
/** Keeps the original role's SERVICE_TOKEN mapping while all roles share the same JVM. */
public final class UnifiedServiceSettings extends Settings {
    private final Config config;private final String role;
    public UnifiedServiceSettings(Config config,String role){super(java.util.Map.of());this.config=config;this.role=role;}
    @Override public String get(String key,String fallback){
        // Preserve the old Compose role-to-environment bindings inside one process.
        String base=key.endsWith("_FILE")?key.substring(0,key.length()-5):key,suffix=key.endsWith("_FILE")?"_FILE":"";
        if(role.equals("voice")&&java.util.Set.of("WEBHOOK_TOKEN","WEBHOOK_SECRET").contains(base))return config.get("TELEPHONY_"+base+suffix,config.get(key,fallback)).strip();
        if(role.equals("recording")){
            String alias=switch(base){case "S3_ACCESS_KEY_ID"->"RECORDING_S3_ACCESS_KEY_ID";case "S3_SECRET_ACCESS_KEY"->"RECORDING_S3_SECRET_ACCESS_KEY";case "S3_BUCKET"->"RECORDING_S3_BUCKET";case "S3_KEY_PREFIX"->"RECORDING_S3_KEY_PREFIX";default->"";};
            if(!alias.isEmpty())return config.get(alias+suffix,config.get(key,fallback)).strip();
        }

        if(key.equals("SERVICE_TOKEN")||key.equals("SERVICE_TOKEN_FILE")){
            String roleKey=role.equals("agent")?"AI_AGENT_SERVICE_TOKEN":role.equals("recording")?"RECORDING_STORAGE_SERVICE_TOKEN":"TELEPHONY_SERVICE_TOKEN";
            if(key.endsWith("_FILE"))roleKey+="_FILE";
            return config.get(roleKey,config.get(key,fallback)).strip();
        }return config.get(key,fallback).strip();
    }
}
