package com.duozhi.outbound;

import java.time.Instant;
import java.util.*;

/** Transaction-local admission facts. Never shared across commits or JVMs. */
final class DialBatchScope implements AutoCloseable {
    private static final ThreadLocal<DialBatchScope> CURRENT=new ThreadLocal<>();
    final Instant instant=Instant.now();
    final Map<String,Map<String,Object>> calls=new HashMap<>(),tenants=new HashMap<>(),settings=new HashMap<>(),scenarios=new HashMap<>(),states=new HashMap<>(),contacts=new HashMap<>(),facts=new HashMap<>(),campaigns=new HashMap<>();
    final Map<Long,List<Map<String,Object>>> lines=new HashMap<>();
    final Map<String,Long> counts=new HashMap<>();final Map<String,Boolean> hours=new HashMap<>();
    final Set<String> stateKeys=new HashSet<>();
    private final DialBatchScope previous;
    DialBatchScope(){previous=CURRENT.get();CURRENT.set(this);}
    static DialBatchScope current(){return CURRENT.get();}
    static String stateKey(Map<String,Object> call){return call.get("id")+":"+call.get("attempts");}
    @Override public void close(){if(previous==null)CURRENT.remove();else CURRENT.set(previous);}
}
