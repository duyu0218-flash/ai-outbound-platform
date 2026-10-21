package com.duozhi.outbound;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static com.duozhi.outbound.CapacityBudgetTrace.*;

class CapacityPlanAndBudgetTest {
    private final Instant base=Instant.parse("2026-10-21T09:00:00Z");
    private final Key key=new Key("tenant:1","2026-10-21","UTC:v1");
    private CapacityRunPlan plan(long allowedSeconds) {
        return new CapacityRunPlan(base,base.plusSeconds(40),base.plusSeconds(40),28800,Duration.ofSeconds(20),Duration.ofSeconds(7),List.of(new CapacityRunPlan.Window(base,base.plusSeconds(allowedSeconds))));
    }
    @Test void eightHourBusinessWindowCannotFitWarmupAndEightHourCapacity() {
        assertThat(plan(28800).errors()).isNotEmpty();assertThat(plan(32400).errors()).isEmpty();
        assertThat(CapacityRunPlan.qualified(false,true)).isFalse();assertThat(CapacityRunPlan.qualified(true,true)).isTrue();
    }
    @Test void disconnectedLegalWindowsCannotBeStitchedIntoOneCapacityRun() {
        var p=new CapacityRunPlan(base,base.plusSeconds(40),base.plusSeconds(40),28800,Duration.ofSeconds(20),Duration.ofSeconds(7),List.of(new CapacityRunPlan.Window(base,base.plusSeconds(14400)),new CapacityRunPlan.Window(base.plusSeconds(18000),base.plusSeconds(40000))));
        assertThat(p.errors()).isNotEmpty();
    }
    private Change change(String id,long seconds,long cost) {return new Change(key,id,1,base.plusSeconds(seconds),"main",new Delta(0,0,0,cost));}
    @Test void warmupRunAndTailReconcileWithoutChargingWarmupTwice() {
        var initial=Map.of(key,new Value(0,0,0,100));var t0=Map.of(key,new Value(0,0,0,120));
        var t1=Map.of(key,new Value(0,0,0,200));var end=Map.of(key,new Value(0,0,0,205));
        var changes=List.of(change("warm",0,20),change("run",10,80),change("tail",20,5));
        var result=verify(initial,changes,base,base.plusSeconds(10),base.plusSeconds(20),base.plusSeconds(30),t0,t1,end);
        assertThat(result.passed()).isTrue();assertThat(result.phaseFacts()).containsEntry("pre:main",1).containsEntry("run:main",1).containsEntry("tail:main",1);
        assertThat(verify(initial,changes,base,base.plusSeconds(10),base.plusSeconds(20),base.plusSeconds(30),t0,t1,Map.of(key,new Value(0,0,0,225))).passed()).isFalse();
    }
    @Test void duplicateFactsAndUninitializedCrossDayWindowsFail() {
        var one=change("warm",0,20);var zero=Map.of(key,new Value(0,0,0,0));
        var duplicate=verify(zero,List.of(one,one),base,base.plusSeconds(10),base.plusSeconds(20),base.plusSeconds(30),zero,zero,zero);
        assertThat(duplicate.errors()).anyMatch(e->e.startsWith("duplicate"));
        var missing=verify(Map.of(),List.of(one),base,base.plusSeconds(10),base.plusSeconds(20),base.plusSeconds(30),Map.of(),Map.of(),Map.of());
        assertThat(missing.errors()).anyMatch(e->e.startsWith("window not initialized"));
    }
    @Test void negativeLegacyDeltaRetainsEventComponent() {
        var initial=Map.of(key,new Value(3,1,0,0));var expected=Map.of(key,new Value(0,1,0,0));
        var delta=new Change(key,"rejection",2,base.plusSeconds(1),"main",new Delta(-3,0,0,0));
        var result=verify(initial,List.of(delta),base,base.plusSeconds(10),base.plusSeconds(20),base.plusSeconds(30),expected,expected,expected);
        assertThat(result.passed()).isTrue();assertThat(result.atEnd().get(key).effective()).isEqualTo(1);
    }
}
