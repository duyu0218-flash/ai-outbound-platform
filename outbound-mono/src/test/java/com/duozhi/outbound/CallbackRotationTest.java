package com.duozhi.outbound;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CallbackRotationTest {
    @Test void analysisOwnersCannotPublishAiWorkerProgress()throws Exception{
        var environment=new org.springframework.core.env.StandardEnvironment();environment.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("analysis-health-fixture",Map.of("JAVA_TASK_WORKERS","2","JAVA_AI_TASK_OWNERS","2","JAVA_ANALYSIS_TASK_OWNERS","2")));
        var worker=new TaskWorker(mock(Store.class),mock(Transactions.class),new Config(environment),mock(JsonSupport.class),mock(Outbox.class),mock(DialService.class),mock(CallLifecycle.class),mock(CallAnalysis.class),mock(CallbackInbox.class),mock(AiRuntime.class),mock(IntegrationTasks.class),mock(DialBatchWorker.class));
        try{
            var types=(String[])org.springframework.test.util.ReflectionTestUtils.getField(worker,"laneTypes");
            var lanes=(java.util.concurrent.atomic.AtomicBoolean[])org.springframework.test.util.ReflectionTestUtils.getField(worker,"lanes");
            var submit=TaskWorker.class.getDeclaredMethod("submitLane",int.class,long.class,boolean.class);submit.setAccessible(true);
            int analysis=Arrays.asList(types).indexOf("('call_analysis')");lanes[analysis].set(true);submit.invoke(worker,analysis,System.nanoTime(),false);
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(lanes[analysis].get()&&System.nanoTime()<until)Thread.sleep(5);
            assertThat(lanes[analysis].get()).isFalse();assertThat((Long)org.springframework.test.util.ReflectionTestUtils.getField(worker,"aiProgress")).isZero();
            int ai=4;assertThat(types[ai]).isEqualTo("('ai_turn')");lanes[ai].set(true);submit.invoke(worker,ai,System.nanoTime(),false);
            until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(lanes[ai].get()&&System.nanoTime()<until)Thread.sleep(5);
            assertThat(lanes[ai].get()).isFalse();assertThat((Long)org.springframework.test.util.ReflectionTestUtils.getField(worker,"aiProgress")).isPositive();
        }finally{worker.close();}
    }
    @Test void timeLimitedConsumerResumesAfterLastPartitionInsteadOfStarvingTheTail()throws Exception {
        Store store=mock(Store.class);Config config=mock(Config.class);CallbackInbox inbox=mock(CallbackInbox.class);
        when(config.flag(anyString(),anyBoolean())).thenReturn(true);
        when(config.number("JAVA_ANALYSIS_BATCH_SIZE",1)).thenReturn(1);
        var partitions=new ArrayList<Map<String,Object>>();for(int id=0;id<64;id++)partitions.add(Map.of("partition_id",id));
        when(store.projection(eq("callbackinbox"),eq("rawTaskWorker192_1"),anyMap())).thenReturn(partitions);
        var visited=new CopyOnWriteArrayList<Integer>();
        when(inbox.consume(anyInt())).thenAnswer(invocation->{visited.add(invocation.getArgument(0));Thread.sleep(60);return true;});
        var worker=new TaskWorker(store,mock(Transactions.class),config,mock(JsonSupport.class),mock(Outbox.class),mock(DialService.class),mock(CallLifecycle.class),mock(CallAnalysis.class),inbox,mock(AiRuntime.class),mock(IntegrationTasks.class),mock(DialBatchWorker.class));
        org.springframework.test.util.ReflectionTestUtils.setField(worker,"dialControl",mock(DialDrainControl.class));
        try {
            worker.ready();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(visited.size()<12&&System.nanoTime()<deadline){worker.receiveTick();Thread.sleep(10);}
            assertThat(visited).hasSizeGreaterThanOrEqualTo(12);
            assertThat(visited.subList(0,12)).containsExactly(0,1,2,3,4,5,6,7,8,9,10,11);
        }finally{worker.close();}
    }
}
