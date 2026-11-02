package com.duozhi.outbound.marketing.journey;

import com.duozhi.outbound.common.support.JsonSupport;
import com.duozhi.outbound.marketing.common.ChannelSender;
import com.duozhi.outbound.marketing.common.MarketingStore;
import com.duozhi.outbound.marketing.common.TouchEvents;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs journey members. A member is claimed when it is {@code ready} and due (conditional update
 * under FOR UPDATE SKIP LOCKED, so concurrent workers never share a member); each node then runs in
 * its own transaction together with the send it performs and its mk_journey_step row, so a crash
 * either keeps both or neither. Waiting members are woken by {@link TouchEvents.Recorded}.
 */
@Service
public class JourneyEngine {
    private static final Logger log = LoggerFactory.getLogger(JourneyEngine.class);
    static final String NOT_CONNECTED = "数据模块未接入";
    private static final Duration LEASE = Duration.ofMinutes(5);
    private static final int MAX_DEFERS = 30, MAX_FAILURES = 5, MAX_HOPS = 30;

    private final MarketingStore db;
    private final MarketingClock clock;
    private final FrequencyRules frequency;
    private final ObjectProvider<ChannelSender> senders;
    private final JsonSupport json;
    private final TransactionTemplate tx, fresh;
    private final Map<String, JourneyDefinition> versions = new ConcurrentHashMap<>();

