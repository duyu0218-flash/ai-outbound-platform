package com.duozhi.outbound.marketing.journey;

import com.duozhi.outbound.marketing.common.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Stand-ins for the dataset module (P1), which implements SnapshotMembers and ChannelSender. Only
 * test classes that import this configuration get them, in their own Spring context, so other
 * marketing tests keep seeing the "not connected" application.
 */
@TestConfiguration
public class JourneyFakes {
    public record Sent(
            long tenant,
            String channel,
            String phoneHash,
            String journeyMemberId,
            String targetId,
            Object request) {}

    /** In-memory snapshots keyed by snapshot id. */
    public static class Members implements SnapshotMembers {
        final Map<String, List<Member>> snapshots = new ConcurrentHashMap<>();

        public void put(String snapshotId, List<Member> members) {
            snapshots.put(snapshotId, List.copyOf(members));
        }

        @Override
        public List<Member> page(long tenant, String snapshotId, long afterSequence, int limit) {
            return snapshots.getOrDefault(snapshotId, List.of()).stream()
                    .filter(m -> m.sequence() > afterSequence)
                    .sorted(Comparator.comparingLong(Member::sequence))
                    .limit(limit)
                    .toList();
        }

        @Override
        public long size(long tenant, String snapshotId) {
            return snapshots.getOrDefault(snapshotId, List.of()).size();
        }
    }

    /**
     * Behaves like P1's sender: admission through the primary ContactPolicy, then a dispatch row
     * stamped with the marketing clock (so frequency windows follow the test clock).
     */
    public static class Sender implements ChannelSender {
        public final List<Sent> sent = Collections.synchronizedList(new ArrayList<>());
        private final ContactPolicy policy;
        private final PhoneIdentity phones;
        private final MarketingClock clock;
        private final JdbcTemplate jdbc;

        Sender(
                ContactPolicy policy,
                PhoneIdentity phones,
                MarketingClock clock,
                JdbcTemplate jdbc) {
            this.policy = policy;
            this.phones = phones;
            this.clock = clock;
            this.jdbc = jdbc;
        }

        @Override
        public Result sms(long tenant, Origin origin, Recipient recipient, Sms sms) {
            return send(tenant, OutcomeCodes.SMS, Attribution.MESSAGE, origin, recipient, sms);
        }

        @Override
        public Result call(long tenant, Origin origin, Recipient recipient, Call call) {
            return send(tenant, OutcomeCodes.CALL, Attribution.CALL, origin, recipient, call);
        }

        private Result send(
                long tenant,
                String channel,
                String targetType,
                Origin origin,
                Recipient recipient,
                Object request) {
            String e164 = phones.decrypt(tenant, recipient.phoneCipher());
            var admission = policy.admit(tenant, e164, recipient.phoneHash(), channel);
            if (!admission.allowed()) return new Result(false, admission.reason(), null);
            String target = channel + "-" + UUID.randomUUID();
            dispatch(
                    jdbc,
                    clock,
                    tenant,
                    channel,
                    targetType,
                    target,
                    recipient.phoneHash(),
                    origin.snapshotId(),
                    origin.journeyId(),
                    origin.journeyMemberId());
            sent.add(
                    new Sent(
                            tenant,
                            channel,
                            recipient.phoneHash(),
                            origin.journeyMemberId(),
                            target,
                            request));
            return new Result(true, "", target);
        }

        public List<Sent> of(long tenant) {
            synchronized (sent) {
                return sent.stream().filter(s -> s.tenant() == tenant).toList();
            }
        }
    }

    /** Writes an mk_dispatch row at the marketing clock's current time. */
    public static String dispatch(
            JdbcTemplate jdbc,
            MarketingClock clock,
            long tenant,
            String channel,
            String targetType,
            String target,
            String phoneHash,
            String snapshotId,
            String journeyId,
            String journeyMemberId) {
        String id = MarketingStore.id("dsp");
        jdbc.update(
                "INSERT INTO mk_dispatch(id,tenant_id,channel,target_type,target_id,phone_hash,snapshot_id,journey_id,journey_member_id,created_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                id,
                tenant,
                channel,
                targetType,
                target,
                phoneHash,
                snapshotId,
                journeyId,
                journeyMemberId,
                clock.db(clock.now()));
        return id;
    }

    @Bean
    @Primary
    Members fakeSnapshotMembers() {
        return new Members();
    }

    @Bean
    @Primary
    Sender fakeChannelSender(
            ContactPolicy policy, PhoneIdentity phones, MarketingClock clock, JdbcTemplate jdbc) {
        return new Sender(policy, phones, clock, jdbc);
    }
}
