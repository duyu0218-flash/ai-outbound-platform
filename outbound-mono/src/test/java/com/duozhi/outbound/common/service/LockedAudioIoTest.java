package com.duozhi.outbound.common.service;

import static org.junit.jupiter.api.Assertions.*;

import com.duozhi.outbound.integration.config.Settings;
import com.duozhi.outbound.voiceai.service.OpeningAudioCache;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LockedAudioIoTest {
    @Test
    void fileIoIsRefusedEvenWhenPlatformLockWasAcquiredBeforeStartingATransaction() {
        var locks = new JvmPlatformLock(new SimpleMeterRegistry());
        var cache = new OpeningAudioCache(new Settings(Map.of()));
        assertFalse(JvmPlatformLock.heldByCurrentThread());
        try (var first = locks.lock(1);
                var nested = locks.lock(1)) {
            assertTrue(JvmPlatformLock.heldByCurrentThread());
            assertThrows(IllegalStateException.class, () -> cache.read(1, Map.of(), "disclosure"));
        }
        assertFalse(JvmPlatformLock.heldByCurrentThread());
    }
}
