package com.duozhi.outbound;

import java.util.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import javax.net.ssl.*;
import org.springframework.stereotype.Service;

@Service
public class IntegrationTasks {
    private final Store store;private final Transactions tx;private final Config config;private final Settings settings;private final JsonSupport json;private final TelephonyClient client;
    public IntegrationTasks(Store store,Transactions tx,Config config,Settings settings,JsonSupport json,TelephonyClient client){this.store=store;this.tx=tx;this.config=config;this.settings=settings;this.json=json;this.client=client;}
    public void execute(String type,Map<String,Object> task,Map<String,Object> payload){
        if("business_callback".equals(type))callback(task,payload);
        else if("send_sms".equals(type))sms(payload);
        else recording(type,task,payload);
    }
    @SuppressWarnings("unchecked") private void callback(Map<String,Object> task,Map<String,Object> payload){
        long tenant=CallLifecycle.tenant(task);Map<String,Object> integration=settings.get(tenant,"integration");String endpoint=Objects.toString(integration.get("webhook_base_url"),"").trim();
        if(!Boolean.TRUE.equals(integration.get("callback_enabled"))||endpoint.isEmpty())return;
        settings.validate("integration",integration);Map<String,Object> call=store.one("callsession","WHERE tenant_id=? AND id=?",tenant,store.encode("callsession","id",payload.get("call_id")));if(call==null)return;
        byte[] body=json.json(JsonSupport.map("event",payload.get("event_type"),"tenant_id",tenant,"call_id",call.get("id"),"campaign_id",call.get("campaign_id"),"phone",call.get("phone"),"mode",call.get("mode"),"status",call.get("status"),"data",payload.getOrDefault("data",Collections.emptyMap()))).getBytes(StandardCharsets.UTF_8);
        Map<String,String> headers=new LinkedHashMap<>();headers.put("X-Delivery-ID",task.get("id").toString());headers.put("Idempotency-Key",task.get("id").toString());
        String ref=Objects.toString(integration.get("webhook_secret_ref"),"").trim();
        if(!ref.isEmpty()){String secret=config.get("BUSINESS_WEBHOOK_SECRET_"+ref,"");if(secret.isEmpty())throw new ApiError(503,"business callback signing secret is unavailable");String stamp=Long.toString(Instant.now().getEpochSecond());byte[] prefix=(stamp+".").getBytes(StandardCharsets.US_ASCII),signed=Arrays.copyOf(prefix,prefix.length+body.length);System.arraycopy(body,0,signed,prefix.length,body.length);headers.put("X-Webhook-Timestamp",stamp);headers.put("X-Webhook-Signature","sha256="+JsonSupport.hmac(secret,signed));}
        boolean success=false;int status=0;
        try{TaskWorker.permitted();status=pinnedPost(endpoint,body,headers,Math.min(60,JsonSupport.integer(integration.get("webhook_timeout_sec"),10))*1000);success=status>=200&&status<300;if(!success)throw new ApiError(502,"callback response "+status);}
        finally{final boolean ok=success;final int code=status;tx.run(()->{TaskWorker.permitted();store.insert("callevent",JsonSupport.map("call_session_id",call.get("id"),"event_type","business_callback_"+(ok?"delivered":"failed"),"source","integration","payload",json.json(JsonSupport.map("event",payload.get("event_type"),"status_code",code,"attempts",1))));return null;});}
    }
    private boolean publicAddress(InetAddress address){
        if(address.isAnyLocalAddress()||address.isLoopbackAddress()||address.isLinkLocalAddress()||address.isSiteLocalAddress()||address.isMulticastAddress())return false;
        byte[] b=address.getAddress();int a=b[0]&255;
        if(b.length==4){int second=b[1]&255,third=b[2]&255;return a!=0&&a!=127&&a<224&&!(a==100&&second>=64&&second<=127)&&!(a==192&&second==0)&&!(a==192&&second==168)&&!(a==192&&second==88&&third==99)&&!(a==198&&(second==18||second==19||second==51&&third==100))&&!(a==203&&second==0&&third==113);}
        return (a&0xe0)==0x20&&!(a==0x20&&(b[1]&255)==1&&(b[2]&255)==0x0d&&(b[3]&255)==0xb8);
    }
    /** Connect to a resolved address once; preserve original host for TLS checks. */
    int pinnedPost(String endpoint,byte[] body,Map<String,String> headers,int timeout){
        URI uri=URI.create(endpoint);String origin=Settings.endpoint(endpoint,true);boolean privateAllowed=Arrays.stream(config.get("BUSINESS_CALLBACK_PRIVATE_ORIGINS","").split(",")).filter(v->!v.trim().isEmpty()).map(v->Settings.endpoint(v.trim(),false)).anyMatch(origin::equals);
        String host=uri.getHost();int port=uri.getPort()<0?("https".equals(uri.getScheme())?443:80):uri.getPort();
        java.util.concurrent.ExecutorService resolver=java.util.concurrent.Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"callback-dns");t.setDaemon(true);return t;});
        Socket socket=null;
        try{
            InetAddress[] addresses=resolver.submit(()->InetAddress.getAllByName(host)).get(3,java.util.concurrent.TimeUnit.SECONDS);
            if(addresses.length==0||!privateAllowed&&Arrays.stream(addresses).anyMatch(a->!publicAddress(a)))throw new ApiError(422,"callback DNS resolved to an unauthorized network");
            TaskWorker.permitted();socket=new Socket();socket.connect(new InetSocketAddress(addresses[0],port),timeout);socket.setSoTimeout(timeout);
            if("https".equals(uri.getScheme())){SSLSocket tls=(SSLSocket)((SSLSocketFactory)SSLSocketFactory.getDefault()).createSocket(socket,host,port,true);SSLParameters parameters=tls.getSSLParameters();parameters.setEndpointIdentificationAlgorithm("HTTPS");if(!host.contains(":"))parameters.setServerNames(Collections.singletonList(new SNIHostName(host)));tls.setSSLParameters(parameters);tls.setSoTimeout(timeout);tls.startHandshake();socket=tls;}
            String path=Objects.toString(uri.getRawPath(),"");if(path.isEmpty())path="/";if(uri.getRawQuery()!=null)path+="?"+uri.getRawQuery();
            StringBuilder request=new StringBuilder("POST ").append(path).append(" HTTP/1.1\r\nHost: ").append(uri.getRawAuthority()).append("\r\nContent-Type: application/json\r\nConnection: close\r\nContent-Length: ").append(body.length).append("\r\n");
            for(Map.Entry<String,String> h:headers.entrySet()){if(h.getValue().contains("\r")||h.getValue().contains("\n"))throw new ApiError(422,"invalid callback header");request.append(h.getKey()).append(": ").append(h.getValue()).append("\r\n");}
            OutputStream out=socket.getOutputStream();out.write(request.append("\r\n").toString().getBytes(StandardCharsets.US_ASCII));out.write(body);out.flush();
            InputStream in=socket.getInputStream();ByteArrayOutputStream line=new ByteArrayOutputStream();for(int i=0;i<8192;i++){int value=in.read();if(value<0)throw new EOFException();if(value=='\n')break;line.write(value);}
            String first=new String(line.toByteArray(),StandardCharsets.US_ASCII);if(!first.matches("HTTP/1\\.[01] [0-9]{3}.*"))throw new IOException("invalid callback response");return Integer.parseInt(first.substring(9,12));
        }catch(ApiError e){throw e;}catch(Exception e){throw new ApiError(502,"callback transport failed");}finally{resolver.shutdownNow();if(socket!=null)try{socket.close();}catch(IOException ignored){}}
    }
    private void recording(String type,Map<String,Object> task,Map<String,Object> payload){
        long tenant=CallLifecycle.tenant(task);Map<String,Object> asset=store.one("recordingasset","WHERE tenant_id=? AND id=?",tenant,payload.get("recording_asset_id"));if(asset==null||asset.get("deleted_at")!=null)return;
        boolean delete="recording_delete".equals(type);if(!delete&&(!Objects.toString(asset.get("storage_uri"),"").isEmpty()||asset.get("retention_until")!=null&&!LocalDateTime.parse(asset.get("retention_until").toString()).isAfter(Store.now())))return;
        String prefix=delete?"RECORDING_DELETE":"RECORDING_INGEST",endpoint=config.get(prefix+"_ENDPOINT","");Settings.endpoint(endpoint,false);
        Map<String,Object> body=JsonSupport.map("recording_asset_id",asset.get("id"),"tenant_id",tenant,"call_id",asset.get("call_session_id"),"provider_recording_id",asset.get("provider_recording_id"),"provider_url",asset.get("provider_url"),"storage_uri",asset.get("storage_uri"),"media_format",asset.get("media_format"),"channel_count",asset.get("channel_count"));
        Map<String,String> headers=new LinkedHashMap<>();String token=config.get(prefix+"_SERVICE_TOKEN","");if(!token.isEmpty())headers.put("Authorization","Bearer "+token);headers.put("Idempotency-Key",task.get("id").toString());
        TaskWorker.permitted();Map<String,Object> result=client.post(endpoint,json.json(body).getBytes(StandardCharsets.UTF_8),headers,Math.max(1,config.number(prefix+"_TIMEOUT_SEC",30))*1000,delete);
        String uri=Objects.toString(result.get("storage_uri"),""),checksum=Objects.toString(result.get("checksum_sha256"),"");if(delete&&Boolean.FALSE.equals(result.get("deleted"))||!delete&&(uri.isEmpty()||!checksum.isEmpty()&&!checksum.matches("[0-9a-fA-F]{64}")))throw new ApiError(502,"recording adapter did not confirm operation");
        tx.run(()->{TaskWorker.permitted();Map<String,Object> latest=store.require("recordingasset",tenant,asset.get("id"));if(latest.get("deleted_at")==null)store.update("recordingasset",tenant,asset.get("id"),delete?JsonSupport.map("provider_url","","storage_uri","","provider_recording_id",null,"state","deleted","deleted_at",Store.now()):JsonSupport.map("storage_uri",uri,"checksum_sha256",checksum.toLowerCase(Locale.ROOT),"state","available"));return null;});
    }
    public Map<String,Object> send(long tenant,String phone,String text){
        Map<String,Object> sms=settings.get(tenant,"sms");if(!Boolean.TRUE.equals(sms.get("enabled")))return JsonSupport.map("state","disabled");
        if("mock".equals(sms.get("provider")))return JsonSupport.map("state","sent_mock");
        String endpoint=config.get("SMS_PROVIDER_ENDPOINT","");Settings.endpoint(endpoint,false);TaskWorker.permitted();
        return client.post(endpoint.replaceAll("/+$","")+"/v1/sms/send",json.json(JsonSupport.map("to",phone,"text",text,"from",sms.get("sender_id"),"sender_id",sms.get("sender_id"),"callback_url",config.get("SMS_CALLBACK_URL",""))).getBytes(StandardCharsets.UTF_8),Values.map("Authorization","Bearer "+config.get("SMS_API_KEY","")),config.number("TELEPHONY_TIMEOUT_SEC",10)*1000);
    }
    private void sms(Map<String,Object> payload){
        Map<String,Object> call=store.one("callsession","WHERE id=?",store.encode("callsession","id",payload.get("call_id")));if(call==null||CallLifecycle.number(call,"attempts")!=JsonSupport.integer(payload.get("attempt"),-1))return;
        long tenant=CallLifecycle.tenant(call);String text=payload.get("text").toString();Map<String,Object> result;
        try{result=send(tenant,call.get("phone").toString(),text);}catch(RuntimeException e){result=JsonSupport.map("state","failed","error","provider request failed");}
        final Map<String,Object> response=result;tx.run(()->{TaskWorker.permitted();store.insert("smslog",JsonSupport.map("tenant_id",tenant,"call_session_id",call.get("id"),"to_phone",call.get("phone"),"template_code","hangup_sms","content",text,"state",response.getOrDefault("state","sent"),"provider_message_id",response.getOrDefault("message_id",response.get("provider_message_id")),"provider_error",response.get("error"),"sent_at",Values.set("failed","disabled").contains(response.get("state"))?null:Store.now()));return null;});
    }
}
