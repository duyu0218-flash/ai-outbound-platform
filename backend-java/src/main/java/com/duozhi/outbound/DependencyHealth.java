package com.duozhi.outbound;

import java.util.*;
import java.time.*;
import org.springframework.stereotype.Service;

@Service
public class DependencyHealth {
    private final Store store;private final Config config;private final ExpiringValues redis;private final TelephonyClient client;private final GatewayCluster gateways;
    public DependencyHealth(Store store,Config config,ExpiringValues redis,TelephonyClient client,GatewayCluster gateways){this.store=store;this.config=config;this.redis=redis;this.client=client;this.gateways=gateways;}
    private String http(String endpoint,String path,String token){
        if(endpoint==null||endpoint.trim().isEmpty())return "unconfigured";
        try{Settings.endpoint(endpoint,false);Map<String,Object> reply=client.get(endpoint.replaceAll("/+$","")+path,token.isEmpty()?Collections.emptyMap():Values.map("Authorization","Bearer "+token),1500);if("/readyz".equals(path)&&!"ready".equals(reply.get("status")))return "unavailable";return "ok";}
        catch(RuntimeException e){return "unavailable";}
    }
    public String telephony(long tenant){
        try{
            List<Map<String,Object>> specs=gateways.specs();
            if(!specs.isEmpty()){
                for(Map<String,Object> spec:specs){if(!Boolean.TRUE.equals(spec.get("enabled"))||((List<?>)spec.get("routes")).stream().noneMatch(route->route.toString().startsWith(tenant+":")))continue;
                    Map<String,Object> node=store.one("gatewaynode","WHERE id=?",spec.get("id"));if(node!=null&&Boolean.TRUE.equals(node.get("ready"))&&JsonSupport.integer(node.get("capacity"),0)>0&&Objects.equals(node.get("endpoint"),spec.get("endpoint"))&&!LocalDateTime.parse(node.get("checked_at").toString()).isBefore(Store.now().minusSeconds(Math.max(1,config.number("VOICE_GATEWAY_HEALTH_TTL_SEC",20)))))return "ok";}
                return "unavailable";
            }
            String provider=config.get("TELEPHONY_PROVIDER","mock");if(!"tenant".equals(provider))return "http".equals(provider)?http(config.get("TELEPHONY_PROVIDER_ENDPOINT",config.get("SIP_PROVIDER_ENDPOINT","")),"/readyz",config.get("TELEPHONY_SERVICE_TOKEN","")):"mock";
            List<Map<String,Object>> lines=store.list("telephonyline","WHERE tenant_id=? AND enabled=? ORDER BY priority,id",tenant,true);if(lines.isEmpty())return "unconfigured";boolean allMock=true;
            for(Map<String,Object> line:lines){if("mock".equals(line.get("provider")))continue;allMock=false;if("ok".equals(http(Objects.toString(line.get("gateway_url"),""),"/readyz","")))return "ok";}
            return allMock?"mock":"unavailable";
        }catch(RuntimeException e){return "unavailable";}
    }
    public Map<String,Object> services(long tenant){
        String db="ok",cache="ok";try{store.jdbc().queryForObject("SELECT 1",Integer.class);}catch(RuntimeException e){db="unavailable";}
        if(!config.get("REDIS_URL","").isEmpty())try{if(!"PONG".equals(redis.shared("PING")))cache="unavailable";}catch(RuntimeException e){cache="unavailable";}
        return JsonSupport.map("database",db,"redis",cache,"ai_agent",http(config.get("AI_AGENT_URL","http://localhost:8001"),"/health",config.get("AI_AGENT_SERVICE_TOKEN","")),"telephony",telephony(tenant));
    }
}
