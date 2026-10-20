package com.duozhi.outbound;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CallbackRotationTest {
    @Test void timeLimitedConsumerResumesAfterLastPartitionInsteadOfStarvingTheTail()throws Exception {
        Store store=mock(Store.class);Config config=mock(Config.class);CallbackInbox inbox=mock(CallbackInbox.class);
        when(config.flag(anyString(),anyBoolean())).thenReturn(true);
        var partitions=new ArrayList<Map<String,Object>>();for(int id=0;id<64;id++)partitions.add(Map.of("partition_id",id));
        when(store.projection(eq("callbackinbox"),eq("rawTaskWorker192_1"),anyMap())).thenReturn(partitions);
        var visited=new CopyOnWriteArrayList<Integer>();
        when(inbox.consume(anyInt())).thenAnswer(invocation->{visited.add(invocation.getArgument(0));Thread.sleep(60);return true;});
        var worker=new TaskWorker(store,mock(Transactions.class),config,mock(JsonSupport.class),mock(Outbox.class),mock(DialService.class),mock(CallLifecycle.class),mock(CallAnalysis.class),inbox,mock(AiRuntime.class),mock(IntegrationTasks.class),mock(DialBatchWorker.class));
        try {
            worker.ready();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(visited.size()<12&&System.nanoTime()<deadline){worker.receiveTick();Thread.sleep(10);}
            assertThat(visited).hasSizeGreaterThanOrEqualTo(12);
            assertThat(visited.subList(0,12)).containsExactly(0,1,2,3,4,5,6,7,8,9,10,11);
        }finally{worker.close();}
    }
}
