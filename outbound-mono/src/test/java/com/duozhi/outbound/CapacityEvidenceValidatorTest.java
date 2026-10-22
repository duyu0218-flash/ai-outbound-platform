package com.duozhi.outbound;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static com.duozhi.outbound.CapacityEvidenceValidator.*;

class CapacityEvidenceValidatorTest {
    @Test void real500QualificationIsExplicitAndCannotLowerDefault650Gate() {
        var events=new ArrayList<Event>();
        for(int i=0;i<1000;i++)events.add(new Event("k"+i,i*SECOND/500,i*SECOND/500));
        assertThat(rates(events,0,2).errors()).contains("whole-window average below 650");
        var explicit=rates(events,0,2,Profile.R500);
        assertThat(explicit.profile()).isEqualTo(Profile.R500);assertThat(explicit.passed()).isTrue();
        assertThat(explicit.rates().average()).isEqualTo(500);
        assertThat(rates(events,0,2,Profile.P650).passed()).isFalse();
    }
    @Test void real500ProfileStillRejectsSlidingHolesAndDuplicateIdentities() {
        var events=new ArrayList<Event>();
        for(int i=0;i<500;i++){events.add(new Event("a"+i,0,0));events.add(new Event("b"+i,1_999_999,1_999_999));}
        var result=rates(events,0,2,Profile.R500);
        assertThat(result.rates().fixedSeconds()).containsExactly(500,500);
        assertThat(result.passed()).isFalse();assertThat(result.rates().slidingMinimum()).isZero();
        events.add(events.get(0));assertThat(rates(events,0,2,Profile.R500).rates().errors()).anyMatch(e->e.contains("duplicate"));
    }
    @Test void explicitProfileIsRequiredAndDoesNotRelaxEvidenceTimeBounds() {
        assertThatThrownBy(()->rates(List.of(),0,3600,null)).isInstanceOf(NullPointerException.class);
        var evidence=new ArrayList<Event>();
        for(int i=0;i<500;i++)evidence.add(new Event("k"+i,999_999,1_000_001));
        assertThat(rates(evidence,0,2,Profile.R500).rates().fixedSeconds()).containsExactly(0,0);
        assertThat(rates(evidence,0,2,Profile.R500).passed()).isFalse();
    }
    @Test void firstSeenPollCannotMoveARealFailureAcrossTheSecondBoundary() {
        var evidence=new ArrayList<Event>();
        for(int i=0;i<800;i++)evidence.add(new Event("a"+i,490_000,550_000));
        for(int i=0;i<100;i++)evidence.add(new Event("b"+i,960_000,1_040_000));
        for(int i=0;i<400;i++)evidence.add(new Event("c"+i,1_490_000,1_550_000));
        var result=rates(evidence,0,2);
        assertThat(result.fixedSeconds()).containsExactly(800,400);assertThat(result.passed()).isFalse();
    }
    @Test void fixedSecondsCannotHideTheGapBetweenOppositeEndsOfAdjacentSeconds() {
        var evidence=new ArrayList<Event>();
        for(int i=0;i<650;i++){evidence.add(new Event("a"+i,10_000,10_000));evidence.add(new Event("b"+i,1_990_000,1_990_000));}
        var result=rates(evidence,0,2);
        assertThat(result.fixedSeconds()).containsExactly(650,650);assertThat(result.slidingMinimum()).isZero();assertThat(result.passed()).isFalse();
    }
    @Test void regularFlowProvesTheMinimumWithoutBoundaryReassignment() {
        var events=new ArrayList<Event>();
        for(int i=0;i<1400;i++)events.add(new Event("k"+i,i*SECOND/700,i*SECOND/700));
        var result=rates(events,0,2);
        assertThat(result.fixedSeconds()).containsExactly(700,700);assertThat(result.slidingMinimum()).isEqualTo(700);assertThat(result.passed()).isTrue();
    }
    @Test void wholeDayIncludesMissingFinalSecondAndUsesTheFullDenominator() {
        var result=rates(List.of(new Event("first",0,0)),0,86400);
        assertThat(result.fixedSeconds()).hasSize(86400);assertThat(result.fixedSeconds()[86399]).isZero();
        assertThat(result.average()).isEqualTo(1.0/86400);assertThat(result.passed()).isFalse();
    }
    @Test void duplicateIdentitiesAreNotNewCalls() {
        assertThat(rates(List.of(new Event("k",0,0),new Event("k",0,0)),0,1).errors()).anyMatch(x->x.contains("duplicate"));
    }
    @Test void endpointSweepAgreesWithExhaustiveSlidingWindows() {
        var random=new Random(500);
        for(int iteration=0;iteration<300;iteration++) {
            var events=new ArrayList<Event>();
            for(int i=0;i<30;i++){long lo=random.nextInt(2_000_030)-15;events.add(new Event("k"+i,lo,lo+random.nextInt(50)));}
            var result=rates(events,0,2);
            var critical=new TreeSet<Long>(List.of(0L,SECOND));
            for(var event:events)for(long p:List.of(event.lo(),event.hi()-SECOND))for(long q=p-1;q<=p+1;q++)if(q>=0&&q<=SECOND)critical.add(q);
            long expected=critical.stream().mapToLong(a->events.stream().filter(e->e.lo()>=a&&e.hi()<a+SECOND).count()).min().orElseThrow();
            assertThat(result.slidingMinimum()).isEqualTo(expected);
        }
    }
    private Event e(String id,long t){return new Event(id,t,t);}
    @Test void incompleteMinorityCannotDisappearFromLatencyPopulation() {
        var requests=new ArrayList<Request>();
        for(int i=0;i<1000;i++){String id="k"+i;requests.add(new Request(id,e(id,0),e(id,10_000),i<995?e(id,100_000):null,null));}
        assertThat(deadlines(requests,false).passed()).isFalse();
    }
    @Test void fastMiddleStageDoesNotHideEntranceOrPbxDelay() {
        assertThat(deadlines(List.of(new Request("k",e("k",0),e("k",3*SECOND),e("k",3*SECOND+100_000),null)),false).passed()).isFalse();
        assertThat(deadlines(List.of(new Request("k",e("k",0),e("k",100_000),e("k",200_000),e("k",30*SECOND))),true).passed()).isFalse();
        assertThat(deadlines(List.of(new Request("k",e("k",0),e("k",100_000),e("k",200_000),e("k",300_000))),true).passed()).isTrue();
    }
}
