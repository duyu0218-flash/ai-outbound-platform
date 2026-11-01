package com.duozhi.outbound.marketing.journey;

import static org.assertj.core.api.Assertions.*;

import java.time.*;
import java.util.TimeZone;
import org.junit.jupiter.api.Test;

class MarketingClockTest {
    @Test
    void applicationStorageUsesUtcEvenWhenTheServerUsesShanghaiTime() {
        TimeZone previous = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
            Instant instant = Instant.parse("2026-11-01T02:30:00Z");
            LocalDateTime stored = LocalDateTime.parse("2026-11-01T02:30:00");
            assertThat(MarketingClock.app(instant)).isEqualTo(stored);
            assertThat(MarketingClock.fromApp(stored)).isEqualTo(instant);
        } finally {
            TimeZone.setDefault(previous);
        }
    }
}
