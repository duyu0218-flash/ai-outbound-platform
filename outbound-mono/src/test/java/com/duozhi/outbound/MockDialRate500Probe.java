package com.duozhi.outbound;
import com.fasterxml.jackson.databind.*;
import java.io.*;import java.net.*;import java.net.http.*;import java.nio.file.*;import java.sql.*;import java.time.*;import java.util.*;import java.util.concurrent.*;import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;import org.springframework.core.env.*;import org.testcontainers.containers.*;import org.testcontainers.utility.DockerImageName;import static org.junit.jupiter.api.Assertions.*;
/** Explicit opt-in. Measures dial acknowledgements, never substitutes API acceptance or AI turns for CPS. */
class MockDialRate500Probe {
 final ObjectMapper json=new ObjectMapper().findAndRegisterModules();final Map<String,Object> result=new LinkedHashMap<>();
 final String run=UUID.randomUUID().toString().substring(0,8);final int seconds=Integer.getInteger("dial.seconds",5);final String SECRET="isolated-dialrate-jwt-secret-with-more-than-32-characters",HOOK="isolated-dialrate-hook-secret-with-more-than-32-characters";
 final int poolSize=Integer.getInteger("dial.pool",10000);
 final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).version(HttpClient.Version.HTTP_1_1).build();
 Path folder;String origin,token,jdbc,dbUser,dbPassword;Process application;
 @Test void dialPathActuallyStarts500NewMockCallsPerSecond()throws Exception{
  boolean queued="queue".equals(System.getProperty("dial.source","http"));assertTrue(Set.of("queue","http").contains(System.getProperty("dial.source","http")));
  folder=Path.of(System.getProperty("dial.output","verification/dial500-"+run));assertFalse(Files.exists(folder));Files.createDirectories(folder);Path jar=Path.of("target/outbound-mono.jar");
  result.put("scope",(queued?"Preloaded number pool with real durable scheduling":"Full HTTP creation and admission")+" / real MySQL+Redis / embedded mock telephony. No real SIP, RTP, audio, cloud models or public numbers.");result.put("target_cps",500);result.put("seconds",seconds);result.put("jar_sha256",HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar))));
  result.put("dial_source",queued?"Preloaded QUEUED pool; real enqueueDue/outbox/admission/mock gateway, no HTTP creation requests":"Fixed-rate full HTTP call creation/admission/mock gateway");
  var futures=new ArrayList<CompletableFuture<?>>();var codes=new ConcurrentHashMap<Integer,AtomicInteger>();var missed=new AtomicInteger();var errors=new ConcurrentHashMap<String,String>();
  try{
        try(var mysql=new MySQLContainer<>("mysql:8.4");var redis=new GenericContainer<>(DockerImageName.parse("redis:7")).withExposedPorts(6379)) {
            int bufferMiB=Integer.getInteger("capacity.mysql.bufferMiB",0);if(bufferMiB>0)mysql.withCommand("--innodb-buffer-pool-size="+(bufferMiB*1024L*1024L),"--innodb-flush-log-at-trx-commit=1","--sync-binlog=1","--transaction-isolation=REPEATABLE-READ");result.put("mysql_buffer_pool_override_mib",bufferMiB);
            mysql.start();redis.start();jdbc=mysql.getJdbcUrl();dbUser=mysql.getUsername();dbPassword=mysql.getPassword();
            result.put("mysql_container",mysql.getContainerId());result.put("redis_container",redis.getContainerId());result.put("mysql_jdbc",jdbc);result.put("profile_jfr",Boolean.getBoolean("capacity.jfr"));write();
            try {
                int port;try(var reservation=new ServerSocket(0,1,InetAddress.getLoopbackAddress())){port=reservation.getLocalPort();}
                origin="http://127.0.0.1:"+port;
                var command=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-Xms512m","-Xmx2g","-XX:+UseG1GC","-XX:MaxGCPauseMillis=20","-XX:NativeMemoryTracking=summary","-Xlog:gc*:file="+folder.resolve("gc.log")+":time,uptime,level,tags","-jar",jar.toString(),"--server.port="+port);
                if(Boolean.getBoolean("capacity.jfr"))command.command().add(1,"-XX:StartFlightRecording=filename="+folder.resolve("profile.jfr")+",settings=profile,dumponexit=true");
                command.redirectErrorStream(true).redirectOutput(folder.resolve("application.log").toFile());
                var env=command.environment();Map<String,String> cfg=new LinkedHashMap<>();
                cfg.put("ENV","dev");cfg.put("JWT_SECRET",SECRET);cfg.put("DEMO_USERS_ENABLED","false");cfg.put("MYSQL_JDBC_URL",jdbc);cfg.put("MYSQL_USER",dbUser);cfg.put("MYSQL_PASSWORD",dbPassword);
                cfg.put("REDIS_HOST",redis.getHost());cfg.put("REDIS_PORT",""+redis.getMappedPort(6379));cfg.put("REDIS_URL","redis://"+redis.getHost()+":"+redis.getMappedPort(6379));
                cfg.put("JAVA_WORKERS_ENABLED","true");cfg.put("JAVA_DISPATCH_ENABLED","true");cfg.put("CALLBACK_INBOX_ENABLED","true");cfg.put("OUTBOUND_PLATFORM_MAX_CONCURRENT","10000");cfg.put("MAX_CONCURRENT_CALLS","10000");
                cfg.put("TELEPHONY_PROVIDER","http");cfg.put("TELEPHONY_PROVIDER_ENDPOINT",origin);cfg.put("VOICE_GATEWAY_DRIVER","mock");cfg.put("VOICE_AI_PIPELINE","legacy");cfg.put("VOICE_COMMAND_SECRET",HOOK);cfg.put("VOICE_CALLBACK_BASE_URL",origin);cfg.put("TELEPHONY_WEBHOOK_BASE",origin);cfg.put("TELEPHONY_WEBHOOK_SECRET",HOOK);cfg.put("TELEPHONY_WEBHOOK_TOKEN",HOOK);
                cfg.put("RATE_LIMIT_RPS","2000");cfg.put("RATE_LIMIT_BURST","2000");cfg.put("METRICS_TOKEN",HOOK);cfg.put("LLM_PROVIDER","rule");cfg.put("JAVA_DIAL_BATCH_SIZE",System.getProperty("dial.batch","100"));cfg.put("JAVA_CALLBACK_BATCH_SIZE","100");cfg.put("VOICE_CALLBACK_BATCH_ENABLED","true");cfg.put("JAVA_DIAL_PROFILE","true");env.putAll(cfg);
                // Explicit higher call ceiling and API rate allowance; worker and DB pools remain at delivered defaults.
                result.put("configuration",Values.map("jvm_heap_max_gib",2,"db_pool_max",4,"voice_ledger_pool_max",4,"model_quota_window","disabled; rule AI","http_inflight_limit",100,"api_rate_limit_rps",2000,"worker_lanes",7,"task_executor_threads",4,"ai_lane_threads_max",4,"callback_consumer_threads",1,"external_model","not used","audio","not exercised","capacity_knobs",10000,"application_jvms",1));
                var actualConfiguration=new LinkedHashMap<>((Map<String,Object>)result.get("configuration"));actualConfiguration.put("dial_batch_size",cfg.get("JAVA_DIAL_BATCH_SIZE"));actualConfiguration.put("callback_batch_size",100);actualConfiguration.put("voice_callback_batch_enabled",true);result.put("configuration",actualConfiguration);
                env.put("JAVA_DIAL_WORKERS",System.getProperty("dial.workers","2"));actualConfiguration.put("dial_executor_threads",env.get("JAVA_DIAL_WORKERS"));actualConfiguration.put("general_task_executor_threads",4);
                env.put("VOICE_CALLBACK_CONCURRENCY",System.getProperty("dial.callback.concurrency","64"));actualConfiguration.put("voice_callback_delivery_concurrency",env.get("VOICE_CALLBACK_CONCURRENCY"));
                env.put("VOICE_CALLBACK_BATCH_SIZE",System.getProperty("dial.callback.batch","64"));actualConfiguration.put("voice_callback_batch_size",env.get("VOICE_CALLBACK_BATCH_SIZE"));actualConfiguration.put("voice_callback_protocol","v1 for <=16; additive signed v2 for 17..64");
                application=command.start();result.put("application_pid",application.pid());write();
                long deadline=System.currentTimeMillis()+60000;boolean healthy=false;while(System.currentTimeMillis()<deadline&&application.isAlive()){try{healthy=send("GET","/healthz",null,false).statusCode()==200;if(healthy)break;}catch(Exception starting){}Thread.sleep(200);}assertTrue(healthy,"fat jar startup: "+folder.resolve("application.log"));
                try(Connection c=connection()){seed(c);}
                var environment=new StandardEnvironment();environment.getPropertySources().addFirst(new MapPropertySource("capacity",Map.of("JWT_SECRET",SECRET)));
                token=new Crypto(json,new Config(environment)).token(Map.of("id",1,"username","capacity-admin","tenant_id",1,"role","admin","token_version",0));

                long phase=System.currentTimeMillis(),begin=System.nanoTime();result.put("phase_start_ms",phase);
                if(queued){
                  try(var c=connection()){
                    c.setAutoCommit(false);var defaults=new Store(null,json,null);
                    for(int i=0;i<poolSize;i++)fixture(c,defaults,"callsession",Map.of("tenant_id",1,"phone",phone(i),"mode","ai_only","status","queued","max_attempts",1));
                    result.put("db_stats_before",databaseStats());phase=System.currentTimeMillis();result.put("phase_start_ms",phase);c.commit();
                  }
                  result.put("fixtures","Isolated explicitly-consented synthetic contacts and QUEUED calls committed together; actual durable scheduler performs all admission and dial transitions. No public telephone calls.");result.put("queued_pool_size",poolSize);Thread.sleep((seconds+2)*1000L);
                }
                for(int i=0;!queued&&i<500*(seconds+2);i++){
                  final int index=i;long due=begin+i*2_000_000L;while(System.nanoTime()<due)java.util.concurrent.locks.LockSupport.parkNanos(due-System.nanoTime());
                  if(System.nanoTime()-due>1_000_000_000L)missed.incrementAndGet();
                  futures.add(http.sendAsync(request("POST","/api/v1/calls",Map.of("phone",phone(index),"mode","ai_only","max_attempts",1),false),HttpResponse.BodyHandlers.ofString()).handle((response,error)->{if(error!=null){codes.computeIfAbsent(0,k->new AtomicInteger()).incrementAndGet();errors.putIfAbsent("transport",error.toString());}else{codes.computeIfAbsent(response.statusCode(),k->new AtomicInteger()).incrementAndGet();if(response.statusCode()!=200)errors.putIfAbsent("http_"+response.statusCode(),response.body().substring(0,Math.min(1000,response.body().length())));}return null;}));
                }
                for(var future:futures)future.get(60,TimeUnit.SECONDS);result.put("planned_calls",queued?poolSize:500*(seconds+2));result.put("warmup_seconds",1);result.put("measured_seconds",seconds);result.put("submitted_calls",futures.size());result.put("generator_capacity_misses",missed.get());result.put("http_codes",codes);result.put("error_examples",errors);result.put("generator",queued?"Preloaded durable QUEUED pool committed at phase start; no calls inserted as DIALING or IN_AI":"Fixed 2 ms async HTTP schedule; every planned call is submitted even when server admission is full");
                var buckets=new TreeMap<Long,Long>();long within=0,accepted=0;try(var c=connection();var q=c.prepareStatement("SELECT FLOOR((UNIX_TIMESTAMP(e.created_at)*1000-?)/1000) AS bucket,COUNT(*) AS n FROM callevent e JOIN callsession c ON c.id=e.call_session_id WHERE c.tenant_id=1 AND e.event_type='dial_command_accepted' GROUP BY bucket ORDER BY bucket")){q.setLong(1,phase);try(var rows=q.executeQuery()){while(rows.next()){long bucket=rows.getLong(1),n=rows.getLong(2);buckets.put(bucket,n);accepted+=n;if(bucket>=1&&bucket<=seconds)within+=n;}}}
                result.put("dial_accepted_per_second",buckets);result.put("dial_accepted_within_window",within);result.put("dial_accepted_at_observation_end",accepted);result.put("actual_cps",within/(double)seconds);result.put("real_telephony_verified",false);
                result.put("db_stats_after",databaseStats());
                try(var c=connection();var q=c.createStatement();var rows=q.executeQuery("SELECT status,COUNT(*) FROM callsession WHERE tenant_id=1 GROUP BY status")){var states=new TreeMap<String,Long>();while(rows.next())states.put(rows.getString(1),rows.getLong(2));result.put("call_states_at_observation_end",states);}
                try(var c=DriverManager.getConnection(jdbc,"root",dbPassword);var q=c.createStatement();var rows=q.executeQuery("SHOW ENGINE INNODB STATUS")){if(rows.next())Files.writeString(folder.resolve("innodb-final.txt"),rows.getString(3));}catch(SQLException diagnostic){result.put("innodb_diagnostic_error",diagnostic.getMessage());}
                try(var c=connection();var q=c.createStatement();var rows=q.executeQuery("SELECT state,COUNT(*),MAX(TIMESTAMPDIFF(MICROSECOND,received_at,UTC_TIMESTAMP(3)))/1000000 FROM callbackinbox GROUP BY state")){var receipts=new ArrayList<Map<String,Object>>();while(rows.next())receipts.add(Map.of("state",rows.getString(1),"count",rows.getLong(2),"oldest_age_sec",rows.getDouble(3)));result.put("callback_receipts",receipts);}
                try(var c=connection();var q=c.createStatement();var rows=q.executeQuery("SELECT COUNT(*),COALESCE(UNIX_TIMESTAMP(UTC_TIMESTAMP(3))-MIN(created),0),COALESCE(MAX(failures),0) FROM voice_outbox")){if(rows.next())result.put("gateway_callback_backlog",Map.of("count",rows.getLong(1),"oldest_age_sec",rows.getDouble(2),"max_failures",rows.getLong(3)));}
                boolean passed=within>=500L*seconds&&missed.get()==0&&java.util.stream.LongStream.rangeClosed(1,seconds).allMatch(bucket->buckets.getOrDefault(bucket,0L)>=500);result.put("passed",passed);json.writerWithDefaultPrettyPrinter().writeValue(folder.resolve("result.json").toFile(),result);assertTrue(passed,"500 CPS NOT achieved; evidence "+folder.resolve("result.json"));
            }finally{if(application!=null){application.destroy();if(!application.waitFor(10,TimeUnit.SECONDS))application.destroyForcibly();}}
        }
  }catch(Throwable error){result.put("probe_error",error.toString());throw error;}finally{json.writerWithDefaultPrettyPrinter().writeValue(folder.resolve("result.json").toFile(),result);}
 }
    void write()throws IOException{json.writerWithDefaultPrettyPrinter().writeValue(folder.resolve("result.json").toFile(),result);}
    Map<String,Long> databaseStats()throws SQLException{var result=new LinkedHashMap<String,Long>();try(var c=connection();var q=c.createStatement();var rows=q.executeQuery("SHOW GLOBAL STATUS WHERE Variable_name IN ('Questions','Com_insert','Com_update','Com_select','Com_commit','Com_rollback','Com_stmt_execute','Innodb_deadlocks','Innodb_row_lock_time')")){while(rows.next())result.put(rows.getString(1),rows.getLong(2));}return result;}
    String phone(int i){return "1555"+String.format(Locale.ROOT,"%07d",i);}
    Connection connection()throws SQLException{return DriverManager.getConnection(jdbc,dbUser,dbPassword);}
    void seed(Connection c)throws Exception{
        Store sourceDefaults=new Store(null,json,null);
        fixture(c,sourceDefaults,"tenant",Map.of("id",1,"code","capacity-"+run,"name","Synthetic capacity fixture"));
        fixture(c,sourceDefaults,"user",Map.of("id",1,"tenant_id",1,"username","capacity-admin","password_hash","not-a-login-credential","full_name","Synthetic capacity admin","role","admin"));
        fixture(c,sourceDefaults,"adminsetting",Map.of("tenant_id",1,"section","compliance","data_json","{\"allowed_start_hour\":0,\"allowed_end_hour\":0}"));
        for(int i=0;i<Math.max(poolSize,500*(seconds+2));i++)fixture(c,sourceDefaults,"contact",Map.of("tenant_id",1,"phone",phone(i),"name","Synthetic "+i,"consent_state","consented"));
        result.put("fixtures","Isolated explicitly-consented synthetic contacts; full HTTP to embedded mock dial path. No public telephone calls.");
    }
    void fixture(Connection c,Store defaults,String table,Map<String,Object> input)throws Exception{
        var values=defaults.defaults(table);values.putAll(input);if(values.get("id")==null)values.remove("id");String physical=table.equals("user")?"sys_user":table;
        String columns=String.join(",",values.keySet().stream().map(k->"`"+k+"`").toList());String slots=String.join(",",Collections.nCopies(values.size(),"?"));
        try(var s=c.prepareStatement("INSERT INTO `"+physical+"` ("+columns+") VALUES ("+slots+")")){int index=1;for(var entry:values.entrySet()){Object value=defaults.encode(table,entry.getKey(),entry.getValue());s.setObject(index++,value instanceof UUID?value.toString():value);}s.executeUpdate();}
    }
    HttpResponse<String> send(String method,String path,Object data,boolean webhook)throws Exception{return http.send(request(method,path,data,webhook),HttpResponse.BodyHandlers.ofString());}
    HttpRequest request(String method,String path,Object data,boolean webhook)throws Exception{
        String body=data==null?"":json.writeValueAsString(data);var request=HttpRequest.newBuilder(URI.create(origin+path)).timeout(Duration.ofSeconds(10));
        if(token!=null&&!webhook)request.header("Authorization","Bearer "+token);
        if(data!=null)request.header("Content-Type","application/json");
        if(webhook){String stamp=""+Instant.now().getEpochSecond();request.header("X-Webhook-Token",HOOK).header("X-Webhook-Timestamp",stamp).header("X-Webhook-Signature",JsonSupport.hmac(HOOK,(stamp+"."+body).getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
        request.method(method,data==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body));return request.build();
    }

}
