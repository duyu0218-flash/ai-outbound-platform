package com.duozhi.outbound.marketing.journey;

import com.duozhi.outbound.common.config.Config;
import com.duozhi.outbound.common.support.JsonSupport;
import com.duozhi.outbound.marketing.common.*;
import java.time.LocalDateTime;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * User touch profiles. Every stored touch fact is applied exactly once (mk_profile_applied keyed by
 * mk_touch_event.sequence): normally by the {@link TouchEvents.Recorded} listener right after
 * commit, and by the catch-up pass that walks the sequence watermark for anything the listener
 * missed (crash between commit and listener, a listener failure, a writer outside TouchEvents).
 */
@Service
public class ProfileService {
    private static final Logger log = LoggerFactory.getLogger(ProfileService.class);
    static final String CURSOR = "profile-catch-up";
    private static final String EVENT_COLUMNS =
            "sequence,id,tenant_id,phone_hash,contact_id,channel,code,source_type,source_id,occurred_at";

    private final MarketingStore db;
    private final JsonSupport json;
    private final MarketingClock clock;
    private final PolicyConfig policies;
    private final PhoneIdentity phones;
    private final TouchEvents touches;
    private final Config config;
    private final TransactionTemplate fresh;

    public ProfileService(
            MarketingStore db,
            JsonSupport json,
            MarketingClock clock,
            PolicyConfig policies,
            PhoneIdentity phones,
            TouchEvents touches,
            Config config,
            PlatformTransactionManager manager) {
        this.db = db;
        this.json = json;
        this.clock = clock;
        this.policies = policies;
        this.phones = phones;
        this.touches = touches;
        this.config = config;
        this.fresh = new TransactionTemplate(manager);
        fresh.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Runs after the recording transaction committed; needs its own transaction because the
     * committed one may still be bound to the thread.
     */
    @EventListener
    public void on(TouchEvents.Recorded recorded) {
        try {
            Map<String, Object> e =
                    db.one(
                            "SELECT " + EVENT_COLUMNS + " FROM mk_touch_event WHERE id=?",
                            recorded.id());
            if (e != null) applyInNewTransaction(e);
        } catch (RuntimeException ex) {
            // The catch-up pass will apply it.
            log.warn("profile update deferred for {}: {}", recorded.id(), ex.toString());
        }
    }

    boolean applyInNewTransaction(Map<String, Object> e) {
        return Boolean.TRUE.equals(fresh.execute(s -> apply(e)));
    }

    /** Applies one stored touch fact; false when it was applied before. */
    boolean apply(Map<String, Object> e) {
        long sequence = ((Number) e.get("sequence")).longValue();
        long tenant = ((Number) e.get("tenant_id")).longValue();
        LocalDateTime now = clock.appNow();
        if (db.write(
                        "INSERT IGNORE INTO mk_profile_applied(event_sequence,tenant_id,applied_at) VALUES(?,?,?)",
                        sequence,
                        tenant,
                        now)
                == 0) return false;
        String hash = (String) e.get("phone_hash");
        db.write(
                "INSERT IGNORE INTO mk_profile(tenant_id,phone_hash,stage,codes,created_at,updated_at) VALUES(?,?,?,'[]',?,?)",
                tenant,
                hash,
                StageRules.NEW,
                now,
                now);
        Map<String, Object> p =
                db.one(
                        "SELECT * FROM mk_profile WHERE tenant_id=? AND phone_hash=? FOR UPDATE",
                        tenant,
                        hash);
        String code = (String) e.get("code");
        String channel = (String) e.get("channel");
        LocalDateTime at = FrequencyRules.local(e.get("occurred_at"));

        int sms = num(p, "sms_count"), delivered = num(p, "sms_delivered");
        int clicks = num(p, "link_clicks"), calls = num(p, "call_count");
        int connected = num(p, "call_connected"), instant = num(p, "instant_hangups");
        int conversations = num(p, "conversations");
        boolean assistant = Boolean.TRUE.equals(bool(p.get("voice_assistant")));
        boolean converted = Boolean.TRUE.equals(bool(p.get("converted")));
        String intent = (String) p.get("best_intent");

        // One send or call per source (message / call session): only the source's earliest
        // delivery or call fact counts it, whatever order facts are applied in.
        String family = family(channel, code);
        if (family != null && firstOfSource(e, sequence, family)) {
            if (family.equals(SMS_FAMILY)) sms++;
            else calls++;
        }
        switch (code) {
            case OutcomeCodes.SMS_DELIVERED -> delivered++;
            case OutcomeCodes.LINK_CLICKED -> clicks++;
            case OutcomeCodes.CALL_ANSWERED -> connected++;
            case OutcomeCodes.ANS_INSTANT_HANGUP -> instant++;
            case OutcomeCodes.ANS_VOICE_ASSISTANT -> assistant = true;
            case OutcomeCodes.ANS_CONVERSATION -> conversations++;
            case OutcomeCodes.CONVERTED, OutcomeCodes.LINK_CONVERTED -> converted = true;
            default -> {}
        }
        if (code.startsWith("INTENT_") && (intent == null || code.compareTo(intent) < 0))
            intent = code;

        Set<String> codes = new TreeSet<>();
        Object stored = json.object("{\"c\":" + p.get("codes") + "}").get("c");
        if (stored instanceof Collection<?> c) c.forEach(x -> codes.add(String.valueOf(x)));
        codes.add(code);

        LocalDateTime first =
                p.get("first_touch_at") == null
                        ? null
                        : FrequencyRules.local(p.get("first_touch_at"));
        LocalDateTime last =
                p.get("last_touch_at") == null
                        ? null
                        : FrequencyRules.local(p.get("last_touch_at"));
        String lastChannel = (String) p.get("last_channel");
        if (first == null || at.isBefore(first)) first = at;
        if (last == null || !at.isBefore(last)) {
            last = at;
            lastChannel = channel;
        }
        String from = (String) p.get("stage");
        String stage = StageRules.evaluate(policies.get(tenant).stageRules(), codes, sms + calls);
        boolean changed = !stage.equals(from);
        db.write(
                "UPDATE mk_profile SET contact_id=COALESCE(contact_id,?),stage=?,sms_count=?,sms_delivered=?,link_clicks=?,call_count=?,call_connected=?,instant_hangups=?,conversations=?,voice_assistant=?,best_intent=?,converted=?,codes=?,first_touch_at=?,last_touch_at=?,last_channel=?,stage_changed_at=IF(?,?,stage_changed_at),updated_at=? WHERE tenant_id=? AND phone_hash=?",
                e.get("contact_id"),
                stage,
                sms,
                delivered,
                clicks,
                calls,
                connected,
                instant,
                conversations,
                assistant,
                intent,
                converted,
                json.json(codes),
                first,
                last,
                lastChannel,
                changed,
                now,
                now,
                tenant,
                hash);
        if (changed)
            db.write(
                    "INSERT INTO mk_profile_history(tenant_id,phone_hash,from_stage,to_stage,code,event_id,created_at) VALUES(?,?,?,?,?,?,?)",
                    tenant,
                    hash,
                    from,
                    stage,
                    code,
                    e.get("id"),
                    now);
        return true;
    }

    private static final String SMS_FAMILY = "^SMS_", CALL_FAMILY = "^(CALL|ANS|INTENT)_";

    private static String family(String channel, String code) {
        if (OutcomeCodes.SMS.equals(channel) && code.startsWith("SMS_")) return SMS_FAMILY;
        if (OutcomeCodes.CALL.equals(channel)
                && (code.startsWith("CALL_")
                        || code.startsWith("ANS_")
                        || code.startsWith("INTENT_"))) return CALL_FAMILY;
        return null;
    }

    private boolean firstOfSource(Map<String, Object> e, long sequence, String family) {
        return db.count(
                        "SELECT COUNT(*) FROM mk_touch_event WHERE tenant_id=? AND source_type=? AND source_id=? AND channel=? AND sequence<? AND REGEXP_LIKE(code,?,'c')",
                        e.get("tenant_id"),
                        e.get("source_type"),
                        e.get("source_id"),
                        e.get("channel"),
                        sequence,
                        family)
                == 0;
    }

    public record CatchUp(int scanned, int applied, long watermark) {}

    /**
     * Walks mk_touch_event past the watermark and applies anything not yet applied. The watermark
     * only moves over facts older than the settle delay, so a fact whose sequence was allocated
     * before a slower transaction committed is still seen on the next pass.
     */
    public CatchUp catchUp(int pageSize) {
        db.write(
                "INSERT IGNORE INTO mk_worker_cursor(name,position,updated_at) VALUES(?,0,?)",
                CURSOR,
                clock.appNow());
        long watermark = db.count("SELECT position FROM mk_worker_cursor WHERE name=?", CURSOR);
        int settle = config.number("MARKETING_PROFILE_SETTLE_SECONDS", 120);
        long after = watermark, next = watermark;
        boolean contiguous = true;
        int scanned = 0, applied = 0;
        for (int page = 0; page < 50; page++) {
            // Cross-tenant scan by design: the catch-up job serves every tenant.
            List<Map<String, Object>> rows =
                    db.rows(
                            "SELECT "
                                    + EVENT_COLUMNS
                                    + ",created_at<NOW(6)-INTERVAL ? SECOND settled FROM mk_touch_event WHERE sequence>? ORDER BY sequence LIMIT ?",
                            settle,
                            after,
                            Math.max(1, Math.min(pageSize, 2000)));
            if (rows.isEmpty()) break;
            for (Map<String, Object> e : rows) {
                scanned++;
                long sequence = ((Number) e.get("sequence")).longValue();
                after = sequence;
                try {
                    if (applyInNewTransaction(e)) applied++;
                } catch (RuntimeException ex) {
                    log.warn("profile catch-up failed at {}: {}", sequence, ex.toString());
                    contiguous = false;
                }
                if (contiguous && Boolean.TRUE.equals(bool(e.get("settled")))) next = sequence;
                else contiguous = false;
            }
            if (rows.size() < pageSize) break;
        }
        if (next > watermark)
            db.write(
                    "UPDATE mk_worker_cursor SET position=GREATEST(position,?),updated_at=? WHERE name=?",
                    next,
                    clock.appNow(),
                    CURSOR);
        return new CatchUp(scanned, applied, Math.max(next, watermark));
    }

    /** Looks a phone up by its hash; the number itself never leaves the request body. */
    public Map<String, Object> lookup(long tenant, String phone) {
        PhoneIdentity.Phone p = phones.of(tenant, phone);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mask", p.mask());
        Map<String, Object> profile =
                db.one(
                        "SELECT contact_id,stage,sms_count,sms_delivered,link_clicks,call_count,call_connected,instant_hangups,conversations,voice_assistant,best_intent,converted,codes,first_touch_at,last_touch_at,last_channel,stage_changed_at,updated_at FROM mk_profile WHERE tenant_id=? AND phone_hash=?",
                        tenant,
                        p.hash());
        if (profile != null) {
            profile = new LinkedHashMap<>(profile);
            profile.put("codes", json.object("{\"c\":" + profile.get("codes") + "}").get("c"));
        }
        out.put("exists", profile != null);
        out.put("profile", profile);
        out.put(
                "history",
                db.rows(
                        "SELECT from_stage,to_stage,code,created_at FROM mk_profile_history WHERE tenant_id=? AND phone_hash=? ORDER BY id DESC LIMIT 100",
                        tenant,
                        p.hash()));
        List<Map<String, Object>> timeline = new ArrayList<>();
        for (Map<String, Object> t : touches.history(tenant, p.hash(), 200)) {
            Map<String, Object> row = new LinkedHashMap<>(t);
            if (row.get("detail") != null)
                row.put("detail", json.object(row.get("detail").toString()));
            timeline.add(row);
        }
        out.put("timeline", timeline);
        out.put(
                "journeys",
                db.rows(
                        "SELECT j.id,j.name,j.status,m.grp,m.node_id,m.state,m.created_at FROM mk_journey_member m JOIN mk_journey j ON j.tenant_id=m.tenant_id AND j.id=m.journey_id WHERE m.tenant_id=? AND m.phone_hash=? ORDER BY m.created_at DESC LIMIT 50",
                        tenant,
                        p.hash()));
        return out;
    }

    public Map<String, Object> overview(long tenant) {
        Map<String, Long> stages = new LinkedHashMap<>();
        for (String s : StageRules.STAGES) stages.put(s, 0L);
        for (Map<String, Object> r :
                db.rows(
                        "SELECT stage,COUNT(*) n FROM mk_profile WHERE tenant_id=? GROUP BY stage",
                        tenant))
            stages.put((String) r.get("stage"), ((Number) r.get("n")).longValue());
        return Map.of(
                "stages",
                stages,
                "recent",
                db.rows(
                        "SELECT LEFT(phone_hash,8) phone_ref,from_stage,to_stage,code,created_at FROM mk_profile_history WHERE tenant_id=? ORDER BY id DESC LIMIT 30",
                        tenant),
                "rules",
                policies.get(tenant).stageRules());
    }

    private static int num(Map<String, Object> p, String key) {
        Object v = p.get(key);
        return v == null ? 0 : ((Number) v).intValue();
    }

    private static Boolean bool(Object v) {
        if (v == null) return null;
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.intValue() != 0;
        return Boolean.parseBoolean(v.toString());
    }
}
