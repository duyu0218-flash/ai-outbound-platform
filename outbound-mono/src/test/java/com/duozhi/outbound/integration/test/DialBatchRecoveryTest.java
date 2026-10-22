package com.duozhi.outbound.integration.test;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.duozhi.outbound.callback.service.CallbackInbox;
import com.duozhi.outbound.common.config.Config;
import com.duozhi.outbound.common.dao.Store;
import com.duozhi.outbound.common.service.Transactions;
import com.duozhi.outbound.common.support.JsonSupport;
import com.duozhi.outbound.dial.dao.DialBatchStore;
import com.duozhi.outbound.dial.service.DialDrainControl;
import com.duozhi.outbound.dial.service.DialRateControl;
import com.duozhi.outbound.dial.service.DialService;
import com.duozhi.outbound.dial.worker.DialBatchWorker;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.env.*;
import org.springframework.test.util.ReflectionTestUtils;

class DialBatchRecoveryTest {
    @Test
    void pressureStopsNewClaimsButSendClosedCanFinishOriginalCommandTasks() {
        var env = new StandardEnvironment();
        env.getPropertySources()
                .addFirst(
                        new MapPropertySource(
                                "recovery",
                                Map.of(
                                        "JAVA_DISPATCH_ENABLED",
                                        "true",
                                        "CALLBACK_INBOX_ENABLED",
                                        "true",
                                        "JAVA_DIAL_BATCH_SIZE",
                                        "100")));
        var store = mock(Store.class);
        var tx = mock(Transactions.class);
        var db = mock(DialBatchStore.class);
        when(tx.serializedIdentity(anyString(), any()))
                .thenAnswer(call -> ((Supplier<?>) call.getArgument(1)).get());
        when(store.query(eq("tenant"), eq("allForMaintenance"), any()))
                .thenReturn(List.of(Map.of("id", 1L)));
        var rate = mock(DialRateControl.class);
        when(rate.healthyForAdmission()).thenReturn(true);
        var drain = mock(DialDrainControl.class);
        var worker =
                new DialBatchWorker(
                        store,
                        db,
                        tx,
                        mock(DialService.class),
                        new Config(env),
                        mock(JsonSupport.class),
                        mock(CallbackInbox.class));
        ReflectionTestUtils.setField(worker, "rate", rate);
        ReflectionTestUtils.setField(worker, "drain", drain);
        assertThat(worker.process(null, null)).isFalse();
        when(rate.healthyForAdmission()).thenReturn(false);
        assertThat(worker.process(null, null)).isFalse();
        when(drain.sendingClosed()).thenReturn(true);
        assertThat(worker.process(null, null)).isFalse();
        var params = ArgumentCaptor.forClass(Map.class);
        verify(db, times(2)).tasks(params.capture());
        assertThat(params.getAllValues().get(0))
                .containsEntry("limit", 100)
                .containsEntry("recoverOnly", false);
        assertThat(params.getAllValues().get(1))
                .containsEntry("limit", 100)
                .containsEntry("recoverOnly", true);
    }
}