    public JourneyEngine(
            MarketingStore db,
            MarketingClock clock,
            FrequencyRules frequency,
            ObjectProvider<ChannelSender> senders,
            JsonSupport json,
            PlatformTransactionManager manager) {
        this.db = db;
        this.clock = clock;
        this.frequency = frequency;
        this.senders = senders;
        this.json = json;
        this.tx = new TransactionTemplate(manager);
        // Node/member ownership is locked explicitly. Sends share the phone guard;
        // unrelated nodes must not retain RR gap locks before creating their targets.
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.fresh = new TransactionTemplate(manager);
        fresh.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public record Claim(String token, List<String> members) {}

    /** One worker pass: recover expired leases, claim due members, run them. */
    public int step(int limit) {
        recoverExpired();
        Claim claim = claim(limit);
        for (String member : claim.members()) run(member, claim.token());
        completeFinished();
        return claim.members().size();
    }

    /**
     * Claims up to {@code limit} due members of running journeys (ready ones, and waiting ones
     * whose condition timed out); idempotent under concurrency.
     */
    public Claim claim(int limit) {
        String token = MarketingStore.id("clm");
        LocalDateTime now = clock.appNow();
        List<String> ids =
                tx.execute(
                        s -> {
                            // Cross-tenant scan by design: the worker serves every tenant.
                            List<String> due =
                                    db
                                            .rows(
                                                    "SELECT m.id FROM mk_journey_member m JOIN mk_journey j ON j.tenant_id=m.tenant_id AND j.id=m.journey_id WHERE m.state IN ('ready','waiting') AND m.next_run_at<=? AND j.status='running' ORDER BY m.next_run_at LIMIT ? FOR UPDATE OF m SKIP LOCKED",
                                                    now,
                                                    Math.max(1, Math.min(limit, 1000)))
                                            .stream()
                                            .map(r -> (String) r.get("id"))
                                            .toList();
                            if (due.isEmpty()) return List.<String>of();
                            String marks = String.join(",", Collections.nCopies(due.size(), "?"));
                            List<Object> args = new ArrayList<>();
                            args.add(token);
                            args.add(MarketingClock.app(clock.now().plus(LEASE)));
                            args.add(now);
                            args.addAll(due);
                            db.write(
                                    "UPDATE mk_journey_member SET state='running',claim_token=?,claimed_until=?,updated_at=? WHERE state IN ('ready','waiting') AND id IN ("
                                            + marks
                                            + ")",
                                    args.toArray());
                            return db
                                    .rows(
                                            "SELECT id FROM mk_journey_member WHERE claim_token=? AND state='running' ORDER BY next_run_at,id",
                                            token)
                                    .stream()
                                    .map(r -> (String) r.get("id"))
                                    .toList();
                        });
        return new Claim(token, ids == null ? List.of() : ids);
    }

    /**
     * Leases outlive a crashed worker; the step log keeps a re-run from sending twice. Rows are
     * locked with SKIP LOCKED first, so concurrent workers never deadlock on the bulk reset.
     */
    public int recoverExpired() {
        Integer n =
                tx.execute(
                        s -> {
                            // Cross-tenant scan by design: the worker serves every tenant.
                            List<Object> ids =
                                    db
                                            .rows(
                                                    "SELECT id FROM mk_journey_member WHERE state='running' AND claimed_until<? LIMIT 1000 FOR UPDATE SKIP LOCKED",
                                                    clock.appNow())
                                            .stream()
                                            .map(r -> r.get("id"))
                                            .toList();
                            if (ids.isEmpty()) return 0;
                            return db.write(
                                    "UPDATE mk_journey_member SET state='ready',claim_token=NULL,claimed_until=NULL WHERE state='running' AND id IN ("
                                            + String.join(",", Collections.nCopies(ids.size(), "?"))
                                            + ")",
                                    ids.toArray());
                        });
        return n == null ? 0 : n;
    }

    /**
     * Members only move from active states to done or stopped, so a plain read that finds no active
     * member stays true; the journey row is then updated by its key alone.
     */
    public int completeFinished() {
        int n = 0;
        // Cross-tenant scan by design: the worker serves every tenant.
        for (Map<String, Object> j :
                db.rows(
                        "SELECT tenant_id,id FROM mk_journey j WHERE status='running' AND NOT EXISTS (SELECT 1 FROM mk_journey_member m WHERE m.tenant_id=j.tenant_id AND m.journey_id=j.id AND m.state IN ('ready','waiting','running')) LIMIT 200"))
            n +=
                    db.write(
                            "UPDATE mk_journey SET status='completed',ended_at=?,updated_at=? WHERE tenant_id=? AND id=? AND status='running'",
                            clock.appNow(),
                            clock.appNow(),
                            j.get("tenant_id"),
                            j.get("id"));
        return n;
    }

    private sealed interface Move permits Advance, Hold, Finish {}

    private record Advance(String next) implements Move {}

    private record Hold(String state, Instant at, String result, boolean deferred)
            implements Move {}

    private record Finish(String state) implements Move {}

    /** Runs a claimed member node by node until it waits, finishes, or loses its claim. */
    void run(String memberId, String token) {
        for (int hop = 0; hop < MAX_HOPS; hop++) {
            boolean more;
            try {
                more = Boolean.TRUE.equals(tx.execute(s -> hop(memberId, token)));
            } catch (RuntimeException e) {
                log.warn("journey member {} failed: {}", memberId, e.toString());
                failed(memberId, token, e);
                return;
            }
            if (!more) return;
        }
        release(memberId, token, "ready", clock.now(), null);
    }

    private boolean hop(String memberId, String token) {
        Map<String, Object> m =
                db.one(
                        "SELECT * FROM mk_journey_member WHERE id=? AND claim_token=? AND state='running' FOR UPDATE",
                        memberId,
                        token);
        if (m == null) return false;
        long tenant = ((Number) m.get("tenant_id")).longValue();
        String status =
                (String)
                        db.one(
                                        "SELECT status FROM mk_journey WHERE tenant_id=? AND id=?",
                                        tenant,
                                        m.get("journey_id"))
                                .get("status");
        if (!"running".equals(status)) {
            if ("paused".equals(status)) release(memberId, token, "ready", clock.now(), null);
            else release(memberId, token, "stopped", null, "活动已停止");
            return false;
        }
        JourneyDefinition def = definition(tenant, (String) m.get("version_id"));
        JourneyDefinition.Node node = def.node((String) m.get("node_id"));
        Move move = execute(tenant, m, node);
        Instant now = clock.now();
        switch (move) {
            case Advance a -> {
                db.write(
                        "UPDATE mk_journey_member SET node_id=?,node_entered_at=?,defer_count=0,updated_at=? WHERE id=?",
                        a.next(),
                        MarketingClock.app(now),
                        MarketingClock.app(now),
                        memberId);
                return true;
            }
            case Hold h -> {
                db.write(
                        "UPDATE mk_journey_member SET state=?,next_run_at=?,claim_token=NULL,claimed_until=NULL,last_result=?,defer_count=defer_count+?,updated_at=? WHERE id=?",
                        h.state(),
                        MarketingClock.app(h.at()),
                        h.result(),
                        h.deferred() ? 1 : 0,
                        MarketingClock.app(now),
                        memberId);
                return false;
            }
            case Finish f -> {
                release(memberId, token, f.state(), null, null);
                return false;
            }
        }
    }

    private Move execute(long tenant, Map<String, Object> m, JourneyDefinition.Node node) {
        String memberId = (String) m.get("id");
        Instant now = clock.now();
        Instant entered = MarketingClock.fromApp(FrequencyRules.local(m.get("node_entered_at")));
        switch (node.type()) {
            case "end" -> {
                step(tenant, m, node, "end", null, null);
                return new Finish("done");
            }
            case "wait" -> {
                Instant due = entered.plusSeconds((long) (node.hours() * 3600));
                if (now.isBefore(due)) return new Hold("ready", due, "等待中", false);
                step(tenant, m, node, "waited", null, null);
                return new Advance(node.next());
            }
            case "condition" -> {
                String marks = String.join(",", Collections.nCopies(node.codes().size(), "?"));
                List<Object> args = new ArrayList<>();
                args.add(tenant);
                args.add(m.get("phone_hash"));
                args.addAll(node.codes());
                args.add(m.get("created_at"));
                args.add(memberId);
                boolean matched =
                        db.count(
                                        "SELECT COUNT(*) FROM mk_touch_event WHERE tenant_id=? AND phone_hash=? AND code IN ("
                                                + marks
                                                + ") AND (occurred_at>=? OR journey_member_id=?)",
                                        args.toArray())
                                > 0;
                if (matched) {
                    step(tenant, m, node, "yes", String.join("|", node.codes()), null);
                    return new Advance(node.yes());
                }
                Instant timeout = entered.plusSeconds((long) (node.windowHours() * 3600));
                if (now.isBefore(timeout)) return new Hold("waiting", timeout, "等待结果", false);
                step(tenant, m, node, "no", "超时未满足，走默认分支", null);
                return new Advance(node.no());
            }
            default -> {
                return send(tenant, m, node, now);
            }
        }
    }

    private Move send(
            long tenant, Map<String, Object> m, JourneyDefinition.Node node, Instant now) {
        String memberId = (String) m.get("id");
        if (db.count(
                        "SELECT COUNT(*) FROM mk_journey_step WHERE member_id=? AND node_id=?",
                        memberId,
                        node.id())
                > 0) return new Advance(node.next());
        if ("control".equals(m.get("grp"))) {
            step(tenant, m, node, "control", "对照组：仅记录，不发送", null);
            return new Advance(node.next());
        }
        ChannelSender sender = senders.getIfAvailable();
        if (sender == null)
            return new Hold(
                    "ready", now.plus(Duration.ofMinutes(30)), NOT_CONNECTED + "（发送通道）", false);
        String hash = (String) m.get("phone_hash");
        int defers = ((Number) m.get("defer_count")).intValue();
        FrequencyRules.Decision d = frequency.check(tenant, hash, node.type(), now);
        if (!d.allowed()) {
            if (d.retryAt() != null && defers < MAX_DEFERS)
                return new Hold("ready", d.retryAt(), "延后：" + d.reason(), true);
            step(tenant, m, node, "denied", d.reason(), null);
            return new Advance(node.next());
        }
        var origin =
                new ChannelSender.Origin(
                        (String) m.get("snapshot_id"), (String) m.get("journey_id"), memberId);
        var recipient =
                new ChannelSender.Recipient(
                        hash,
                        (String) m.get("phone_cipher"),
                        m.get("contact_id") == null
                                ? null
                                : ((Number) m.get("contact_id")).longValue());
        ChannelSender.Result r =
                "sms".equals(node.type())
                        ? sender.sms(tenant, origin, recipient, node.smsRequest())
                        : sender.call(
                                tenant,
                                origin,
                                recipient,
                                new ChannelSender.Call(node.campaignId()));
        if (r != null && r.accepted()) step(tenant, m, node, "sent", null, r.targetId());
        else step(tenant, m, node, "denied", r == null ? "发送失败" : r.reason(), null);
        return new Advance(node.next());
    }

    private void step(
            long tenant,
            Map<String, Object> m,
            JourneyDefinition.Node node,
            String action,
            String reason,
            String target) {
        db.write(
                "INSERT IGNORE INTO mk_journey_step(tenant_id,journey_id,member_id,node_id,node_type,action,reason,target_id,created_at) VALUES(?,?,?,?,?,?,?,?,?)",
                tenant,
                m.get("journey_id"),
                m.get("id"),
                node.id(),
                node.type(),
                action,
                reason == null ? null : reason.substring(0, Math.min(255, reason.length())),
                target,
                clock.appNow());
    }

    private void release(String memberId, String token, String state, Instant at, String result) {
        db.write(
                "UPDATE mk_journey_member SET state=?,next_run_at=?,claim_token=NULL,claimed_until=NULL,last_result=COALESCE(?,last_result),updated_at=? WHERE id=? AND claim_token=?",
                state,
                at == null ? null : MarketingClock.app(at),
                result,
                clock.appNow(),
                memberId,
                token);
    }

    /** A node that threw is retried later; after repeated failures the member moves on. */
    private void failed(String memberId, String token, RuntimeException e) {
        String reason = String.valueOf(e.getMessage());
        reason = "执行失败：" + reason.substring(0, Math.min(200, reason.length()));
        String r = reason;
        tx.executeWithoutResult(
                s -> {
                    Map<String, Object> m =
                            db.one(
                                    "SELECT * FROM mk_journey_member WHERE id=? AND claim_token=? FOR UPDATE",
                                    memberId,
                                    token);
                    if (m == null) return;
                    int defers = ((Number) m.get("defer_count")).intValue();
                    if (defers + 1 >= MAX_FAILURES) {
                        long tenant = ((Number) m.get("tenant_id")).longValue();
                        JourneyDefinition.Node node =
                                definition(tenant, (String) m.get("version_id"))
                                        .node((String) m.get("node_id"));
                        step(tenant, m, node, "failed", r, null);
                        List<String> next = node.successors();
                        if (next.isEmpty()) {
                            release(memberId, token, "done", null, r);
                            return;
                        }
                        db.write(
                                "UPDATE mk_journey_member SET node_id=?,node_entered_at=?,defer_count=0,state='ready',next_run_at=?,claim_token=NULL,claimed_until=NULL,last_result=?,updated_at=? WHERE id=?",
                                "condition".equals(node.type()) ? node.no() : next.getFirst(),
                                clock.appNow(),
                                clock.appNow(),
                                r,
                                clock.appNow(),
                                memberId);
                        return;
                    }
                    db.write(
                            "UPDATE mk_journey_member SET state='ready',next_run_at=?,claim_token=NULL,claimed_until=NULL,last_result=?,defer_count=defer_count+1,updated_at=? WHERE id=?",
                            MarketingClock.app(clock.now().plus(Duration.ofMinutes(10))),
                            r,
                            clock.appNow(),
                            memberId);
                });
    }

    JourneyDefinition definition(long tenant, String versionId) {
        JourneyDefinition known = versions.get(versionId);
        if (known != null) return known;
        Map<String, Object> v =
                db.one(
                        "SELECT definition FROM mk_journey_version WHERE tenant_id=? AND id=?",
                        tenant,
                        versionId);
        if (v == null) throw new IllegalStateException("journey version missing " + versionId);
        JourneyDefinition def =
                JourneyDefinition.parse(json.object(v.get("definition").toString()));
        if (versions.size() > 1000) versions.clear();
        versions.put(versionId, def);
        return def;
    }

    /** Wakes members waiting on a condition; the condition itself re-reads mk_touch_event. */
    @EventListener
    public void on(TouchEvents.Recorded recorded) {
        try {
            fresh.executeWithoutResult(s -> wake(recorded.touch()));
        } catch (RuntimeException e) {
            log.warn("journey wake-up failed for {}: {}", recorded.id(), e.toString());
        }
    }

    int wake(TouchEvents.Touch t) {
        String member = t.dispatch() == null ? null : t.dispatch().journeyMemberId();
        if (member != null) {
            int n =
                    db.write(
                            "UPDATE mk_journey_member SET state='ready',next_run_at=?,updated_at=? WHERE tenant_id=? AND id=? AND state='waiting'",
                            clock.appNow(),
                            clock.appNow(),
                            t.tenant(),
                            member);
            if (n > 0
                    || db.count(
                                    "SELECT COUNT(*) FROM mk_journey_member WHERE tenant_id=? AND id=?",
                                    t.tenant(),
                                    member)
                            > 0) return n;
        }
        return db.write(
                "UPDATE mk_journey_member SET state='ready',next_run_at=?,updated_at=? WHERE tenant_id=? AND phone_hash=? AND state='waiting'",
                clock.appNow(),
                clock.appNow(),
                t.tenant(),
                t.phoneHash());
    }
}
