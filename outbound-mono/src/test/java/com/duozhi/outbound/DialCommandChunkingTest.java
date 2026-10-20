package com.duozhi.outbound;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import com.duozhi.outbound.embedded.VoiceController.CommandResult;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class DialCommandChunkingTest {
    @Test void largerAdmissionCohortKeepsGatewayCommandsAt100AndAcknowledgesEveryChunk(){verify(false);}
    @Test void failedMiddleChunkKeepsSuccessfulAcknowledgementsAndOnlyRetriesItsOwnCalls(){verify(true);}
    private void verify(boolean failMiddle){
        var client=mock(TelephonyClient.class);var db=mock(DialBatchStore.class);
        var service=spy(new DialService(null,null,null,null,null,null,client,null,null,null,null,null,null,null));
        var sent=new ArrayList<Integer>();var acknowledged=new ArrayList<String>();var sequence=new AtomicInteger();
        doAnswer(invocation->{List<Map<String,Object>> calls=invocation.getArgument(0);for(var entry:calls)acknowledged.add(((Map<?,?>)entry.get("call")).get("id").toString());return null;}).when(service).acknowledgeTasks(anyList(),same(db));
        when(client.dialBatch(anyList())).thenAnswer(invocation->{
            List<Map<String,Object>> calls=invocation.getArgument(0);sent.add(calls.size());
            if(sequence.incrementAndGet()==2&&failMiddle)throw new TelephonyClient.Failure(503,false);
            return calls.stream().map(entry->new CommandResult(Map.of("provider_call_id",((Map<?,?>)entry.get("call")).get("id")),200,false)).toList();
        });
        var entries=new ArrayList<Map<String,Object>>();for(int i=0;i<250;i++)entries.add(JsonSupport.map("call",JsonSupport.map("id","synthetic-"+i,"tenant_id",1,"attempts",1),"metadata",Map.of(),"payload",new LinkedHashMap<>()));
        service.sendTasks(entries,db);
        assertThat(sent).containsExactly(100,100,50);assertThat(new HashSet<>(acknowledged)).hasSize(failMiddle?150:250);assertThat(acknowledged).hasSize(failMiddle?150:250);
        for(int i=0;i<entries.size();i++)assertThat(entries.get(i).containsKey("error")).isEqualTo(failMiddle&&i>=100&&i<200);
    }
}
