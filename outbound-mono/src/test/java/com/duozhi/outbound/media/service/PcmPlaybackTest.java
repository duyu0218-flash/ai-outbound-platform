package com.duozhi.outbound.media.service;

import static org.assertj.core.api.Assertions.*;

import com.duozhi.outbound.voiceai.service.ResponseScope;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class PcmPlaybackTest {
    @Test
    void finishWaitsForSenderAndQueueOwnsCopiedBytes() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var heard = new AtomicInteger();
        try (var scope = new ResponseScope();
                var player =
                        new PcmPlayback(
                                8000,
                                scope::check,
                                (bytes, purpose) -> {
                                    entered.countDown();
                                    release.await();
                                    heard.set(bytes[0]);
                                },
                                scope);
                var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
            byte[] bytes = new byte[320];
            bytes[0] = 42;
            player.accept(bytes, "response");
            bytes[0] = 99;
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            var finishing =
                    tasks.submit(
                            () -> {
                                player.finish();
                                return null;
                            });
            assertThatThrownBy(() -> finishing.get(40, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            release.countDown();
            finishing.get(2, TimeUnit.SECONDS);
            assertThat(heard.get()).isEqualTo(42);
        } finally {
            release.countDown();
        }
    }

    @Test
    void cancellationDropsQueuedOutputAndRejectsOldGeneration() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var count = new AtomicInteger();
        try (var scope = new ResponseScope();
                var player =
                        new PcmPlayback(
                                8000,
                                scope::check,
                                (bytes, purpose) -> {
                                    count.incrementAndGet();
                                    entered.countDown();
                                    release.await();
                                },
                                scope)) {
            player.accept(new byte[320 * 4], "response");
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            scope.close();
            release.countDown();
            assertThatThrownBy(player::finish).isInstanceOf(Exception.class);
            assertThatThrownBy(() -> player.accept(new byte[320], "response"))
                    .isInstanceOf(Exception.class);
            assertThat(count.get()).isEqualTo(1);
        } finally {
            release.countDown();
        }
    }
}
