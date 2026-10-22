package com.duozhi.outbound.dial.service;

import static org.assertj.core.api.Assertions.*;

import com.duozhi.outbound.callback.service.CallbackInbox;
import com.duozhi.outbound.common.config.Config;
import com.duozhi.outbound.job.service.RealtimeTaskPressure;
import com.duozhi.outbound.voice.controller.VoiceController;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

class DialRateControlTest {
    @Test
    void samplerUsesItsOwnLaneAndCanStopWithoutDefaultScheduler() throws Exception {
        var config = Mockito.mock(Config.class);
        var inbox = Mockito.mock(CallbackInbox.class);
        var jdbc = Mockito.mock(JdbcTemplate.class);
        var voice = Mockito.mock(VoiceController.class);
        ObjectProvider<VoiceController> provider = Mockito.mock(ObjectProvider.class);
        Mockito.when(config.get("JAVA_DIAL_RATE_CONTROL", "legacy")).thenReturn("adaptive");
        Mockito.when(config.flag("JAVA_WORKERS_ENABLED", false)).thenReturn(true);
        Mockito.when(config.number(ArgumentMatchers.anyString(), ArgumentMatchers.anyInt()))
                .thenAnswer(call -> call.getArgument(1));
        var observed = new CountDownLatch(2);
        var threadNames = ConcurrentHashMap.<String>newKeySet();
        Mockito.when(inbox.snapshot())
                .thenAnswer(
                        call -> {
                            threadNames.add(Thread.currentThread().getName());
                            observed.countDown();
                            return Map.of("live_workers", 1, "dead", 0, "oldest_age_sec", 0);
                        });
        Mockito.when(jdbc.queryForMap(ArgumentMatchers.anyString()))
                .thenReturn(Map.of("n", 0, "b", 0));
        Mockito.when(provider.getObject()).thenReturn(voice);
        Mockito.when(voice.metricValues())
                .thenReturn(
                        Map.of(
                                "ai_outbound_voice_gateway_",
                                Map.of("ready", 1),
                                "ai_outbound_voice_security_",
                                Map.of("stopped", false, "oldest_callback_age_sec", 0)));
        var pressure = Mockito.mock(RealtimeTaskPressure.class);
        Mockito.when(pressure.snapshot())
                .thenReturn(Map.of("control_age_sec", 0d, "ai_age_sec", 0d, "dead", 0d));
        var control = new DialRateControl(config, inbox, jdbc, provider, pressure);
        try {
            control.startSampler();
            control.startSampler();
            assertThat(observed.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(threadNames).containsExactly("dial-rate-sampler");
        } finally {
            control.stopSampler();
        }
    }

    @Test
    void slowOrMissingSamplesCannotEarnRecoveryCredit() {
        Assertions.assertThat(DialRateControl.recoveryElapsed(0, 1_000_000_000L)).isZero();
        Assertions.assertThat(DialRateControl.recoveryElapsed(1, 2_000_000_001L)).isZero();
        Assertions.assertThat(DialRateControl.recoveryElapsed(1, 200_000_001L)).isEqualTo(.2);
        Assertions.assertThat(DialRateControl.recoveryElapsed(1, 400_000_001L)).isEqualTo(.2);
    }

    @Test
    void ageCurveHasOneSetOfBoundariesAndHardFailuresAlwaysStop() {
        assertThat(DialRateControl.target(0, true)).isEqualTo(1);
        assertThat(DialRateControl.target(.5, true)).isEqualTo(1);
        assertThat(DialRateControl.target(3, true)).isCloseTo(.3, within(1e-9));
        assertThat(DialRateControl.target(4, true)).isCloseTo(.15, within(1e-9));
        for (double age : new double[] {0, 1, 4, 5, Double.NaN})
            assertThat(DialRateControl.target(age, false)).isZero();
        assertThat(DialRateControl.target(5, true)).isZero();
    }

    @Test
    void recoveryIsBoundedToTenPercentPerSecondButPressureAppliesImmediately() {
        assertThat(DialRateControl.advance(0, 1, .2)).isCloseTo(.02, within(1e-9));
        assertThat(DialRateControl.advance(.5, 1, 1)).isCloseTo(.6, within(1e-9));
        assertThat(DialRateControl.advance(1, .1, .2)).isEqualTo(.1);
        assertThat(DialRateControl.advance(.5, 0, 0)).isZero();
    }
}
