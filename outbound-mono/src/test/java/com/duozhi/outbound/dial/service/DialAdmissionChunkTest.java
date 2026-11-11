package com.duozhi.outbound.dial.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.duozhi.outbound.common.config.Config;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class DialAdmissionChunkTest {
    @Test
    void largeCohortUsesBoundedAdmissionTransactionsInOriginalOrder() {
        var service = fixture(100);
        var sizes = new ArrayList<Integer>();
        doAnswer(
                        i -> {
                            List<Map<String, Object>> tasks = i.getArgument(0);
                            sizes.add(tasks.size());
                            return tasks;
                        })
                .when(service)
                .prepareChunk(anyList(), isNull());
        var tasks = tasks(500);
        assertThat(service.prepareTasks(tasks, null)).containsExactlyElementsOf(tasks);
        assertThat(sizes).containsExactly(100, 100, 100, 100, 100);
    }

    @Test
    void chunkFailureStopsBeforePreparingLaterTasks() {
        var service = fixture(2);
        var visited = new ArrayList<String>();
        doAnswer(
                        i -> {
                            List<Map<String, Object>> tasks = i.getArgument(0);
                            visited.add(tasks.getFirst().get("aggregate_id").toString());
                            if (visited.size() == 2)
                                throw new IllegalStateException(
                                        "injected failure after first commit");
                            return tasks;
                        })
                .when(service)
                .prepareChunk(anyList(), isNull());
        assertThatThrownBy(() -> service.prepareTasks(tasks(7), null))
                .isInstanceOf(IllegalStateException.class);
        assertThat(visited).containsExactly("0", "2");
    }

    private DialService fixture(int size) {
        var config =
                new Config(
                        new MockEnvironment()
                                .withProperty("JAVA_DIAL_ADMISSION_CHUNK_SIZE", "" + size));
        return spy(
                new DialService(
                        null, null, config, null, null, null, null, null, null, null, null, null,
                        null, null, null));
    }

    private List<Map<String, Object>> tasks(int count) {
        var tasks = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < count; i++) tasks.add(Map.of("aggregate_id", "" + i));
        return tasks;
    }
}
