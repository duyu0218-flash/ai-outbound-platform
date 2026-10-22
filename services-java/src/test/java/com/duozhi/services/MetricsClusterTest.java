package com.duozhi.services;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MetricsClusterTest {
    @TempDir
    Path directory;
    @Test
    void actualWorkerMetricsSumCountsTakeMaxAndOmitUnavailableWorkers() throws Exception {
        String token="independent-metrics-cluster-token-00000000000";
        List<HttpServer> servers=new ArrayList<>();List<Map<String,Object>> roster=new ArrayList<>();
        AtomicBoolean healthy=new AtomicBoolean(true);
        try {
            for(int i=1;i<=2;i++) {
                final int index=i;
                HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);servers.add(server);
                server.createContext("/internal/state",exchange->{
                    assertEquals("Bearer "+token,exchange.getRequestHeaders().getFirst("Authorization"));
                    byte[] body=Support.json(Map.of("ready",healthy.get(),"worker_id","worker-"+index,"capacity",1,"sessions",Map.of(),"epoch","epoch-"+index,"metrics",Map.of("pcm_input_frames_total",index,"tts_first_audio_max_seconds",index*.1)));
                    exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
                });server.start();int port=server.getAddress().getPort();
                roster.add(Map.of("id","worker-"+i,"endpoint","http://127.0.0.1:"+port,"ws_base","ws://127.0.0.1:"+port+"/v1/pipecat/media","capacity",1));
            }
            Settings settings=new Settings(Map.of("MEDIA_RPC_TOKEN",token,"MEDIA_WORKERS_JSON",new String(Support.json(roster),StandardCharsets.UTF_8),"PIPECAT_MAX_ACTIVE_SESSIONS","2"));
            try(var ledger=new LocalLedger(directory.resolve("cluster.db").toString());var cluster=new MediaCluster(settings,ledger)) {
                assertEquals(3.0,cluster.metrics().get("pcm_input_frames_total").doubleValue());
                assertEquals(.2,cluster.metrics().get("tts_first_audio_max_seconds").doubleValue(),.000001);
                healthy.set(false);
                assertTimeoutPreemptively(Duration.ofSeconds(4),()->{while(!cluster.metrics().isEmpty())Thread.sleep(10);});
                assertFalse(cluster.metrics().containsKey("pcm_input_frames_total"));
            }
        }finally{servers.forEach(server->server.stop(0));}
    }
}
