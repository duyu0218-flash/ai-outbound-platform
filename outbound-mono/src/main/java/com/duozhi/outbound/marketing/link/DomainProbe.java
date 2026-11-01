package com.duozhi.outbound.marketing.link;

import com.duozhi.outbound.common.config.Config;
import com.duozhi.outbound.marketing.common.MarketingStore;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Health of short-link domains: DNS, certificate expiry and interception. A failing domain is
 * marked blocked (it keeps redirecting existing links but receives no new ones), administrators are
 * notified, and if its pool has no active domain left the best standby domain is promoted.
 */
@Service
public class DomainProbe {
    private static final Logger log = LoggerFactory.getLogger(DomainProbe.class);
    static final Duration CERT_WARNING = Duration.ofDays(15);

    private final MarketingStore db;
    private final DomainNetwork network;
    private final InterceptionDetector interception;
    private final TransactionTemplate tx;
    private final Config config;

    public DomainProbe(
            MarketingStore db,
            DomainNetwork network,
            InterceptionDetector interception,
            TransactionTemplate tx,
            Config config) {
        this.db = db;
        this.network = network;
        this.interception = interception;
        this.tx = tx;
        this.config = config;
    }

    public record Outcome(String id, String domain, String state, String reason) {}

    @Scheduled(
            fixedDelayString = "${MARKETING_LINK_PROBE_MS:600000}",
            initialDelayString = "${MARKETING_LINK_PROBE_MS:600000}")
    public void tick() {
        if (!config.flag("MARKETING_WORKERS_ENABLED", false)) return;
        try {
            probeDue(200);
        } catch (RuntimeException e) {
            log.warn("domain probe failed: {}", e.getClass().getSimpleName());
        }
    }

    /** One step: probes every active or standby domain not probed in the last interval. */
    public List<Outcome> probeDue(int limit) {
        long interval = Math.max(0, config.number("MARKETING_LINK_PROBE_MS", 600000) / 1000);
        // Cross-tenant scan: the worker probes every tenant's pools.
        var due =
                db.rows(
                        "SELECT tenant_id,id FROM mk_link_domain WHERE state IN ('active','standby') AND (probed_at IS NULL OR probed_at<UTC_TIMESTAMP(6)-INTERVAL ? SECOND) ORDER BY probed_at IS NOT NULL,probed_at LIMIT ?",
                        interval,
                        Math.max(1, limit));
        List<Outcome> out = new ArrayList<>();
        for (var d : due)
            out.add(probe(((Number) d.get("tenant_id")).longValue(), d.get("id").toString()));
        return out;
    }

    public Outcome probe(long tenant, String id) {
        var d =
                db.one(
                        "SELECT id,domain,state,cert_expires_at FROM mk_link_domain WHERE tenant_id=? AND id=?",
                        tenant,
                        id);
        if (d == null) return null;
        String domain = d.get("domain").toString();
        String failure = network.dnsFailure(domain), warning = null;
        Instant cert = network.certificateExpiry(domain);
        if (cert == null) cert = Links.instant(d.get("cert_expires_at"));
        if (failure == null && cert != null) {
            if (!cert.isAfter(Instant.now())) failure = "证书已过期";
            else if (cert.isBefore(Instant.now().plus(CERT_WARNING))) warning = "证书将在 15 天内过期";
        }
        if (failure == null) {
            String blocked = interception.intercepted(domain);
            if (blocked != null) failure = "被拦截：" + blocked;
        }
        String reason = failure != null ? failure : warning;
        Instant observed = cert;
        String fail = failure, warn = warning;
        return tx.execute(
                s -> {
                    var row =
                            db.one(
                                    "SELECT state,pool FROM mk_link_domain WHERE tenant_id=? AND id=? FOR UPDATE",
                                    tenant,
                                    id);
                    String state = row.get("state").toString();
                    boolean probeable = Set.of("active", "standby").contains(state);
                    if (fail != null && probeable) {
                        db.write(
                                "UPDATE mk_link_domain SET state='blocked',probe_state='failed',probe_reason=?,probed_at=UTC_TIMESTAMP(6),blocked_reason=?,blocked_at=UTC_TIMESTAMP(6),cert_expires_at=COALESCE(?,cert_expires_at) WHERE tenant_id=? AND id=?",
                                fail,
                                fail,
                                observed == null ? null : Links.utc(observed),
                                tenant,
                                id);
                        alert(tenant, id, "短链域名 " + domain + " 已停用：" + fail);
                        log.warn(
                                "short-link domain blocked tenant={} domain={} reason={}",
                                tenant,
                                domain,
                                fail);
                        if ("active".equals(state)) failover(tenant, row.get("pool").toString());
                        return new Outcome(id, domain, "blocked", fail);
                    }
                    db.write(
                            "UPDATE mk_link_domain SET probe_state=?,probe_reason=?,probed_at=UTC_TIMESTAMP(6),cert_expires_at=COALESCE(?,cert_expires_at) WHERE tenant_id=? AND id=?",
                            fail != null ? "failed" : warn != null ? "warn" : "ok",
                            reason,
                            observed == null ? null : Links.utc(observed),
                            tenant,
                            id);
                    if (warn != null && probeable)
                        alert(tenant, id + ":cert", "短链域名 " + domain + " " + warn);
                    return new Outcome(id, domain, state, reason);
                });
    }

    /** Keeps the pool serving: promotes the highest-weight healthy standby domain. */
    private void failover(long tenant, String pool) {
        if (db.count(
                        "SELECT COUNT(*) FROM mk_link_domain WHERE tenant_id=? AND pool=? AND state='active'",
                        tenant,
                        pool)
                > 0) return;
        var standby =
                db.one(
                        "SELECT id,domain FROM mk_link_domain WHERE tenant_id=? AND pool=? AND state='standby' AND COALESCE(probe_state,'ok')<>'failed' ORDER BY weight DESC,created_at LIMIT 1",
                        tenant,
                        pool);
        if (standby == null) {
            alert(tenant, "empty:" + pool, "域名池「" + pool + "」已没有可用域名，新短链将无法生成");
            return;
        }
        db.write(
                "UPDATE mk_link_domain SET state='active' WHERE tenant_id=? AND id=?",
                tenant,
                standby.get("id"));
        alert(
                tenant,
                "promote:" + standby.get("id"),
                "域名池「" + pool + "」已启用备用域名 " + standby.get("domain"));
    }

    /** Uses the operations notice centre: one notice per tenant administrator. */
    private void alert(long tenant, String key, String title) {
        String eventKey =
                Clicks.truncate(
                        "mk-link:" + key + ":" + Instant.now().getEpochSecond() / 86400, 160);
        db.write(
                "INSERT IGNORE INTO ops_notice(tenant_id,user_id,event_key,title,path) SELECT tenant_id,id,?,?,'/marketing/links' FROM sys_user WHERE tenant_id=? AND role='admin' AND enabled=TRUE",
                eventKey,
                Clicks.truncate(title, 200),
                tenant);
    }
}
