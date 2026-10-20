package com.duozhi.outbound;

import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.*;
import org.testcontainers.containers.*;
import org.testcontainers.utility.DockerImageName;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit opt-in probe: -Dtest=SingleHost500CapacityProbe test. Never part of default verify. */
class SingleHost500CapacityProbe {
    final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    final Map<String,Object> result = new LinkedHashMap<>();
    final List<Map<String,Object>> samples = new CopyOnWriteArrayList<>();
    final ConcurrentMap<Integer,Long> scheduled = new ConcurrentHashMap<>(), delivered = new ConcurrentHashMap<>();
    final ConcurrentMap<Integer,Integer> waiting = new ConcurrentHashMap<>();
    final ConcurrentMap<Integer,Long> requested = new ConcurrentHashMap<>();
    final List<Double> httpLatency = new CopyOnWriteArrayList<>(), replyLatency = new CopyOnWriteArrayList<>(), observerQueryMs = new CopyOnWriteArrayList<>();
    final List<Thread> captures = new CopyOnWriteArrayList<>();
    final ConcurrentMap<String,AtomicInteger> codes = new ConcurrentHashMap<>();
    final AtomicInteger generatorMisses = new AtomicInteger(), waitingMisses = new AtomicInteger(), inFlight = new AtomicInteger();
    final ExecutorService load = Executors.newFixedThreadPool(32);
    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).version(HttpClient.Version.HTTP_1_1).build();
    Path folder; String origin, token; Process application; volatile boolean observing; Thread observer;
    static final String SECRET = "isolated-capacity-fixture-jwt-secret-20261020-0000000000";
    static final String HOOK = "isolated-capacity-webhook-fixture-20261020-0000000000000";
    final String run = UUID.randomUUID().toString().substring(0,8);
    final int target = Integer.getInteger("capacity.calls",500), rate = Integer.getInteger("capacity.rate",50), seconds = Integer.getInteger("capacity.seconds",120);
    long phaseStart; int admissionCreated; String jdbc, dbUser, dbPassword;
    final List<String> calls = new ArrayList<>();

    @Test void singleFatJarSustains500InFlightAndDurableDialogue() throws Exception {
        assertEquals(500,target,"This qualification is specifically for 500 calls");
        folder=Path.of(System.getProperty("capacity.output","verification/capacity500-"+LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))));
        assertFalse(Files.exists(folder),"Preserve prior evidence; select a fresh output directory"); Files.createDirectories(folder);
        Path jar=Path.of("target/outbound-mono.jar");assertTrue(Files.isRegularFile(jar));
        result.put("run_id",run);result.put("jar_sha256",HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar))));
        result.put("scope","single fat jar / real MySQL and Redis / synthetic embedded voice, rule AI; no PCM, external model, PBX or real recording");
        result.put("target_calls",target);result.put("target_turns_per_second",rate);result.put("hold_seconds",seconds);
        result.put("thresholds",Map.of("all_500_in_ai",true,"http_success_fraction_min",0.999,"scheduled_reply_fraction_min",0.99,"reply_p95_ms_max",1500,"reply_p99_ms_max",3000,"pending_after_drain_max",0));
        result.put("threshold_note","Explicit simulation acceptance assumptions; 500 calls each get a slot every "+(500.0/rate)+" seconds. No next utterance before the previous durable reply. Missed slots remain failures.");
        try(var mysql=new MySQLContainer<>("mysql:8.4");var redis=new GenericContainer<>(DockerImageName.parse("redis:7")).withExposedPorts(6379)) {
            int bufferMiB=Integer.getInteger("capacity.mysql.bufferMiB",0);if(bufferMiB>0)mysql.withCommand("--innodb-buffer-pool-size="+(bufferMiB*1024L*1024L),"--innodb-flush-log-at-trx-commit=1","--sync-binlog=1","--transaction-isolation=REPEATABLE-READ");result.put("mysql_buffer_pool_override_mib",bufferMiB);
            mysql.start();redis.start();jdbc=mysql.getJdbcUrl();dbUser=mysql.getUsername();dbPassword=mysql.getPassword();
            result.put("mysql_container",mysql.getContainerId());result.put("redis_container",redis.getContainerId());result.put("mysql_jdbc",jdbc);result.put("profile_jfr",Boolean.getBoolean("capacity.jfr"));write();
            try {
                int port;try(var reservation=new ServerSocket(0,1,InetAddress.getLoopbackAddress())){port=reservation.getLocalPort();}
                origin="http://127.0.0.1:"+port;
                var command=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-Xms512m","-Xmx2g","-XX:+UseG1GC","-XX:MaxGCPauseMillis=20","-XX:NativeMemoryTracking=summary","-Xlog:gc*:file="+folder.resolve("gc.log")+":time,uptime,level,tags","-jar",jar.toString(),"--server.port="+port);
                command.redirectErrorStream(true).redirectOutput(folder.resolve("application.log").toFile());
                var env=command.environment();Map<String,String> cfg=new LinkedHashMap<>();
                cfg.put("ENV","dev");cfg.put("JWT_SECRET",SECRET);cfg.put("DEMO_USERS_ENABLED","false");cfg.put("MYSQL_JDBC_URL",jdbc);cfg.put("MYSQL_USER",dbUser);cfg.put("MYSQL_PASSWORD",dbPassword);
                cfg.put("REDIS_HOST",redis.getHost());cfg.put("REDIS_PORT",""+redis.getMappedPort(6379));cfg.put("REDIS_URL","redis://"+redis.getHost()+":"+redis.getMappedPort(6379));
                cfg.put("JAVA_WORKERS_ENABLED","true");cfg.put("JAVA_DISPATCH_ENABLED","true");cfg.put("CALLBACK_INBOX_ENABLED","true");cfg.put("OUTBOUND_PLATFORM_MAX_CONCURRENT","500");cfg.put("MAX_CONCURRENT_CALLS","500");
                cfg.put("TELEPHONY_PROVIDER","http");cfg.put("TELEPHONY_PROVIDER_ENDPOINT",origin);cfg.put("VOICE_GATEWAY_DRIVER","mock");cfg.put("VOICE_AI_PIPELINE","legacy");cfg.put("VOICE_COMMAND_SECRET",HOOK);cfg.put("VOICE_CALLBACK_BASE_URL",origin);cfg.put("TELEPHONY_WEBHOOK_BASE",origin);cfg.put("TELEPHONY_WEBHOOK_SECRET",HOOK);cfg.put("TELEPHONY_WEBHOOK_TOKEN",HOOK);
                cfg.put("METRICS_TOKEN",HOOK);cfg.put("LLM_PROVIDER","rule");env.putAll(cfg);
                // Capacity knobs only; request/queue/DB/worker limits stay at the delivered defaults.
                result.put("configuration",Values.map("jvm_heap_max_gib",2,"db_pool_max",4,"voice_ledger_pool_max",4,"model_quota_window","disabled; rule AI","http_inflight_limit",100,"api_rate_limit_rps",100,"worker_lanes",7,"task_executor_threads",4,"ai_lane_threads_max",4,"callback_consumer_threads",1,"external_model","not used","audio","not exercised","capacity_knobs",500,"application_jvms",1));
                application=command.start();result.put("application_pid",application.pid());write();
                long deadline=System.currentTimeMillis()+60000;boolean healthy=false;while(System.currentTimeMillis()<deadline&&application.isAlive()){try{healthy=send("GET","/healthz",null,false).statusCode()==200;if(healthy)break;}catch(Exception starting){}Thread.sleep(200);}assertTrue(healthy,"fat jar startup: "+folder.resolve("application.log"));
                try(Connection c=connection()){seed(c);}
                var environment=new StandardEnvironment();environment.getPropertySources().addFirst(new MapPropertySource("capacity",Map.of("JWT_SECRET",SECRET)));
                token=new Crypto(json,new Config(environment)).token(Map.of("id",1,"username","capacity-admin","tenant_id",1,"role","admin","token_version",0));
                startObserver();Thread.sleep(1000);
                List<Double> admissionMs=new ArrayList<>();Map<String,Integer> admissionCodes=new TreeMap<>();
                long ramp=System.nanoTime();for(int i=0;i<500;i++){
                    parkUntil(ramp+i*50_000_000L);long started=System.nanoTime();
                    var response=send("POST","/api/v1/calls",Map.of("phone",phone(i),"mode","ai_only","max_attempts",1),false);
                    admissionCodes.merge(""+response.statusCode(),1,Integer::sum);if(response.statusCode()!=200){Files.writeString(folder.resolve("admission-errors.jsonl"),json.writeValueAsString(Map.of("index",i,"status",response.statusCode(),"body",response.body()))+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);if(i==0)throw new IllegalStateException("Preflight call creation rejected: "+response.statusCode()+" "+response.body());}admissionMs.add((System.nanoTime()-started)/1e6);
                    if(response.statusCode()==200){calls.add(json.readTree(response.body()).get("id").asText());admissionCreated++;}
                    if(i%100==99)System.out.println("capacity ramp created="+admissionCreated+" / "+(i+1));
                }
                result.put("admission",Map.of("created",admissionCreated,"http_status",admissionCodes,"p95_ms",quantile(admissionMs,.95),"elapsed_seconds",(System.nanoTime()-ramp)/1e9));
                deadline=System.currentTimeMillis()+120000;while(System.currentTimeMillis()<deadline&&application.isAlive()){if(count("SELECT COUNT(*) FROM callsession WHERE status='IN_AI'")==500)break;Thread.sleep(500);}
                long inAi=count("SELECT COUNT(*) FROM callsession WHERE status='IN_AI'");result.put("in_ai_at_load_start",inAi);
                var overflow=send("POST","/api/v1/calls",Map.of("phone",phone(500),"mode","ai_only","max_attempts",1),false);JsonNode over=json.readTree(overflow.body());
                result.put("call_501",Map.of("http",overflow.statusCode(),"status",over.path("status").asText(),"active_count",count("SELECT COUNT(*) FROM callsession WHERE status NOT IN ('CREATED','QUEUED','COMPLETED','FAILED','BUSY','NO_ANSWER','VOICEMAIL')")));
                System.out.println("capacity steady start: in_ai="+inAi+" created="+calls.size());
                assertEquals(500,inAi,"Actual admission failed to reach 500 IN_AI; not a steady-state sample");
                diagnostics("before-load");
                if(Boolean.getBoolean("capacity.jfr"))capture("JFR.start name=capacity settings=profile filename="+folder.resolve("application.jfr").toAbsolutePath(),"jfr-start.txt");
                phaseStart=System.currentTimeMillis();result.put("phase_start_ms",phaseStart);write();long begin=System.nanoTime();int planned=rate*seconds;
                for(int i=0;i<planned;i++){
                    long due=begin+(long)i*1_000_000_000L/rate;parkUntil(due);long wall=phaseStart+(long)i*1000/rate;scheduled.put(i,wall);
                    if(i==rate*15||i==rate*60||i==rate*(seconds-10)){int phase=i/rate;Thread capture=new Thread(()->{capture("Thread.print","thread-dump-"+phase+"s.txt");diagnostics("load-"+phase+"s");},"capacity-thread-capture");capture.setDaemon(true);captures.add(capture);capture.start();}
                    if(System.nanoTime()-due>1_000_000_000L||inFlight.get()>=100){generatorMisses.incrementAndGet();continue;}
                    int slot=i%500;if(slot>=calls.size()){generatorMisses.incrementAndGet();continue;}
                    if(waiting.putIfAbsent(slot,i)!=null){waitingMisses.incrementAndGet();continue;}
                    requested.put(i,wall);inFlight.incrementAndGet();final int index=i;
                    load.submit(()->{long start=System.nanoTime();try{
                        var response=send("POST","/api/v1/webhooks/telephony/speech",Map.of("call_id",calls.get(slot),"event_id",run+"-turn-"+index,"transcript",utterance(index),"attempt",1,"is_final",true,"confidence",1.0),true);
                        codes.computeIfAbsent(""+response.statusCode(),k->new AtomicInteger()).incrementAndGet();httpLatency.add((System.nanoTime()-start)/1e6);
                        if(response.statusCode()/100!=2){waiting.remove(slot,index);synchronized(this){Files.writeString(folder.resolve("request-errors.jsonl"),json.writeValueAsString(Map.of("index",index,"at_ms",System.currentTimeMillis(),"status",response.statusCode(),"body",response.body()))+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}}
                    }catch(Exception error){codes.computeIfAbsent(error.getClass().getSimpleName(),k->new AtomicInteger()).incrementAndGet();waiting.remove(slot,index);}finally{inFlight.decrementAndGet();}});
                }
                long steadyEnd=phaseStart+seconds*1000L;result.put("phase_end_ms",steadyEnd);result.put("delivered_within_steady_window",delivered.values().stream().filter(t->t<=steadyEnd).count());
                deadline=System.currentTimeMillis()+30000;while(System.currentTimeMillis()<deadline&&(!waiting.isEmpty()||inFlight.get()>0))Thread.sleep(200);
                result.put("planned_turns",planned);result.put("sent_turns",requested.size());result.put("http_status",codes);result.put("generator_missed_slots",generatorMisses.get());result.put("call_waiting_missed_slots",waitingMisses.get());
                result.put("durable_replies",delivered.size());result.put("pending_calls_after_drain",waiting.size());result.put("http_p95_ms",quantile(httpLatency,.95));result.put("http_p99_ms",quantile(httpLatency,.99));result.put("reply_p95_ms",quantile(replyLatency,.95));result.put("reply_p99_ms",quantile(replyLatency,.99));
                result.put("steady_valid_reply_rps",((Number)result.get("delivered_within_steady_window")).doubleValue()/seconds);
                result.put("max_in_flight_calls",samples.stream().mapToLong(s->((Number)s.get("active_calls")).longValue()).max().orElse(0));
                result.put("peak_pending_tasks",samples.stream().mapToLong(s->((Number)s.get("pending_tasks")).longValue()).max().orElse(0));
                result.put("peak_pending_callbacks",samples.stream().mapToLong(s->((Number)s.get("pending_callbacks")).longValue()).max().orElse(0));
                long successes=codes.entrySet().stream().filter(e->e.getKey().matches("2[0-9][0-9]")).mapToLong(e->e.getValue().get()).sum();
                var steadySamples=samples.stream().filter(s->((Number)s.get("at_ms")).longValue()>=phaseStart&&((Number)s.get("at_ms")).longValue()<=steadyEnd).toList();
                boolean held500=!steadySamples.isEmpty()&&steadySamples.stream().allMatch(s->((Number)s.get("active_calls")).longValue()==500&&((Number)s.get("in_ai")).longValue()==500);result.put("held_500_in_steady_samples",held500);result.put("steady_sample_count",steadySamples.size());
                result.put("observer_query_p95_ms",quantile(observerQueryMs,.95));result.put("observer_query_p99_ms",quantile(observerQueryMs,.99));
                boolean passed=inAi==500&&held500&&successes>=planned*.999&&delivered.size()>=planned*.99&&quantile(replyLatency,.95)!=null&&quantile(replyLatency,.95)<=1500&&quantile(replyLatency,.99)<=3000&&waiting.isEmpty()&&application.isAlive();
                result.put("simulation_slo_passed",passed);result.put("real_500_audio_capacity_verified",false);result.put("production_capacity_verified",false);
                for(Thread capture:captures)capture.join(15000);
                if(Boolean.getBoolean("capacity.jfr"))capture("JFR.stop name=capacity","jfr-stop.txt");
                diagnostics("after-load");capture("Thread.print","thread-dump-after.txt");
                capture("GC.heap_info","heap-info.txt");capture("VM.native_memory summary","native-memory.txt");
                result.put("final_database",snapshot());result.put("application_alive",application.isAlive());write();
                // Expected failure is retained: never weaken this assertion to make a capacity report green.
                assertTrue(passed,"500-call simulation missed its stated SLO; complete evidence: "+folder.resolve("result.json"));
            }catch(Throwable error){result.put("probe_error",error.toString());write();throw error;}
            finally{observing=false;if(observer!=null)observer.join(5000);load.shutdownNow();if(application!=null){application.destroy();if(!application.waitFor(10,TimeUnit.SECONDS))application.destroyForcibly();}write();}
        }
    }
    String phone(int i){return "1555"+String.format(Locale.ROOT,"%07d",i);}
    String utterance(int i){return "请继续介绍服务 CAPACITY_"+run+"_"+i;}
    Connection connection()throws SQLException{return DriverManager.getConnection(jdbc,dbUser,dbPassword);}
    long count(String sql)throws Exception{try(var c=connection();var q=c.createStatement();var r=q.executeQuery(sql)){r.next();return r.getLong(1);}}
    void seed(Connection c)throws Exception{
        Store sourceDefaults=new Store(null,json,null);
        fixture(c,sourceDefaults,"tenant",Map.of("id",1,"code","capacity-"+run,"name","Synthetic capacity fixture"));
        fixture(c,sourceDefaults,"user",Map.of("id",1,"tenant_id",1,"username","capacity-admin","password_hash","not-a-login-credential","full_name","Synthetic capacity admin","role","admin"));
        fixture(c,sourceDefaults,"adminsetting",Map.of("tenant_id",1,"section","compliance","data_json","{\"allowed_start_hour\":0,\"allowed_end_hour\":0}"));
        for(int i=0;i<=500;i++)fixture(c,sourceDefaults,"contact",Map.of("tenant_id",1,"phone",phone(i),"name","Synthetic "+i,"consent_state","consented"));
        result.put("fixtures","One isolated tenant/admin and 501 explicitly-consented synthetic contacts; calls are all created through the real HTTP dial/admission path, never inserted as IN_AI rows.");
    }
    void fixture(Connection c,Store defaults,String table,Map<String,Object> input)throws Exception{
        var values=defaults.defaults(table);values.putAll(input);if(values.get("id")==null)values.remove("id");String physical=table.equals("user")?"sys_user":table;
        String columns=String.join(",",values.keySet().stream().map(k->"`"+k+"`").toList());String slots=String.join(",",Collections.nCopies(values.size(),"?"));
        try(var s=c.prepareStatement("INSERT INTO `"+physical+"` ("+columns+") VALUES ("+slots+")")){int index=1;for(var entry:values.entrySet()){Object value=defaults.encode(table,entry.getKey(),entry.getValue());s.setObject(index++,value);}s.executeUpdate();}
    }
    HttpResponse<String> send(String method,String path,Object data,boolean webhook)throws Exception{
        String body=data==null?"":json.writeValueAsString(data);var request=HttpRequest.newBuilder(URI.create(origin+path)).timeout(Duration.ofSeconds(10));
        if(token!=null&&!webhook)request.header("Authorization","Bearer "+token);
        if(data!=null)request.header("Content-Type","application/json");
        if(webhook){String stamp=""+Instant.now().getEpochSecond();request.header("X-Webhook-Token",HOOK).header("X-Webhook-Timestamp",stamp).header("X-Webhook-Signature",JsonSupport.hmac(HOOK,(stamp+"."+body).getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
        request.method(method,data==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body));return http.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    static void parkUntil(long due){long left;while((left=due-System.nanoTime())>0)LockSupport.parkNanos(left);}
    static Double quantile(List<Double> values,double p){if(values.isEmpty())return null;var sorted=new ArrayList<>(values);Collections.sort(sorted);return sorted.get(Math.max(0,(int)Math.ceil(sorted.size()*p)-1));}
    Map<String,Object> snapshot()throws Exception{
        var s=new LinkedHashMap<String,Object>();s.put("at_ms",System.currentTimeMillis());
        s.put("active_calls",count("SELECT COUNT(*) FROM callsession WHERE status NOT IN ('CREATED','QUEUED','COMPLETED','FAILED','BUSY','NO_ANSWER','VOICEMAIL')"));
        s.put("in_ai",count("SELECT COUNT(*) FROM callsession WHERE status='IN_AI'"));s.put("pending_tasks",count("SELECT COUNT(*) FROM taskoutbox WHERE state IN ('PENDING','FAILED','PROCESSING') AND available_at<=UTC_TIMESTAMP(3)"));s.put("pending_callbacks",count("SELECT COUNT(*) FROM callbackinbox WHERE state<>'done'"));s.put("dead_tasks",count("SELECT COUNT(*) FROM taskoutbox WHERE state='DEAD'"));
        s.put("voice_pending_callbacks",count("SELECT COUNT(*) FROM voice_outbox"));
        s.put("durable_replies",delivered.size());application.info().totalCpuDuration().ifPresent(value->s.put("application_cpu_seconds",value.toMillis()/1000.0));
        try{var ps=new ProcessBuilder("ps","-p",""+application.pid(),"-o","%cpu=,rss=").start();String[] usage=new String(ps.getInputStream().readAllBytes()).trim().split("\\s+");if(ps.waitFor(2,TimeUnit.SECONDS)&&usage.length==2){s.put("application_cpu_percent",Double.parseDouble(usage[0]));s.put("application_rss_kib",Long.parseLong(usage[1]));}}catch(Exception unavailable){s.put("os_resource_sample_error",unavailable.getClass().getSimpleName());}
        try{String text=sendMetrics();for(String name:List.of("ai_outbound_jvm_heap_used_bytes","ai_outbound_jvm_threads","ai_outbound_java_gc_seconds_total")){var m=java.util.regex.Pattern.compile("(?m)^"+name+" ([0-9.Ee+\\-]+)").matcher(text);if(m.find())s.put(name,Double.parseDouble(m.group(1)));}}catch(Exception unavailable){s.put("runtime_metrics_error",unavailable.getClass().getSimpleName());}
        return s;
    }
    String sendMetrics()throws Exception{return http.send(HttpRequest.newBuilder(URI.create(origin+"/metrics/runtime")).timeout(Duration.ofSeconds(3)).header("Authorization","Bearer "+HOOK).build(),HttpResponse.BodyHandlers.ofString()).body();}
    void startObserver(){observing=true;observer=new Thread(()->{long last=0;while(observing){try{
        long queryStarted=System.nanoTime();try(var c=connection();var q=c.prepareStatement("SELECT c.transcript FROM speechturn c JOIN speechturn a ON a.call_session_id=c.call_session_id AND a.attempt=c.attempt AND a.turn_index=c.turn_index AND a.speaker_role='ai' WHERE c.speaker_role='customer' AND c.transcript LIKE ?")){q.setString(1,"%CAPACITY_"+run+"_%");try(var r=q.executeQuery()){while(r.next()){String value=r.getString(1);int index=Integer.parseInt(value.substring(value.lastIndexOf('_')+1));Long due=requested.get(index);if(due!=null&&delivered.putIfAbsent(index,System.currentTimeMillis())==null){replyLatency.add((double)(System.currentTimeMillis()-due));waiting.remove(index%500,index);}}}}
        observerQueryMs.add((System.nanoTime()-queryStarted)/1e6);
        if(System.currentTimeMillis()-last>=1000){var sample=snapshot();samples.add(sample);Files.writeString(folder.resolve("samples.jsonl"),json.writeValueAsString(sample)+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);last=System.currentTimeMillis();}
        Thread.sleep(200);
    }catch(Exception error){try{Files.writeString(folder.resolve("observer-errors.log"),error.toString()+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}catch(Exception ignored){}try{Thread.sleep(500);}catch(InterruptedException done){return;}}}},"capacity-observer");observer.setDaemon(true);observer.start();}
    void diagnostics(String phase){
        var data=new LinkedHashMap<String,Object>();data.put("at_ms",System.currentTimeMillis());
        try(var c=DriverManager.getConnection(jdbc,"root",dbPassword)){
            var statements=new LinkedHashMap<String,String>();
            statements.put("durability","SELECT VERSION() AS version,@@GLOBAL.innodb_flush_log_at_trx_commit AS flush_at_commit,@@GLOBAL.sync_binlog AS sync_binlog,@@SESSION.transaction_isolation AS isolation_level");
            statements.put("variables","SHOW VARIABLES WHERE Variable_name IN ('innodb_buffer_pool_size','innodb_flush_log_at_trx_commit','sync_binlog','transaction_isolation','innodb_io_capacity','max_connections','innodb_deadlock_detect')");
            statements.put("task_indexes","SHOW INDEX FROM taskoutbox");
            statements.put("explain_abandoned","EXPLAIN SELECT * FROM taskoutbox FORCE INDEX (ix_taskoutbox_ready_scope) WHERE tenant_id=1 AND task_type IN ('ai_turn') AND state='PROCESSING' AND locked_at<=UTC_TIMESTAMP(3)-INTERVAL 30 SECOND AND attempts>=max_attempts LIMIT 10");
            statements.put("explain_claim","EXPLAIN SELECT * FROM taskoutbox FORCE INDEX (ix_taskoutbox_ready_scope) WHERE tenant_id=1 AND task_type IN ('ai_turn') AND available_at<=UTC_TIMESTAMP(3) AND attempts<max_attempts AND (state IN ('PENDING','FAILED') OR (state='PROCESSING' AND locked_at<=UTC_TIMESTAMP(3)-INTERVAL 30 SECOND)) AND NOT EXISTS (SELECT 1 FROM taskoutbox older WHERE taskoutbox.task_type<>'after_playback' AND older.tenant_id=taskoutbox.tenant_id AND older.aggregate_id=taskoutbox.aggregate_id AND (older.task_type=taskoutbox.task_type OR (older.task_type IN ('recording_ingest','recording_delete') AND taskoutbox.task_type IN ('recording_ingest','recording_delete'))) AND older.state IN ('PENDING','FAILED','PROCESSING') AND (older.created_at<taskoutbox.created_at OR (older.created_at=taskoutbox.created_at AND older.id<taskoutbox.id))) ORDER BY available_at,id LIMIT 1");
            statements.put("status","SHOW GLOBAL STATUS WHERE Variable_name IN ('Threads_running','Threads_connected','Innodb_deadlocks','Innodb_row_lock_current_waits','Innodb_row_lock_time','Innodb_row_lock_waits','Innodb_buffer_pool_wait_free','Innodb_buffer_pool_reads','Innodb_buffer_pool_read_requests','Com_commit','Com_rollback','Slow_queries','Questions')");
            statements.put("digests","SELECT LEFT(DIGEST_TEXT,2000) AS sql_digest,COUNT_STAR,SUM_TIMER_WAIT,AVG_TIMER_WAIT,MAX_TIMER_WAIT,SUM_LOCK_TIME,SUM_ROWS_EXAMINED,SUM_ROWS_SENT,SUM_ERRORS FROM performance_schema.events_statements_summary_by_digest WHERE SCHEMA_NAME=DATABASE() ORDER BY SUM_TIMER_WAIT DESC LIMIT 25");
            statements.put("deadlock_counter","SELECT NAME,COUNT,STATUS FROM information_schema.innodb_metrics WHERE NAME='lock_deadlocks'");
            statements.put("lock_waits","SELECT * FROM performance_schema.data_lock_waits LIMIT 100");
            statements.put("current_statements","SELECT THREAD_ID,EVENT_NAME,LEFT(SQL_TEXT,2000) AS sql_text,TIMER_WAIT,LOCK_TIME,ROWS_EXAMINED FROM performance_schema.events_statements_current WHERE SQL_TEXT IS NOT NULL LIMIT 40");
            statements.put("task_queues","SELECT task_type,state,COUNT(*) AS count,MIN(available_at) AS oldest FROM taskoutbox GROUP BY task_type,state");
            statements.put("voice_ledger","SELECT 'voice_attempts' AS name,COUNT(*) AS count FROM voice_attempts UNION ALL SELECT 'voice_outbox',COUNT(*) FROM voice_outbox UNION ALL SELECT 'voice_flags',COUNT(*) FROM voice_flags UNION ALL SELECT 'voice_model_usage',COUNT(*) FROM voice_model_usage");
            if(phase.equals("after-load")){
                statements.put("turn_timings","SELECT c.transcript,c.created_at AS customer_created,a.created_at AS reply_created,c.attempt,c.turn_index,TIMESTAMPDIFF(MICROSECOND,c.created_at,a.created_at)/1000.0 AS persisted_pair_ms,t.created_at AS task_created,t.updated_at AS task_updated,t.state AS task_state,t.attempts AS task_attempts FROM speechturn c LEFT JOIN speechturn a ON a.call_session_id=c.call_session_id AND a.attempt=c.attempt AND a.turn_index=c.turn_index AND a.speaker_role='ai' LEFT JOIN taskoutbox t ON t.idempotency_key=CONCAT('ai:',c.call_session_id,':speech:',c.id) WHERE c.speaker_role='customer' AND c.transcript LIKE '%CAPACITY_"+run+"_%'");
                statements.put("ai_stage_durations","SELECT stage,provider,duration_ms,success,created_at FROM callmetric WHERE stage IN ('ai.turn','tts.dispatch') AND created_at>=FROM_UNIXTIME("+(phaseStart/1000.0)+") ORDER BY created_at");
            }
            for(var entry:statements.entrySet())try(var q=c.createStatement()){q.setQueryTimeout(5);try(var rows=q.executeQuery(entry.getValue())){var values=new ArrayList<Map<String,Object>>();int n=rows.getMetaData().getColumnCount();while(rows.next()){var value=new LinkedHashMap<String,Object>();for(int i=1;i<=n;i++)value.put(rows.getMetaData().getColumnLabel(i),rows.getString(i));values.add(value);}data.put(entry.getKey(),values);}}catch(Exception error){data.put(entry.getKey()+"_error",error.toString());}
            try(var q=c.createStatement();var rows=q.executeQuery("SHOW ENGINE INNODB STATUS")){if(rows.next())Files.writeString(folder.resolve("innodb-"+phase+".txt"),rows.getString(3));}
        }catch(Exception error){data.put("diagnostic_error",error.toString());}
        try{Files.writeString(folder.resolve("database-"+phase+".json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(data)+"\n");}catch(IOException ignored){}
    }
    void capture(String operation,String output){try{var args=new ArrayList<>(List.of(Path.of(System.getProperty("java.home"),"bin/jcmd").toString(),""+application.pid()));args.addAll(Arrays.asList(operation.split(" ")));var p=new ProcessBuilder(args).redirectErrorStream(true).redirectOutput(folder.resolve(output).toFile()).start();if(!p.waitFor(10,TimeUnit.SECONDS))p.destroyForcibly();}catch(Exception error){result.put("capture_"+output,error.toString());}}
    void write()throws IOException{
        result.put("samples",samples.size());Files.writeString(folder.resolve("result.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(result)+"\n");
        var rows=new StringBuilder("index,scheduled_ms,requested,delivered_ms,scheduled_reply_ms\n");
        for(var index:new TreeSet<>(scheduled.keySet())){Long due=scheduled.get(index),done=delivered.get(index);rows.append(index).append(',').append(due).append(',').append(requested.containsKey(index)).append(',').append(done==null?"":done).append(',').append(done==null?"":done-due).append('\n');}
        Files.writeString(folder.resolve("turns.csv"),rows.toString());
    }
}
