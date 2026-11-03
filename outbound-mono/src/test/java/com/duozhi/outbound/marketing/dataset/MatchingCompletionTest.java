package com.duozhi.outbound.marketing.dataset;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.duozhi.outbound.marketing.common.MarketingStore;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.InvalidDataAccessResourceUsageException;

class MatchingCompletionTest {
    private static final long TENANT = 7L;
    private static final String JOB = "completion-test";
    private static final String STALE = "UPDATE mk_match_request SET state='uncertain'";
    private static final String FINISHED = "UPDATE mk_match_job SET state='finished'";
    private final MarketingStore db = mock(MarketingStore.class);
    private final MatchAdapter adapter = mock(MatchAdapter.class);
    private final MatchFlights flights = mock(MatchFlights.class);

    private Matching matching() {
        var job = Map.<String, Object>of("tenant_id", TENANT, "id", JOB, "adapter", "stub");
        when(adapter.id()).thenReturn("stub");
        when(adapter.online()).thenReturn(true);
        when(adapter.configured()).thenReturn(true);
        when(db.one(anyString(), eq(JOB))).thenReturn(job);
        when(db.rows(anyString(), eq(TENANT), eq(JOB), eq(Matching.STEP))).thenReturn(List.of());
        when(db.count(anyString(), eq(TENANT), eq(JOB))).thenReturn(0L);
        return new Matching(db, null, null, null, null, List.of(adapter), null, flights);
    }

    @Test
    void aCompletionDeadlockDefersOnlyDatabaseWorkUntilTheNextPoll() {
        var matching = matching();
        when(db.write(startsWith(STALE), eq(TENANT), eq(JOB)))
                .thenThrow(new CannotAcquireLockException("simulated completion deadlock"))
                .thenReturn(0);

        assertThat(matching.step(JOB)).isFalse();
        verify(db, never()).write(startsWith(FINISHED), eq(TENANT), eq(JOB));
        assertThat(matching.step(JOB)).isFalse();
        verify(db).write(startsWith(FINISHED), eq(TENANT), eq(JOB));
        verify(adapter, never()).query(any());
        verify(flights, never()).claim(anyMap(), anyMap());
    }

    @Test
    void anUnrelatedDatabaseFailureIsStillReportedWithoutSendingAnything() {
        var matching = matching();
        when(db.write(startsWith(STALE), eq(TENANT), eq(JOB)))
                .thenThrow(new InvalidDataAccessResourceUsageException("broken database contract"));

        assertThatThrownBy(() -> matching.step(JOB))
                .isInstanceOf(InvalidDataAccessResourceUsageException.class);
        verify(db, never()).write(startsWith(FINISHED), eq(TENANT), eq(JOB));
        verify(adapter, never()).query(any());
    }
}
