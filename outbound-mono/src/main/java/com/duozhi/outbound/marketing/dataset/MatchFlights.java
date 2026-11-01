package com.duozhi.outbound.marketing.dataset;

import com.duozhi.outbound.marketing.common.MarketingStore;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Database-enforced single flight: no process-local mutex or expiring send lease. */
@Service
public class MatchFlights {
    private final MarketingStore db;
    private final TransactionTemplate tx;

    public MatchFlights(MarketingStore db, TransactionTemplate tx) {
        this.db = db;
        this.tx = tx;
    }

    private Object[] key(Map<String, Object> job, Map<String, Object> request) {
        return new Object[] {
            job.get("tenant_id"),
            job.get("partner_id"),
            job.get("product"),
            request.get("phone_hash")
        };
    }

    private Map<String, Object> lock(Map<String, Object> job, Map<String, Object> request) {
        Object[] k = key(job, request);
        db.write(
                "INSERT INTO mk_match_flight(tenant_id,partner_id,product,phone_hash,owner_request_id,state) VALUES(?,?,?,?,?,'ready') ON DUPLICATE KEY UPDATE owner_request_id=owner_request_id",
                k[0],
                k[1],
                k[2],
                k[3],
                request.get("id"));
        return db.one(
                "SELECT *, expires_at<=NOW(6) AS expired FROM mk_match_flight WHERE tenant_id=? AND partner_id=? AND product=? AND phone_hash=? FOR UPDATE",
                k);
    }

    /**
     * Commits the shared claim AND pending request before granting permission to use the network.
     */
    public boolean claim(Map<String, Object> job, Map<String, Object> request) {
        return Boolean.TRUE.equals(
                tx.execute(
                        s -> {
                            var flight = lock(job, request);
                            var current =
                                    db.one(
                                            "SELECT * FROM mk_match_request WHERE tenant_id=? AND id=? FOR UPDATE",
                                            job.get("tenant_id"),
                                            request.get("id"));
                            if (current == null
                                    || !Set.of("reserved", "waiting")
                                            .contains(current.get("state"))) return false;
                            boolean waiting = "waiting".equals(current.get("state"));
                            String owner =
                                    waiting && current.get("shared_request_id") != null
                                            ? current.get("shared_request_id").toString()
                                            : flight.get("owner_request_id").toString();
                            var original =
                                    db.one(
                                            "SELECT * FROM mk_match_request WHERE tenant_id=? AND id=?",
                                            job.get("tenant_id"),
                                            owner);
                            // A waiter consumes the exact owner's answer even after a long offline
                            // interval.
                            if (waiting
                                    && original != null
                                    && Set.of("success", "no_data")
                                            .contains(original.get("state"))) {
                                reuse(current, original, owner, "复用同一查询结果，未再次调用或计费。");
                                return false;
                            }
                            var cached =
                                    db.one(
                                            "SELECT * FROM mk_match_result WHERE tenant_id=? AND partner_id=? AND product=? AND phone_hash=? AND expires_at>NOW(6)",
                                            key(job, request));
                            if (cached != null) {
                                reuse(
                                        current,
                                        cached,
                                        owner.equals(current.get("id")) ? null : owner,
                                        "复用 30 天内结果，未再次计费。");
                                return false;
                            }
                            if ("pending".equals(flight.get("state"))) {
                                int stale =
                                        db.write(
                                                "UPDATE mk_match_request SET state='uncertain',code='PENDING_REVIEW',message='原调用结果待核对，未重复发送。' WHERE tenant_id=? AND id=? AND state='pending' AND sent_at<NOW(6)-INTERVAL 5 MINUTE",
                                                job.get("tenant_id"),
                                                owner);
                                if (stale > 0
                                        || original != null
                                                && Set.of("failed", "uncertain")
                                                        .contains(original.get("state"))) {
                                    db.write(
                                            "UPDATE mk_match_flight SET state='uncertain',updated_at=NOW(6) WHERE owner_request_id=?",
                                            owner);
                                    flight.put("state", "uncertain");
                                }
                            }
                            boolean expired =
                                    Boolean.TRUE.equals(flight.get("expired"))
                                            || flight.get("expired") instanceof Number n
                                                    && n.intValue() == 1;
                            if (!waiting
                                    && ("ready".equals(flight.get("state"))
                                            || Set.of("success", "no_data")
                                                            .contains(flight.get("state"))
                                                    && expired)) {
                                db.write(
                                        "UPDATE mk_match_flight SET owner_request_id=?,state='pending',expires_at=NULL,updated_at=NOW(6) WHERE tenant_id=? AND partner_id=? AND product=? AND phone_hash=?",
                                        current.get("id"),
                                        job.get("tenant_id"),
                                        job.get("partner_id"),
                                        job.get("product"),
                                        request.get("phone_hash"));
                                int changed =
                                        db.write(
                                                "UPDATE mk_match_request SET state='pending',charged=TRUE,code='PENDING',shared_request_id=NULL,sent_at=NOW(6) WHERE tenant_id=? AND id=? AND state='reserved'",
                                                job.get("tenant_id"),
                                                current.get("id"));
                                if (changed != 1)
                                    throw new IllegalStateException("match claim lost");
                                return true;
                            }
                            boolean active = "pending".equals(flight.get("state"));
                            db.write(
                                    "UPDATE mk_match_request SET state=?,code=?,message=?,shared_request_id=?,charged=FALSE,finished_at=IF(?,NULL,NOW(6)) WHERE tenant_id=? AND id=? AND state IN ('reserved','waiting')",
                                    active ? "waiting" : "uncertain",
                                    active ? "SHARED_WAIT" : "SHARED_REVIEW",
                                    active
                                            ? "已有任务正在查询此号码，等待共享结果，未重复调用。"
                                            : "该号码已有调用结果待核对，已阻止重复调用；请导入核对后的已有结果。",
                                    owner,
                                    active,
                                    job.get("tenant_id"),
                                    current.get("id"));
                            return false;
                        }));
    }

