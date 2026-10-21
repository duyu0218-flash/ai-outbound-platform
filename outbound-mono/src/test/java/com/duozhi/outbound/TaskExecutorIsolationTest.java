package com.duozhi.outbound;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.*;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TaskExecutorIsolationTest {
    @Test void blockedColdAndIntegrationWorkersCannotOccupyAiOrPlaybackThreads()throws Exception {
        var environment=new StandardEnvironment();environment.getPropertySources().addFirst(new MapPropertySource("isolation",Map.of(
            "JAVA_WORKERS_ENABLED","true","JAVA_TASK_WORKERS","1","JAVA_AI_TASK_WORKERS","1",
            "JAVA_ANALYSIS_TASK_OWNERS","2","JAVA_ANALYSIS_TASK_WORKERS","1","JAVA_CONTROL_TASK_WORKERS","1")));
        var cold=new CountDownLatch(1);var integration=new CountDownLatch(1);var ai=new CountDownLatch(1);var control=new CountDownLatch(1);var release=new CountDownLatch(1);
        var names=ConcurrentHashMap.<String>newKeySet();
        var worker=new TaskWorker(mock(Store.class),mock(Transactions.class),new Config(environment),mock(JsonSupport.class),mock(Outbox.class),mock(DialService.class),mock(CallLifecycle.class),mock(CallAnalysis.class),mock(CallbackInbox.class),mock(AiRuntime.class),mock(IntegrationTasks.class),mock(DialBatchWorker.class)) {
            @Override public int enqueueDue(){return 0;}
            @Override boolean processLane(Long tenant,int lane){
                String type=((String[])ReflectionTestUtils.getField(this,"laneTypes"))[lane];names.add(Thread.currentThread().getName());
                try{if(type.contains("call_analysis")){cold.countDown();release.await(5,TimeUnit.SECONDS);}
                    else if(type.contains("business_callback")){integration.countDown();release.await(5,TimeUnit.SECONDS);}
                    else if(type.contains("ai_turn"))ai.countDown();else if(type.contains("after_playback"))control.countDown();
                }catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
                return false;
            }
        };
        ReflectionTestUtils.setField(worker,"dialControl",mock(DialDrainControl.class));
        try {
            worker.ready();worker.tick();assertThat(cold.await(2,TimeUnit.SECONDS)).isTrue();assertThat(integration.await(2,TimeUnit.SECONDS)).isTrue();
            assertThat(ai.await(2,TimeUnit.SECONDS)).isTrue();assertThat(control.await(2,TimeUnit.SECONDS)).isTrue();
            assertThat(names).contains("java-ai-turn","java-call-control","java-call-analysis","java-outbox");
            for(int i=0;i<100;i++)worker.tick();
            for(String field:List.of("executor","aiExecutor","controlExecutor","analysisExecutor"))
                assertThat(((ThreadPoolExecutor)ReflectionTestUtils.getField(worker,field)).getQueue().size()).isLessThanOrEqualTo(16);
        }finally{release.countDown();worker.close();}
    }
}
