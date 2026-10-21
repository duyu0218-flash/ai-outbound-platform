package com.duozhi.outbound;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class TelephonyClient {
    @org.springframework.beans.factory.annotation.Autowired private DialAuthorizationService authorizations;
    @org.springframework.beans.factory.annotation.Autowired private DialCommandJournal journal;
    @org.springframework.beans.factory.annotation.Autowired private DialDrainControl drain;
    public static class Failure extends RuntimeException {
        public final int status;
        public final boolean rejectedBeforeAdmission;
        public Failure(int status, boolean rejectedBeforeAdmission) {
            super("telephony command failed (" + status + ")"); this.status = status; this.rejectedBeforeAdmission = rejectedBeforeAdmission;
        }
        // A transport code/header cannot revoke a previously issued command.
        // Resource release requires a persisted, identity-bound cancellation.
        public boolean definitive() { return false; }
    }
    private final Store store;
    private final Config config;
    private final JsonSupport json;private final EmbeddedServiceTransport embedded;
    public TelephonyClient(Store store, Config config, JsonSupport json,EmbeddedServiceTransport embedded) { this.store = store; this.config = config; this.json = json;this.embedded=embedded; }

    private int protocol(Map<String,Object> call){return journal==null?config.number("JAVA_DIAL_AUTH_PROTOCOL",1):journal.protocol(call);}
    public boolean mock(Map<String, Object> call) { return "mock".equals(route(call).get("provider"))&&protocol(call)==1; }
    public void validate(Map<String, Object> call) {
        var route=route(call,false);call.put("_dial_route",route.get("descriptor"));
        if (!("mock".equals(route.get("provider"))&&protocol(call)==1) && config.get("VOICE_COMMAND_SECRET", "").isEmpty()) throw new ApiError(503, "real telephony requires a signing secret");
    }
    private Map<String, Object> route(Map<String, Object> call) {
        return route(call,true);
    }
    @SuppressWarnings("unchecked")
    private Map<String,Object> route(Map<String,Object> call,boolean original){
        Object saved=original?call.get("_dial_route"):null;
        if(original&&saved==null&&journal!=null&&CallLifecycle.number(call,"attempts")>0){
            var frozen=journal.frozen(CallLifecycle.tenant(call),call.get("id"),CallLifecycle.number(call,"attempts"));
            if(frozen!=null&&frozen.get("call") instanceof Map<?,?> old)saved=old.get("_dial_route");
        }
        if(saved instanceof Map<?,?> descriptor){
            String key=Objects.toString(descriptor.get("credential_key"),""),bearer=key.isEmpty()?"":config.get(key,"");
            if(Boolean.TRUE.equals(descriptor.get("credential_required"))&&bearer.isEmpty())throw new ApiError(503,"original telephony credential reference unavailable");
            return JsonSupport.map("provider",descriptor.get("provider"),"endpoint",descriptor.get("endpoint"),"bearer",bearer,"descriptor",descriptor);
        }
        var batch=DialBatchScope.current();List<Map<String,Object>> known=batch==null?null:batch.lines.get(CallLifecycle.tenant(call));
        Map<String, Object> line = known==null?(call.get("telephony_line_id") == null ? store.one("telephonyline","sourceTelephonyClient30_0",Store.queryParameters(0,CallLifecycle.tenant(call),true))
                : store.require("telephonyline", CallLifecycle.tenant(call), call.get("telephony_line_id"))):known.stream().filter(l->call.get("telephony_line_id")==null||l.get("id").toString().equals(call.get("telephony_line_id").toString())).findFirst().orElse(null);
        String provider = line == null ? config.get("TELEPHONY_PROVIDER", "mock").trim().transform(Ascii::lower) : line.get("provider").toString().trim().transform(Ascii::lower);
        String endpoint = line == null ? config.get("TELEPHONY_PROVIDER_ENDPOINT", config.get("SIP_PROVIDER_ENDPOINT", "")) : Objects.toString(line.get("gateway_url"), "");
        String credential = line == null ? "" : Objects.toString(line.get("credential_ref"), "");
        String bearer = line == null || call.get("gateway_node_id") != null ? config.get("TELEPHONY_SERVICE_TOKEN", "") : "";
        String credentialKey=line==null||call.get("gateway_node_id")!=null?"TELEPHONY_SERVICE_TOKEN":"";boolean required=false;
        if (call.get("gateway_node_id") != null) { provider = "http"; endpoint = Objects.toString(call.get("gateway_endpoint"), ""); }
        if (provider.equals("tenant")) throw new ApiError(503, "no enabled telephony line configured for tenant");
        if (!provider.equals("mock")) Settings.endpoint(endpoint, false);
        if (!credential.trim().isEmpty()) {
            if(!credential.trim().matches("[A-Za-z0-9_]{1,128}"))throw new ApiError(422,"invalid credential reference");
            String ref = Ascii.upper(credential.trim());
            credentialKey="TELEPHONY_SECRET_"+ref;required=true;
            bearer = config.get("TELEPHONY_SECRET_" + ref, "");
            if (bearer.isEmpty()) throw new ApiError(503, "telephony credential reference unavailable");
        }
        String normalizedEndpoint=endpoint.replaceAll("/+$","");
        return JsonSupport.map("provider", provider, "endpoint", normalizedEndpoint, "bearer", bearer,"descriptor",JsonSupport.map("provider",provider,"endpoint",normalizedEndpoint,"credential_key",credentialKey,"credential_required",required));
    }
    public Map<String, Object> command(Map<String, Object> call, String action, Map<String, Object> fields) {
        TaskWorker.permitted();
        if("dial".equals(action)&&!drain.sending(call))throw new TaskWorker.Deferred();
        Map<String, Object> route = route(call);
        int protocol=protocol(call);
        if ("mock".equals(route.get("provider"))&&protocol==1) {
            if("dial-confirmation".equals(action))return journal.confirmation(call);
            if ("speak".equals(action)) return JsonSupport.map("playback_complete", true);
            if ("hangup".equals(action)) return JsonSupport.map("ended", true, "provider_call_id", "mock-" + call.get("id"));
            return JsonSupport.map("result", "accepted", "provider_call_id", "mock-" + call.get("id"), "state", "accepted");
        }
        var payload=payload(call,action,fields,protocol);int timeout=Math.max(1,config.number("TELEPHONY_TIMEOUT_SEC",8))*1000;
        if(Values.set("dial","dial-cancel").contains(action)&&protocol==2){
            var requests=List.of(new DialAuthorizationService.Request(call,payload));authorizations.prepare(requests);
            var install=signedPayload("dial-authorize",payload,route);var reply=embedded.command("dial-authorize",install.body(),install.headers(),timeout);
            authorizations.acknowledge(requests,List.of(new com.duozhi.outbound.embedded.VoiceController.CommandResult(reply,200,false)));
        }
        TaskWorker.permitted();var command=signedPayload(action,payload,route);
        try{return embedded.command(action,command.body(),command.headers(),timeout);}
        catch(Failure ambiguous){
            if(action.equals("dial")&&protocol==2){var cancellation=signedPayload("dial-cancel",payload,route);return embedded.command("dial-cancel",cancellation.body(),cancellation.headers(),timeout);}
            throw ambiguous;
        }
    }
    private Map<String,Object> payload(Map<String,Object> call,String action,Map<String,Object> fields,int protocol){
        var payload=new LinkedHashMap<>(fields);payload.put("call_id",call.get("id"));payload.put("tenant_id",call.get("tenant_id"));
        if(!Values.set("dial","dial-authorize","dial-cancel","dial-confirmation").contains(action)){payload.put("expected_attempt",call.get("attempts"));payload.put("provider_call_id",call.get("telephony_call_id"));}
        if(protocol==2)payload.put("protocol_version",2);return payload;
    }
    private com.duozhi.outbound.embedded.VoiceController.Command signedPayload(String action,Map<String,Object> payload,Map<String,Object> route){
        String secret = config.get("VOICE_COMMAND_SECRET", "");
        if (secret.isEmpty()) throw new ApiError(503, "real telephony requires a signing secret");
        String path = "/v1/call/" + action;
        byte[] body = json.canonical(payload, false).getBytes(StandardCharsets.UTF_8);
        String stamp = Long.toString(Instant.now().getEpochSecond()), nonce = UUID.randomUUID().toString().replace("-", "");
        String signature = JsonSupport.hmac(secret, (stamp + "." + nonce + "." + path + "." + new String(body, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8));
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("x-voice-timestamp", stamp); headers.put("x-voice-nonce", nonce); headers.put("x-voice-signature", signature);
        if (!route.get("bearer").toString().isEmpty()) headers.put("Authorization", "Bearer " + route.get("bearer"));
        return new com.duozhi.outbound.embedded.VoiceController.Command(action,body,headers);
    }
    List<com.duozhi.outbound.embedded.VoiceController.CommandResult> dialBatch(List<Map<String,Object>> entries){
        TaskWorker.permitted();
        if(drain.sendingClosed())throw new TaskWorker.Deferred();
        var payloads=new ArrayList<Map<String,Object>>();var resolvedRoutes=new ArrayList<Map<String,Object>>();
        var installRequests=new ArrayList<DialAuthorizationService.Request>();var installRoutes=new ArrayList<Map<String,Object>>();
        var routes=new HashMap<String,Map<String,Object>>();String base=config.get("TELEPHONY_WEBHOOK_BASE","http://localhost:8000").replaceAll("/+$","");
        for(var entry:entries){
            var call=(Map<String,Object>)entry.get("call");var metadata=(Map<String,Object>)entry.get("metadata");
            var route=routes.computeIfAbsent(call.get("tenant_id")+":"+call.get("telephony_line_id")+":"+call.get("gateway_node_id"),key->route(call));int protocol=protocol(call);
            if("mock".equals(route.get("provider"))&&protocol==1)throw new IllegalArgumentException("direct legacy mock uses durable mock status tasks");
            var fields=JsonSupport.map("phone",call.get("phone"),"caller_id",metadata.getOrDefault("caller_id",""),"webhook_url",metadata.getOrDefault("status_webhook_url",base+"/api/v1/webhooks/telephony/status"),"metadata",metadata);
            var payload=payload(call,"dial",fields,protocol);payloads.add(payload);resolvedRoutes.add(route);
            if(protocol==2){installRequests.add(new DialAuthorizationService.Request(call,payload));installRoutes.add(route);}
        }
        int timeout=Math.max(1,config.number("TELEPHONY_TIMEOUT_SEC",8))*1000;
        if(!installRequests.isEmpty()){
            long phase=System.nanoTime();authorizations.prepare(installRequests);profile("grant_prepare",installRequests.size(),phase);var installs=new ArrayList<com.duozhi.outbound.embedded.VoiceController.Command>();
            for(int i=0;i<installRequests.size();i++)installs.add(signedPayload("dial-authorize",installRequests.get(i).payload(),installRoutes.get(i)));
            phase=System.nanoTime();var installed=embedded.commands(installs,timeout);profile("gateway_install",installRequests.size(),phase);
            phase=System.nanoTime();authorizations.acknowledge(installRequests,installed);profile("installation_ack",installRequests.size(),phase);
        }
        TaskWorker.permitted();var commands=new ArrayList<com.duozhi.outbound.embedded.VoiceController.Command>();
        for(int i=0;i<payloads.size();i++)commands.add(signedPayload("dial",payloads.get(i),resolvedRoutes.get(i)));
        if(drain.sendingClosed())throw new TaskWorker.Deferred();
        long sent=System.nanoTime();var replies=new ArrayList<>(embedded.commands(commands,timeout));profile("gateway_dispatch",commands.size(),sent);
        if(replies.size()!=commands.size())return replies;
        for(int i=0;i<replies.size();i++)if(replies.get(i).status()!=200&&payloads.get(i).get("protocol_version") instanceof Number version&&version.intValue()==2){
            try{var cancel=signedPayload("dial-cancel",payloads.get(i),resolvedRoutes.get(i));replies.set(i,new com.duozhi.outbound.embedded.VoiceController.CommandResult(embedded.command("dial-cancel",cancel.body(),cancel.headers(),timeout),200,false));}catch(RuntimeException unproven){/* Keep the original failed result; resources remain reserved. */}
        }
        return replies;
    }
    private void profile(String phase,int count,long begin){if(config.flag("JAVA_DIAL_PROFILE",false))org.slf4j.LoggerFactory.getLogger(getClass()).info("dial_protocol phase={} size={} elapsed_ms={}",phase,count,(System.nanoTime()-begin)/1_000_000.0);}
    public Map<String, Object> post(String endpoint, byte[] body, Map<String, String> headers, int timeoutMs) {
        return post(endpoint, body, headers, timeoutMs, false);
    }
    public Map<String, Object> post(String endpoint, byte[] body, Map<String, String> headers, int timeoutMs, boolean allowEmpty) {
        return request(endpoint, body, headers, timeoutMs, allowEmpty);
    }
    public Map<String, Object> get(String endpoint, Map<String, String> headers, int timeoutMs) {
        return request(endpoint, null, headers, timeoutMs, false);
    }
    private Map<String, Object> request(String endpoint, byte[] body, Map<String, String> headers, int timeoutMs, boolean allowEmpty) {
        HttpURLConnection connection = null;
        try {
            SecurityHeaders.outbound(headers);
            Settings.endpoint(endpoint, false); // Reject non-HTTP schemes and URL credentials at the transport boundary.
            connection = (HttpURLConnection)new URL(endpoint).openConnection(Proxy.NO_PROXY);
            connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(timeoutMs); connection.setReadTimeout(timeoutMs);
            connection.setRequestMethod(body == null ? "GET" : "POST");
            if (body != null) { connection.setDoOutput(true); connection.setFixedLengthStreamingMode(body.length); }
            connection.setRequestProperty("Content-Type", "application/json");
            for (Map.Entry<String, String> header : headers.entrySet()) connection.setRequestProperty(header.getKey(), header.getValue());
            if (body != null) try (OutputStream out = connection.getOutputStream()) { out.write(body); }
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw new Failure(status, status == 429 && "false".equals(connection.getHeaderField("X-Voice-Dial-Admitted")));
            try (InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192]; int size;
                while ((size = in.read(buffer)) != -1) { if (out.size() + size > 1048576) throw new Failure(0, false); out.write(buffer, 0, size); }
                if (allowEmpty && out.size() == 0) return Collections.emptyMap();
                return json.object(new String(out.toByteArray(), StandardCharsets.UTF_8));
            }
        } catch (IOException | ApiError e) { throw new Failure(0, false); }
        finally { if (connection != null) connection.disconnect(); }
    }
}
