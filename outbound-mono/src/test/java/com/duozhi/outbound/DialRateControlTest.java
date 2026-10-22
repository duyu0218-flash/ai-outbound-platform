package com.duozhi.outbound;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class DialRateControlTest {
 @Test void samplerUsesItsOwnLaneAndCanStopWithoutDefaultScheduler()throws Exception{
  var config=org.mockito.Mockito.mock(Config.class);var inbox=org.mockito.Mockito.mock(CallbackInbox.class);
  var jdbc=org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class);
  var voice=org.mockito.Mockito.mock(com.duozhi.outbound.embedded.VoiceController.class);
  org.springframework.beans.factory.ObjectProvider<com.duozhi.outbound.embedded.VoiceController> provider=org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
  org.mockito.Mockito.when(config.get("JAVA_DIAL_RATE_CONTROL","legacy")).thenReturn("adaptive");
  org.mockito.Mockito.when(config.flag("JAVA_WORKERS_ENABLED",false)).thenReturn(true);
  org.mockito.Mockito.when(config.number(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyInt())).thenAnswer(call->call.getArgument(1));
  var observed=new java.util.concurrent.CountDownLatch(2);var threadNames=java.util.concurrent.ConcurrentHashMap.<String>newKeySet();
  org.mockito.Mockito.when(inbox.snapshot()).thenAnswer(call->{threadNames.add(Thread.currentThread().getName());observed.countDown();return java.util.Map.of("live_workers",1,"dead",0,"oldest_age_sec",0);});
  org.mockito.Mockito.when(jdbc.queryForMap(org.mockito.ArgumentMatchers.anyString())).thenReturn(java.util.Map.of("n",0,"b",0));
  org.mockito.Mockito.when(provider.getObject()).thenReturn(voice);
  org.mockito.Mockito.when(voice.metricValues()).thenReturn(java.util.Map.of("ai_outbound_voice_gateway_",java.util.Map.of("ready",1),"ai_outbound_voice_security_",java.util.Map.of("stopped",false,"oldest_callback_age_sec",0)));
  var pressure=org.mockito.Mockito.mock(RealtimeTaskPressure.class);org.mockito.Mockito.when(pressure.snapshot()).thenReturn(java.util.Map.of("control_age_sec",0d,"ai_age_sec",0d,"dead",0d));
  var control=new DialRateControl(config,inbox,jdbc,provider,pressure);
  try{control.startSampler();control.startSampler();assertThat(observed.await(3,java.util.concurrent.TimeUnit.SECONDS)).isTrue();assertThat(threadNames).containsExactly("dial-rate-sampler");}
  finally{control.stopSampler();}
 }
 @org.junit.jupiter.api.Test void slowOrMissingSamplesCannotEarnRecoveryCredit(){
  org.assertj.core.api.Assertions.assertThat(DialRateControl.recoveryElapsed(0,1_000_000_000L)).isZero();
  org.assertj.core.api.Assertions.assertThat(DialRateControl.recoveryElapsed(1,2_000_000_001L)).isZero();
  org.assertj.core.api.Assertions.assertThat(DialRateControl.recoveryElapsed(1,200_000_001L)).isEqualTo(.2);
  org.assertj.core.api.Assertions.assertThat(DialRateControl.recoveryElapsed(1,400_000_001L)).isEqualTo(.2);
 }
 @Test void ageCurveHasOneSetOfBoundariesAndHardFailuresAlwaysStop(){
  assertThat(DialRateControl.target(0,true)).isEqualTo(1);
  assertThat(DialRateControl.target(.5,true)).isEqualTo(1);
  assertThat(DialRateControl.target(3,true)).isCloseTo(.3,within(1e-9));
  assertThat(DialRateControl.target(4,true)).isCloseTo(.15,within(1e-9));
  for(double age:new double[]{0,1,4,5,Double.NaN})assertThat(DialRateControl.target(age,false)).isZero();
  assertThat(DialRateControl.target(5,true)).isZero();
 }
 @Test void recoveryIsBoundedToTenPercentPerSecondButPressureAppliesImmediately(){
  assertThat(DialRateControl.advance(0,1,.2)).isCloseTo(.02,within(1e-9));
  assertThat(DialRateControl.advance(.5,1,1)).isCloseTo(.6,within(1e-9));
  assertThat(DialRateControl.advance(1,.1,.2)).isEqualTo(.1);
  assertThat(DialRateControl.advance(.5,0,0)).isZero();
 }
}
