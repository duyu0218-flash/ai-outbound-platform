package com.duozhi.outbound.integration.test;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.duozhi.outbound.call.service.CallLifecycle;
import com.duozhi.outbound.call.service.CallReconciliation;
import com.duozhi.outbound.common.config.Config;
import com.duozhi.outbound.common.dao.Store;
import com.duozhi.outbound.common.service.Transactions;
import com.duozhi.outbound.common.support.JsonSupport;
import com.duozhi.outbound.integration.service.TelephonyClient;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class CallReconciliationTest {
    @Test
    void delayedAcceptanceCannotBeHungUpByAnOldSnapshot() {
        probe(false, false, false);
    }

    @Test
    void providerConfirmedEndRecoversLifecycle() {
        probe(true, false, true);
    }

    @Test
    void oldProviderEndCannotEndReplacement() {
        probe(true, true, false);
    }

    @Test
    void unknownProviderRetainsResources() {
        probe(null, false, false);
    }

    @SuppressWarnings("unchecked")
    private void probe(Boolean ended, boolean replaced, boolean release) {
        var store = mock(Store.class);
        var tx = mock(Transactions.class);
        var client = mock(TelephonyClient.class);
        var lifecycle = mock(CallLifecycle.class);
        var stale =
                JsonSupport.map(
                        "id",
                        "call",
                        "tenant_id",
                        1,
                        "attempts",
                        1,
                        "telephony_call_id",
                        "old-provider",
                        "status",
                        "DIALING",
                        "updated_at",
                        "old");
        var latest = new LinkedHashMap<>(stale);
        latest.put("updated_at", "new");
        latest.put("status", "IN_AI");
        if (replaced) latest.put("telephony_call_id", "new-provider");
        when(store.orderedAcross(eq("callsession"), anyString(), anyMap(), anyList(), anyInt()))
                .thenReturn(List.of(stale));
        when(tx.serialized(any(Supplier.class)))
                .thenAnswer(i -> ((Supplier<?>) i.getArgument(0)).get());
        when(tx.call("call")).thenReturn(latest);
        if (ended == null)
            when(client.command(anyMap(), eq("status"), anyMap()))
                    .thenThrow(new TelephonyClient.Failure(503, false));
        else
            when(client.command(anyMap(), eq("status"), anyMap()))
                    .thenReturn(Map.of("ended", ended));
        var service =
                new CallReconciliation(
                        store, tx, new Config(new MockEnvironment()), client, lifecycle);
        try {
            assertThat(service.reconcile(20)).isEqualTo(release ? 1 : 0);
            verify(client).command(same(stale), eq("status"), anyMap());
            verify(client, never()).command(anyMap(), eq("hangup"), anyMap());
            if (release) verify(lifecycle).status(anyMap());
            else verifyNoInteractions(lifecycle);
            verify(store, never()).update(anyString(), anyLong(), any(), anyMap());
        } finally {
            service.close();
        }
    }
}
