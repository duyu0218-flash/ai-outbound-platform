package com.duozhi.outbound.messaging.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.duozhi.outbound.common.config.Config;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

class MessageWorkerIsolationTest {
    private Config config() {
        return new Config(
                new MockEnvironment()
                        .withProperty("MESSAGING_ENABLED", "true")
                        .withProperty("MESSAGING_WORKERS_ENABLED", "true")
                        .withProperty("MESSAGING_SEND_WORKERS", "2")
                        .withProperty("MESSAGING_CHANNEL_CONCURRENCY", "1")
                        .withProperty("MESSAGING_QUERY_WORKERS", "2"));
    }

    @Test
    void slowQueryAndSlowSendCannotBlockOtherTenantChannelOrCallIntents() throws Exception {
        var messaging = mock(Messaging.class);
        var delivery = mock(MessageDelivery.class);
        var slow = new MessagePartition(1, "slow");
        var healthy = new MessagePartition(2, "healthy");
        var queryEntered = new CountDownLatch(1);
        var sendEntered = new CountDownLatch(1);
        var healthySent = new CountDownLatch(1);
        var healthyQueried = new CountDownLatch(1);
        var prepared = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var slowSends = new AtomicInteger();
        var slowQueries = new AtomicInteger();
        when(messaging.readyPartitions(any(), anyInt()))
                .thenAnswer(
                        inv ->
                                inv.getArgument(0).equals(MessagePartition.START)
                                        ? List.of(slow, healthy)
                                        : List.of());
        when(delivery.queryPartitions(any(), anyInt()))
                .thenAnswer(
                        inv ->
                                inv.getArgument(0).equals(MessagePartition.START)
                                        ? List.of(slow, healthy)
                                        : List.of());
        when(messaging.processOne(slow))
                .thenAnswer(
                        inv -> {
                            slowSends.incrementAndGet();
                            sendEntered.countDown();
                            release.await(3, TimeUnit.SECONDS);
                            return false;
                        });
        when(delivery.queryOne(slow))
                .thenAnswer(
                        inv -> {
                            slowQueries.incrementAndGet();
                            queryEntered.countDown();
                            release.await(3, TimeUnit.SECONDS);
                            return false;
                        });
        when(messaging.processOne(healthy))
                .thenAnswer(
                        inv -> {
                            healthySent.countDown();
                            return false;
                        });
        when(delivery.queryOne(healthy))
                .thenAnswer(
                        inv -> {
                            healthyQueried.countDown();
                            return false;
                        });
        when(messaging.processCallIntent())
                .thenAnswer(
                        inv -> {
                            prepared.countDown();
                            return false;
                        });
        var worker = new MessageWorker(messaging, config());
        ReflectionTestUtils.setField(worker, "delivery", delivery);
        try {
            worker.tick();
            assertThat(queryEntered.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(sendEntered.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(healthySent.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(healthyQueried.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(prepared.await(1, TimeUnit.SECONDS)).isTrue();
            for (int i = 0; i < 100; i++) worker.tick();
            assertThat(slowSends).hasValue(1);
            assertThat(slowQueries).hasValue(1);
            for (String field : List.of("sends", "queries", "preparation", "recovery"))
                assertThat(
                                ((ThreadPoolExecutor) ReflectionTestUtils.getField(worker, field))
                                        .getQueue()
                                        .size())
                        .isLessThanOrEqualTo(2);
        } finally {
            release.countDown();
            worker.close();
        }
    }

    @Test
    void expiredSliceDoesNotStartAnotherSubmission() throws Exception {
        var messaging = mock(Messaging.class);
        var delivery = mock(MessageDelivery.class);
        var partition = new MessagePartition(1, "one");
        var finished = new CountDownLatch(1);
        when(messaging.readyPartitions(any(), anyInt())).thenReturn(List.of(partition));
        when(messaging.processOne(partition))
                .thenAnswer(
                        inv -> {
                            Thread.sleep(150);
                            finished.countDown();
                            return true;
                        });
        var cfg =
                new Config(
                        new MockEnvironment()
                                .withProperty("MESSAGING_ENABLED", "true")
                                .withProperty("MESSAGING_WORKERS_ENABLED", "true")
                                .withProperty("MESSAGING_CHANNEL_CONCURRENCY", "1")
                                .withProperty("MESSAGING_WORK_BUDGET_MS", "100"));
        var worker = new MessageWorker(messaging, cfg);
        ReflectionTestUtils.setField(worker, "delivery", delivery);
        try {
            worker.tick();
            assertThat(finished.await(1, TimeUnit.SECONDS)).isTrue();
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (!((Set<?>) ReflectionTestUtils.getField(worker, "sending")).isEmpty()
                    && System.nanoTime() < until) Thread.onSpinWait();
            verify(messaging, times(1)).processOne(partition);
        } finally {
            worker.close();
        }
    }

    @Test
    void oneChannelCanFillItsBoundedWindowWithoutLaunchingUnboundedOwners() throws Exception {
        var messaging = mock(Messaging.class);
        var delivery = mock(MessageDelivery.class);
        var partition = new MessagePartition(1, "cmpp");
        var entered = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        when(messaging.readyPartitions(any(), anyInt())).thenReturn(List.of(partition));
        when(messaging.processOne(partition))
                .thenAnswer(
                        inv -> {
                            calls.incrementAndGet();
                            entered.countDown();
                            release.await(3, TimeUnit.SECONDS);
                            return false;
                        });
        var cfg =
                new Config(
                        new MockEnvironment()
                                .withProperty("MESSAGING_ENABLED", "true")
                                .withProperty("MESSAGING_WORKERS_ENABLED", "true")
                                .withProperty("MESSAGING_SEND_WORKERS", "4")
                                .withProperty("MESSAGING_CHANNEL_CONCURRENCY", "2"));
        var worker = new MessageWorker(messaging, cfg);
        ReflectionTestUtils.setField(worker, "delivery", delivery);
        try {
            worker.tick();
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            for (int i = 0; i < 100; i++) worker.tick();
            assertThat(calls).hasValue(2);
        } finally {
            release.countDown();
            worker.close();
        }
    }
}
