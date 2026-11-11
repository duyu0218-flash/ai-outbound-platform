package com.duozhi.outbound.dial.worker;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.duozhi.outbound.dial.dao.DialBatchStore;
import com.duozhi.outbound.dial.service.DialService;
import java.util.*;
import org.junit.jupiter.api.Test;

class DialChunkDispatchTest {
    @Test
    void commitsAndSendsEachGroupBeforePreparingMoreCalls() {
        verifyChunks(false);
    }

    @Test
    void laterFailurePreservesPreviouslyAcknowledgedTasksAndOnlyFailsTheUnfinishedSuffix() {
        verifyChunks(true);
    }

    @SuppressWarnings("unchecked")
    private void verifyChunks(boolean failLast) {
        var dial = mock(DialService.class);
        var db = mock(DialBatchStore.class);
        var worker = new DialBatchWorker(null, db, null, dial, null, null, null);
        var tasks = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 250; i++) tasks.add(Map.of("id", "task-" + i));
        var order = new ArrayList<String>();
        var failure = new IllegalStateException("injected later admission failure");
        when(dial.prepareTasks(anyList(), same(db)))
                .thenAnswer(
                        i -> {
                            List<Map<String, Object>> group = i.getArgument(0);
                            order.add("prepare:" + group.size());
                            if (failLast && group.size() == 50) throw failure;
                            return group.stream()
                                    .map(task -> Map.<String, Object>of("task", task))
                                    .toList();
                        });
        doAnswer(
                        i -> {
                            List<?> group = i.getArgument(0);
                            order.add("send:" + group.size());
                            return null;
                        })
                .when(dial)
                .sendTasks(anyList(), same(db));
        var completed = new ArrayList<Map<String, Object>>();
        if (failLast)
            assertThatThrownBy(() -> worker.dispatchChunks(tasks, completed, new long[2]))
                    .isSameAs(failure);
        else worker.dispatchChunks(tasks, completed, new long[2]);
        assertThat(order)
                .containsExactlyElementsOf(
                        failLast
                                ? List.of(
                                        "prepare:100",
                                        "send:100",
                                        "prepare:100",
                                        "send:100",
                                        "prepare:50")
                                : List.of(
                                        "prepare:100",
                                        "send:100",
                                        "prepare:100",
                                        "send:100",
                                        "prepare:50",
                                        "send:50"));
        assertThat(completed).hasSize(failLast ? 200 : 250);
        for (var entry : completed) {
            var outcome = DialBatchWorker.outcome(entry, failLast ? failure : null);
            assertThat(outcome.error()).isNull();
            assertThat(outcome.deferred()).isFalse();
        }
        if (failLast) assertThat(DialBatchWorker.outcome(null, failure).error()).isSameAs(failure);
    }
}
