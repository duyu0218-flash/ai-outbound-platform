package com.duozhi.outbound;

import java.sql.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.assertj.core.api.Assertions.*;
import static com.duozhi.outbound.DurableQuotaCoordinator.*;

/** Real independent MySQL transactions. No Spring app, Redis, mock database,
 * live admission mode or performance claim is involved in this protocol gate. */
@Testcontainers
class DurableQuotaCoordinatorIT {
    @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4");
    static JdbcTemplate jdbc;static DriverManagerDataSource source;static DataSourceTransactionManager manager;
    DurableQuotaCoordinator first,second;Lease leader;
    @BeforeAll static void migrate(){
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(),"root",MYSQL.getPassword()).locations("classpath:db/migration").load().migrate();
        source=new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());
        manager=new DataSourceTransactionManager(source);jdbc=new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE v3_admission_fixture(identity_key VARCHAR(128) PRIMARY KEY, value INT NOT NULL) ENGINE=InnoDB");
    }
    @BeforeEach void initialize(){
        for(String t:List.of("dial_v3_quota_allocation","dial_v3_quota_grant_scope","dial_v3_quota_grant","dial_v3_quota_scope","v3_admission_fixture"))jdbc.update("DELETE FROM "+t);
        jdbc.update("UPDATE dial_v3_coordinator SET owner=NULL,epoch=0,lease_until=NULL,state='RECOVERING' WHERE id=1");
        first=new DurableQuotaCoordinator(jdbc,manager);second=new DurableQuotaCoordinator(new JdbcTemplate(source),new DataSourceTransactionManager(source));
        leader=first.acquire("coordinator-a",0,Duration.ofSeconds(60));
        first.configure(leader,"platform:0",100);first.configure(leader,"tenant:1",100);first.recover(leader);
    }
    GrantRequest request(String id,long units){return request(id,units,List.of("tenant:1","platform:0"));}
    GrantRequest request(String id,long units,List<String> scopes){
        var start=Instant.now().truncatedTo(ChronoUnit.MICROS).minusSeconds(1);
        return new GrantRequest(id,"partition:0",1,scopes,units,start,start.plusSeconds(55));
    }
    Allocation consume(String grant,String id){return first.consume(leader,grant,"partition:0",1,id,"tenant:1:call:"+id,()->jdbc.update("INSERT INTO v3_admission_fixture VALUES(?,1)",id));}
    long held(String scope){return jdbc.queryForObject("SELECT held FROM dial_v3_quota_scope WHERE scope_key=?",Long.class,scope);}
    void expireLeader(){jdbc.update("UPDATE dial_v3_coordinator SET lease_until=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE id=1");}
    Lease takeover(){expireLeader();var next=second.acquire("coordinator-b",leader.epoch(),Duration.ofSeconds(60));second.recover(next);return next;}
    @Test void recoveryKeepsIssuedButUnreportedBudgetsInsteadOfLastKnownPeak(){
        first.issue(leader,request("unreported",60));
        var next=takeover();
        assertThat(second.inspect(next)).allSatisfy(b->{assertThat(b.held()).isEqualTo(60);assertThat(b.unused()).isEqualTo(60);assertThat(b.active()).isZero();});
        assertThatThrownBy(()->second.issue(next,request("overissue",41))).isInstanceOf(ApiError.class);
        second.issue(next,request("remaining",40));assertThat(held("platform:0")).isEqualTo(100);
    }
    @Test void restartReconstructsActiveAndUnusedIdentitiesWithoutAReport(){
        first.issue(leader,request("grant",10));consume("grant","attempt-a");
        var next=takeover();
        assertThat(second.inspect(next)).allSatisfy(b->{assertThat(b.held()).isEqualTo(10);assertThat(b.unused()).isEqualTo(9);assertThat(b.active()).isEqualTo(1);});
        assertThat(second.close(next,"grant")).isEqualTo(9);
        assertThat(held("platform:0")).isEqualTo(1);
    }
    @Test void grantExpiryDoesNotReclaimLiveOrUnusedResourcesWithoutClosure(){
        first.issue(leader,request("grant",3));consume("grant","attempt-a");
        jdbc.update("UPDATE dial_v3_quota_grant SET valid_until=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE grant_id='grant'");
        assertThatThrownBy(()->consume("grant","late")).isInstanceOf(ApiError.class);
        assertThat(held("platform:0")).isEqualTo(3);
        assertThat(first.close(leader,"grant")).isEqualTo(2);assertThat(first.close(leader,"grant")).isZero();
        assertThat(held("platform:0")).isEqualTo(1);
        assertThat(first.release(leader,"attempt-a","terminal-proof",()->jdbc.update("UPDATE v3_admission_fixture SET value=2 WHERE identity_key='attempt-a'"))).isTrue();
        assertThat(held("platform:0")).isZero();
    }
    @Test void grantIdentityRetryNeverIssuesTwiceAndChangedPayloadConflicts(){
        var r=request("grant",40);first.issue(leader,r);assertThat(second.issue(leader,r).issued()).isEqualTo(40);
        assertThat(held("platform:0")).isEqualTo(40);
        assertThatThrownBy(()->second.issue(leader,request("grant",41))).isInstanceOf(ApiError.class);
        assertThat(held("tenant:1")).isEqualTo(40);
    }
    @Test void scopeTupleIsAllOrNothingIncludingUnknownOrExhaustedScope(){
        first.configure(leader,"tenant:1",1);
        assertThatThrownBy(()->first.issue(leader,request("too-many",2))).isInstanceOf(ApiError.class);
        assertThat(held("platform:0")).isZero();assertThat(held("tenant:1")).isZero();
        assertThatThrownBy(()->first.issue(leader,request("missing",1,List.of("platform:0","gateway:missing")))).isInstanceOf(ApiError.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dial_v3_quota_grant",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dial_v3_quota_grant_scope",Long.class)).isZero();
    }
    @Test void admissionFactsAndConsumptionRollbackTogether(){
        first.issue(leader,request("grant",1));
        assertThatThrownBy(()->first.consume(leader,"grant","partition:0",1,"allocation","K",()->{
            jdbc.update("INSERT INTO v3_admission_fixture VALUES('allocation',1)");throw new IllegalStateException("failure before WAL commit");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM v3_admission_fixture",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dial_v3_quota_allocation",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT consumed FROM dial_v3_quota_grant WHERE grant_id='grant'",Long.class)).isZero();
        consume("grant","allocation");assertThat(held("platform:0")).isEqualTo(1);
    }
    @Test void replayAfterClosureAndExpiryReturnsOriginalWithoutRunningAdmissionAgain(){
        first.issue(leader,request("grant",2));var initial=consume("grant","allocation");first.close(leader,"grant");
        jdbc.update("UPDATE dial_v3_quota_grant SET valid_until=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE grant_id='grant'");
        assertThat(first.consume(leader,"grant","partition:0",1,"allocation",initial.dialKey(),()->{throw new AssertionError("replayed side effect");})).isEqualTo(initial);
        assertThatThrownBy(()->consume("grant","new-allocation")).isInstanceOf(ApiError.class);
    }
    @Test void independentLeadersCannotBothAcquireAndExpiredLeaseCannotRenew(){
        assertThatThrownBy(()->second.acquire("coordinator-b",leader.epoch(),Duration.ofSeconds(60))).isInstanceOf(ApiError.class);
        expireLeader();assertThatThrownBy(()->first.renew(leader,Duration.ofSeconds(60))).isInstanceOf(ApiError.class);
        var next=second.acquire("coordinator-b",leader.epoch(),Duration.ofSeconds(60));
        assertThatThrownBy(()->second.issue(next,request("before-ready",1))).isInstanceOf(ApiError.class);
        second.recover(next);
        assertThatThrownBy(()->first.issue(leader,request("old-owner",1))).isInstanceOf(ApiError.class);
        assertThatThrownBy(()->first.recover(leader)).isInstanceOf(ApiError.class);
    }
    @Test void corruptConservationFailsClosedAndDoesNotPromoteReady(){
        first.issue(leader,request("grant",10));
        jdbc.update("UPDATE dial_v3_quota_scope SET held=9 WHERE scope_key='platform:0'");expireLeader();
        var next=second.acquire("coordinator-b",leader.epoch(),Duration.ofSeconds(60));
        assertThatThrownBy(()->second.recover(next)).isInstanceOf(ApiError.class);
        assertThat(jdbc.queryForObject("SELECT state FROM dial_v3_coordinator WHERE id=1",String.class)).isEqualTo("RECOVERING");
        assertThatThrownBy(()->second.issue(next,request("not-ready",1))).isInstanceOf(ApiError.class);
    }
    @Test void loweringLimitCannotIgnoreOutstandingGrantsOrActiveCalls(){
        first.issue(leader,request("grant",20));consume("grant","allocation");
        assertThatThrownBy(()->first.configure(leader,"platform:0",19)).isInstanceOf(ApiError.class);
        first.close(leader,"grant");first.configure(leader,"platform:0",1);
        assertThatThrownBy(()->first.configure(leader,"platform:0",0)).isInstanceOf(ApiError.class);
    }
    @Test void staleOwnerAndChangedAllocationIdentityCannotConsume(){
        first.issue(leader,request("grant",3));
        assertThatThrownBy(()->first.consume(leader,"grant","partition:0",2,"a","K",()->{})).isInstanceOf(ApiError.class);
        first.consume(leader,"grant","partition:0",1,"a","K",()->{});
        assertThatThrownBy(()->first.consume(leader,"grant","partition:0",1,"a","different-K",()->{})).isInstanceOf(ApiError.class);
        assertThat(jdbc.queryForObject("SELECT consumed FROM dial_v3_quota_grant WHERE grant_id='grant'",Long.class)).isEqualTo(1);
    }
    @Test void releaseRequiresSameProofOnReplayAndRollsBackWithTerminalFact(){
        first.issue(leader,request("grant",1));consume("grant","allocation");
        assertThatThrownBy(()->first.release(leader,"allocation","proof",()->{jdbc.update("UPDATE v3_admission_fixture SET value=2");throw new IllegalStateException("terminal rollback");})).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT value FROM v3_admission_fixture",Integer.class)).isEqualTo(1);assertThat(held("platform:0")).isEqualTo(1);
        assertThat(first.release(leader,"allocation","proof",()->{})).isTrue();
        assertThat(first.release(leader,"allocation","proof",()->{throw new AssertionError("duplicate release");})).isFalse();
        assertThatThrownBy(()->first.release(leader,"allocation","different-proof",()->{})).isInstanceOf(ApiError.class);
        assertThat(held("platform:0")).isZero();
    }
    @Test void provedReleaseAllowsNewReservationForSameKWithoutRevivingOldAllocation(){
        first.issue(leader,request("grant",2));
        first.consume(leader,"grant","partition:0",1,"old-reservation","K",()->{});
        first.release(leader,"old-reservation","no-send-proof",()->{});
        first.consume(leader,"grant","partition:0",1,"new-reservation","K",()->{});
        assertThat(first.consume(leader,"grant","partition:0",1,"old-reservation","K",()->{throw new AssertionError("revived reservation");}).state()).isEqualTo("RELEASED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dial_v3_quota_allocation WHERE dial_key='K' AND state='ACTIVE'",Long.class)).isEqualTo(1);
        assertThat(held("platform:0")).isEqualTo(1);
    }
    @Test void concurrentGrantsCannotOverissueSharedScope()throws Exception{
        first.configure(leader,"platform:0",1);var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);var successes=new AtomicInteger();
        try{
            var futures=new ArrayList<Future<?>>();
            for(int n=0;n<2;n++){int i=n;futures.add(pool.submit(()->{await(start);try{(i==0?first:second).issue(leader,request("g"+i,1));successes.incrementAndGet();}catch(ApiError expected){assertThat(expected.getMessage()).contains("quota exhausted");}}));}
            start.countDown();for(var f:futures)f.get(10,TimeUnit.SECONDS);
            assertThat(successes.get()).isEqualTo(1);assertThat(held("platform:0")).isEqualTo(1);
        }finally{start.countDown();pool.shutdownNow();}
    }
    @Test void independentGrantsCannotCreateTwoActiveReservationsForSameK()throws Exception{
        first.issue(leader,request("g0",1));first.issue(leader,request("g1",1));
        var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);var successes=new AtomicInteger();
        try{
            var futures=new ArrayList<Future<?>>();
            for(int n=0;n<2;n++){int i=n;futures.add(pool.submit(()->{await(start);try{(i==0?first:second).consume(leader,"g"+i,"partition:0",1,"a"+i,"same-K",()->jdbc.update("INSERT INTO v3_admission_fixture VALUES(?,1)","a"+i));successes.incrementAndGet();}catch(org.springframework.dao.DuplicateKeyException expected){/* database K guard rejects loser */}}));}
            start.countDown();for(var f:futures)f.get(10,TimeUnit.SECONDS);
            assertThat(successes.get()).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dial_v3_quota_allocation WHERE state='ACTIVE'",Long.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM v3_admission_fixture",Long.class)).isEqualTo(1);
            assertThat(first.inspect(leader)).allSatisfy(b->{assertThat(b.active()).isEqualTo(1);assertThat(b.unused()).isEqualTo(1);});
        }finally{start.countDown();pool.shutdownNow();}
    }
    @Test void expiryWhilePersistingAdmissionRollsBackInsteadOfAcceptingLateConsumption(){
        first.issue(leader,request("grant",1));
        assertThatThrownBy(()->first.consume(leader,"grant","partition:0",1,"a","K",()->{
            jdbc.update("INSERT INTO v3_admission_fixture VALUES('a',1)");
            jdbc.update("UPDATE dial_v3_coordinator SET lease_until=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE id=1");
        })).isInstanceOf(ApiError.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM v3_admission_fixture",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dial_v3_quota_allocation",Long.class)).isZero();
    }
    @Test void committedGrantSurvivesOuterBusinessRollback(){
        var outer=new TransactionTemplate(manager);
        outer.execute(status->{first.issue(leader,request("grant",20));status.setRollbackOnly();return null;});
        assertThat(held("platform:0")).isEqualTo(20);assertThat(second.recover(leader)).allSatisfy(b->assertThat(b.unused()).isEqualTo(20));
    }
    @Test void dailyAndCpsPoliciesCannotBeMisrepresentedAsRefundableConcurrentScopes(){
        assertThatThrownBy(()->first.configure(leader,"daily:tenant:1",100)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->first.configure(leader,"cps:platform",100)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new GrantRequest("g","partition:0",1,List.of("platform:0","platform:0"),1,Instant.EPOCH,Instant.EPOCH.plusSeconds(1))).isInstanceOf(IllegalArgumentException.class);
    }
    private static void await(CountDownLatch start){try{if(!start.await(5,TimeUnit.SECONDS))throw new AssertionError("test barrier timed out");}catch(InterruptedException error){Thread.currentThread().interrupt();throw new AssertionError(error);}}
}