    private void reuse(
            Map<String, Object> request, Map<String, Object> answer, String owner, String message) {
        db.write(
                "UPDATE mk_match_request SET state='cached',code='CACHED',message=?,request_id=?,hit=?,score=?,grade=?,best_hours=?,labels=?,shared_request_id=?,charged=FALSE,finished_at=NOW(6) WHERE tenant_id=? AND id=? AND state IN ('reserved','waiting')",
                message,
                answer.get("request_id"),
                answer.get("hit"),
                answer.get("score"),
                answer.get("grade"),
                answer.get("best_hours"),
                answer.get("labels"),
                owner,
                request.get("tenant_id"),
                request.get("id"));
    }

    public void refresh(Map<String, Object> job) {
        for (var request :
                db.rows(
                        "SELECT * FROM mk_match_request WHERE tenant_id=? AND job_id=? AND state='waiting' ORDER BY position LIMIT 1000",
                        job.get("tenant_id"),
                        job.get("id"))) claim(job, request);
    }

    /** Called inside the result transaction, before updating the owner's request/cache. */
    public void lockOwner(String requestId) {
        db.one(
                "SELECT owner_request_id FROM mk_match_flight WHERE owner_request_id=? FOR UPDATE",
                requestId);
    }

    public void complete(String requestId, String state) {
        db.write(
                "UPDATE mk_match_flight SET state=?,expires_at=IF(? IN ('success','no_data'),NOW(6)+INTERVAL 30 DAY,NULL),updated_at=NOW(6) WHERE owner_request_id=?",
                state,
                state,
                requestId);
    }

    /** An imported, verified answer can reconcile a blocked flight without another network call. */
    public void reconcile(
            long tenant,
            String partner,
            String product,
            String hash,
            String requestId,
            String state) {
        db.write(
                "UPDATE mk_match_flight SET owner_request_id=?,state=?,expires_at=NOW(6)+INTERVAL 30 DAY,updated_at=NOW(6) WHERE tenant_id=? AND partner_id=? AND product=? AND phone_hash=? AND state IN ('uncertain','failed')",
                requestId,
                state,
                tenant,
                partner,
                product,
                hash);
    }
}
