package com.duozhi.outbound;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.lang.management.ManagementFactory;
import io.micrometer.core.instrument.*;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
/** Original metric names and dimensions exported by the same Micrometer registry as Actuator. */
@org.springframework.stereotype.Component
public class MetricsRegistry {
    private record Key(String name,List<String> labels){}
    private record Value(AtomicReference<Number> number,Meter meter){}
    private final PrometheusMeterRegistry registry;private final Map<Key,Value> values=new LinkedHashMap<>();private final Set<Key> business=new HashSet<>();private Set<Key> collecting;
    public MetricsRegistry(PrometheusMeterRegistry registry){this.registry=registry;runtime();}
    public synchronized void put(String name,Object raw,String... labels){Number number=raw instanceof Boolean flag?flag?1:0:raw instanceof Number n?n:null;if(number==null||!Double.isFinite(number.doubleValue()))return;Key key=new Key(name,List.of(labels));Value value=values.get(key);if(value==null){var state=new AtomicReference<Number>(number);Gauge gauge=Gauge.builder(name,state,stateValue->stateValue.get().doubleValue()).tags(labels).strongReference(true).register(registry);value=new Value(state,gauge);values.put(key,value);}value.number.set(number);if(collecting!=null)collecting.add(key);}
    public synchronized void runtime(){put("ai_outbound_process_id",ProcessHandle.current().pid());put("ai_outbound_jvm_threads",ManagementFactory.getThreadMXBean().getThreadCount());put("ai_outbound_jvm_heap_used_bytes",ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());put("ai_outbound_jvm_uptime_seconds",ManagementFactory.getRuntimeMXBean().getUptime()/1000.0);var memory=ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();put("ai_outbound_java_heap_used_bytes",memory.getUsed());put("ai_outbound_java_heap_max_bytes",memory.getMax());put("ai_outbound_java_threads",ManagementFactory.getThreadMXBean().getThreadCount());put("ai_outbound_java_uptime_seconds",ManagementFactory.getRuntimeMXBean().getUptime()/1000.0);long count=0,millis=0;for(var gc:ManagementFactory.getGarbageCollectorMXBeans()){if(gc.getCollectionCount()>=0)count+=gc.getCollectionCount();if(gc.getCollectionTime()>=0)millis+=gc.getCollectionTime();}put("ai_outbound_java_gc_collections_total",count);put("ai_outbound_java_gc_seconds_total",millis/1000.0);}
    public synchronized void snapshot(Runnable update){Set<Key> current=new HashSet<>();collecting=current;try{update.run();for(Key old:business)if(!current.contains(old)){Value removed=values.remove(old);if(removed!=null)registry.remove(removed.meter);}business.clear();business.addAll(current);}finally{collecting=null;}}
    public synchronized String scrape(boolean onlyRuntime){runtime();Set<String> names=new HashSet<>();for(Key key:values.keySet())if(!onlyRuntime||key.name.startsWith("ai_outbound_jvm_")||key.name.startsWith("ai_outbound_java_")||key.name.equals("ai_outbound_process_id"))names.add(key.name);return registry.scrape("text/plain; version=0.0.4; charset=utf-8",names);}
}
