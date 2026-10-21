package com.duozhi.outbound;

import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class DailyAttemptCountTest {
    private final LocalDateTime day=LocalDateTime.of(2026,10,21,0,0);
    private Map<String,Object> call(int attempts, LocalDateTime started) {
        var result=new HashMap<String,Object>();result.put("attempts",attempts);
        result.put("created_at",day.minusDays(1).toString());result.put("started_at",started);return result;
    }
    @Test void reAdmissionPreservesIndependentLegacyAndEventComponents() {
        var yesterday=call(2,day.minusHours(1));var claimed=call(3,day.plusHours(1));var rejected=call(2,null);
        var tenant=new DailyAttemptCount(0,0).changed(yesterday,claimed,"started_at",day,1);
        var phone=new DailyAttemptCount(0,0).changed(yesterday,claimed,"created_at",day,1);
        assertThat(tenant).isEqualTo(new DailyAttemptCount(3,1));assertThat(phone.effective()).isEqualTo(1);
        tenant=tenant.changed(claimed,rejected,"started_at",day,0);
        assertThat(tenant).isEqualTo(new DailyAttemptCount(0,1));
        tenant=tenant.changed(rejected,claimed,"started_at",day,1);
        phone=phone.changed(rejected,claimed,"created_at",day,1);
        assertThat(tenant.effective()).isEqualTo(3);assertThat(phone.effective()).isEqualTo(2);
    }
    @Test void currentDayPhoneLegacyUsesAttemptsDeltaRatherThanNewTotal() {
        var before=call(2,day);before.put("created_at",day.toString());
        var after=new HashMap<>(before);after.put("attempts",3);
        assertThat(new DailyAttemptCount(2,1).changed(before,after,"created_at",day,1)).isEqualTo(new DailyAttemptCount(3,2));
    }
    @Test void SQLLowerBoundHasNoAddedUpperBoundAndIncludesExactMidnight() {
        assertThat(DailyAttemptCount.contribution(call(2,day),"started_at",day)).isEqualTo(2);
        assertThat(DailyAttemptCount.contribution(call(2,day.plusDays(1)),"started_at",day)).isEqualTo(2);
        assertThat(DailyAttemptCount.contribution(call(2,day.minusNanos(1)),"started_at",day)).isZero();
    }
    @Test void corruptedNegativeComponentsAndOverflowFailClosed() {
        assertThatThrownBy(()->new DailyAttemptCount(-1,0)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->new DailyAttemptCount(0,Long.MAX_VALUE).changed(call(0,null),call(0,null),"started_at",day,1)).isInstanceOf(ArithmeticException.class);
    }
}
