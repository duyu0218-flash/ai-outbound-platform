package com.duozhi.outbound;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=${JAVA_TEST_DATABASE_URL:jdbc:sqlite:file:java-auth-test?mode=memory&cache=shared}","spring.datasource.hikari.maximum-pool-size=1","JAVA_INITIALIZE_SCHEMA=true","JWT_SECRET=java-integration-test-secret-at-least-32-characters","DEMO_USERS_ENABLED=true"})
@AutoConfigureMockMvc
class GatewayIntegrationTest {
    @Autowired CallbackInbox inbox; @Autowired Store store; @Autowired Transactions tx; @Autowired JsonSupport json;
    private GatewayCluster cluster(List<Map<String,Object>> nodes) {
        Config cfg=new Config(new MockEnvironment().withProperty("VOICE_GATEWAY_NODES_JSON",json.json(nodes)));
        return new GatewayCluster(store,tx,cfg,json,new TelephonyClient(store,cfg,json),new StabilityAdmission(cfg,new ExpiringValues(cfg),json,inbox));
    }
    private Map<String,Object> spec(String id,int cps) {return JsonSupport.map("id",id,"endpoint","http://gateway-"+id+".invalid","capacity",2,"cps",cps,"enabled",true,"routes",Arrays.asList("110001:0"));}
    @Test void staleUnreadyUnauthorizedAndPacedNodesCannotAdmit() {
        Map<String,Object> node=spec("paced",1);GatewayCluster cluster=cluster(Arrays.asList(node));
        try {
            assertThrows(TaskWorker.Deferred.class,()->tx.run(()->{tx.platform();return cluster.choose(110001,null,1);}));
            cluster.record(node,Store.now(),true,2);
            assertThrows(TaskWorker.Deferred.class,()->tx.run(()->{tx.platform();return cluster.choose(110002,null,1);}));
            assertEquals("paced",tx.run(()->{tx.platform();return cluster.choose(110001,null,1);}).get("id"));
            assertThrows(TaskWorker.Deferred.class,()->tx.run(()->{tx.platform();return cluster.choose(110001,null,1);}));
            store.updateInternal("gatewaynode","paced",JsonSupport.map("next_dial_at",null,"checked_at",Store.now().minusMinutes(1)));
            assertThrows(TaskWorker.Deferred.class,()->tx.run(()->{tx.platform();return cluster.choose(110001,null,1);}));
        } finally {cluster.close();}
    }
    @Test void activeCallReservationPreventsEndpointMoveAndLateProbeOverwrite() {
        Map<String,Object> node=spec("owned",0);GatewayCluster cluster=cluster(Arrays.asList(node));
        try {
            store.insert("tenant",JsonSupport.map("id",110001,"name","Gateway fixture","code","gateway-fixture"));
            cluster.record(node,Store.now(),true,2);
            store.insert("callsession",JsonSupport.map("tenant_id",110001,"phone","13100006666","mode","human_only","status","dialing","attempts",1,"gateway_node_id","owned","gateway_endpoint",node.get("endpoint")));
            Map<String,Object> moved=new LinkedHashMap<>(node);moved.put("endpoint","http://replacement.invalid");cluster.record(moved,Store.now(),true,2);
            Map<String,Object> saved=store.one("gatewaynode","WHERE id='owned'");assertEquals(node.get("endpoint"),saved.get("endpoint"));assertEquals(false,saved.get("ready"));
            cluster.record(node,Store.now().minusHours(1),true,2);assertEquals(false,store.one("gatewaynode","WHERE id='owned'").get("ready"));
            cluster.record(node,Store.now(),true,1);assertThrows(TaskWorker.Deferred.class,()->tx.run(()->{tx.platform();return cluster.choose(110001,null,1);}));
        } finally {cluster.close();}
    }
    @Test void rosterRejectsDuplicatesUnscopedRoutesAndEndpointPaths() {
        Map<String,Object> node=spec("validation",0);GatewayCluster duplicate=cluster(Arrays.asList(node,node));
        try {assertEquals(503,assertThrows(ApiError.class,duplicate::specs).status);}finally{duplicate.close();}
        node.put("routes",Arrays.asList("*"));GatewayCluster invalid=cluster(Arrays.asList(node));try{assertThrows(ApiError.class,invalid::specs);}finally{invalid.close();}
        node.put("routes",Arrays.asList("110001:0"));node.put("endpoint","http://gateway.invalid/path");GatewayCluster path=cluster(Arrays.asList(node));try{assertThrows(ApiError.class,path::specs);}finally{path.close();}
    }
    @Test void probeChecksNodeIdentityAndReportedCapacity() {
        Map<String,Object> node=spec("probe",0);Config cfg=new Config(new MockEnvironment().withProperty("VOICE_GATEWAY_NODES_JSON",json.json(Arrays.asList(node))));
        TelephonyClient client=new TelephonyClient(store,cfg,json){@Override public Map<String,Object> get(String endpoint,Map<String,String> headers,int timeout){return JsonSupport.map("status","ready","node_id","probe","call_capacity",1);}};
        GatewayCluster cluster=new GatewayCluster(store,tx,cfg,json,client,new StabilityAdmission(cfg,new ExpiringValues(cfg),json,inbox));
        try{cluster.probe();Map<String,Object> saved=store.one("gatewaynode","WHERE id='probe'");assertEquals(true,saved.get("ready"));assertEquals(1,saved.get("capacity"));}finally{cluster.close();}
    }
}
