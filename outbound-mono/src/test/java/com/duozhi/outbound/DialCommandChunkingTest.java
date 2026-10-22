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
        org.springframework.test.util.ReflectionTestUtils.setField(service,"drain",mock(DialDrainControl.class));
        var sent=new ArrayList<Integer>();var acknowledged=new ArrayList<String>();var sequence=new AtomicInteger();
        doAnswer(invocation->{List<Map<String,Object>> calls=invocation.getArgument(0);for(var entry:calls)acknowledged.add(((Map<?,?>)entry.get("call")).get("id").toString());return null;}).when(service).acknowledgeTasks(anyList(),same(db));
        when(client.dialBatch(anyList())).thenAnswer(invocation->{
            List<Map<String,Object>> calls=invocation.getArgument(0);sent.add(calls.size());
            if(sequence.incrementAndGet()==2&&failMiddle)throw new TelephonyClient.Failure(503,false);
            return calls.stream().map(entry->new CommandResult(Map.of("result","accepted","provider_call_id",((Map<?,?>)entry.get("call")).get("id")),200,false)).toList();
        });
        var entries=new ArrayList<Map<String,Object>>();for(int i=0;i<250;i++)entries.add(JsonSupport.map("call",JsonSupport.map("id","synthetic-"+i,"tenant_id",1,"attempts",1),"metadata",Map.of(),"payload",new LinkedHashMap<>()));
        service.sendTasks(entries,db);
        assertThat(sent).containsExactly(100,100,50);assertThat(new HashSet<>(acknowledged)).hasSize(failMiddle?150:250);assertThat(acknowledged).hasSize(failMiddle?150:250);
        for(int i=0;i<entries.size();i++){
            boolean unknown=failMiddle&&i>=100&&i<200;
            assertThat(entries.get(i).containsKey("error")).isEqualTo(unknown);
            assertThat(entries.get(i).containsKey("pending_confirmation")).isEqualTo(unknown);
        }
    }
    @Test void registeredAndUnknownResponsesNeverBecomeDurableAcceptance(){
        assertThat(DialService.acceptedResponse(Map.of("result","pending_reconciliation","provider_call_id","k"))).isFalse();
        assertThat(DialService.acceptedResponse(Map.of("result","registered","provider_call_id","k"))).isFalse();
        assertThat(DialService.acceptedResponse(Map.of("provider_call_id","k"))).isFalse();
        assertThat(DialService.acceptedResponse(Map.of("result","accepted","provider_call_id","k"))).isTrue();
    }
    @Test void unprovenRateLimitOrServiceFailureCannotReleaseAnUnknownCommand(){
        for(int status:List.of(0,400,401,403,404,422,429,500,503))assertThat(new TelephonyClient.Failure(status,false).definitive()).isFalse();
    }
    @Test void incompleteBatchResponseRetainsEveryOriginalIdentity(){
        var client=mock(TelephonyClient.class);var service=new DialService(null,null,null,null,null,null,client,null,null,null,null,null,null,null);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"drain",mock(DialDrainControl.class));
        when(client.dialBatch(anyList())).thenReturn(List.of());
        var entry=JsonSupport.map("call",JsonSupport.map("id","synthetic","tenant_id",1,"attempts",1),"metadata",Map.of(),"payload",new LinkedHashMap<>());
        service.sendTasks(List.of(entry),mock(DialBatchStore.class));
        assertThat(entry).containsEntry("pending_confirmation",true).doesNotContainKey("response");
    }
    @Test void sendingClosedRetriesOnlyQueryTheOriginalCommandAndCannotRedial(){
        var client=mock(TelephonyClient.class);var db=mock(DialBatchStore.class);var drain=mock(DialDrainControl.class);
        when(drain.sendingClosed()).thenReturn(true);
        var service=spy(new DialService(null,null,null,null,null,null,client,null,null,null,null,null,null,null));
        org.springframework.test.util.ReflectionTestUtils.setField(service,"drain",drain);
        doReturn(true).when(service).confirmClaimed(anyMap(),anyMap());
        var call=JsonSupport.map("id","synthetic","tenant_id",1,"attempts",1);var metadata=Map.<String,Object>of();
        service.sendTasks(List.of(JsonSupport.map("call",call,"metadata",metadata,"payload",new LinkedHashMap<>())),db);
        org.mockito.Mockito.verify(service).confirmClaimed(same(call),same(metadata));verifyNoInteractions(client,db);
    }

}
