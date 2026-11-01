package com.duozhi.outbound.marketing.link;

import com.duozhi.outbound.common.config.Config;
import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.marketing.common.*;
import com.duozhi.outbound.messaging.service.MessageSecrets;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.web.bind.annotation.*;

/** Operator API for domains, landing whitelist, link performance and conversion postbacks. */
@RestController
@RequestMapping("/api/v1/marketing/links")
public class LinkAdmin {
    static final Set<String> STATES = Set.of("active", "standby", "blocked", "disabled");

    private final MarketingAccess access;
    private final MarketingStore db;
    private final Links links;
    private final Clicks clicks;
    private final DomainProbe probe;
    private final TouchEvents touches;
    private final Attribution attribution;
    private final MessageSecrets secrets;
    private final Config config;

    public LinkAdmin(
            MarketingAccess access,
            MarketingStore db,
            Links links,
            Clicks clicks,
            DomainProbe probe,
            TouchEvents touches,
            Attribution attribution,
            MessageSecrets secrets,
            Config config) {
        this.access = access;
        this.db = db;
        this.links = links;
        this.clicks = clicks;
        this.probe = probe;
        this.touches = touches;
        this.attribution = attribution;
        this.secrets = secrets;
        this.config = config;
    }

    private long tenant(HttpServletRequest request) {
        return access.operator(request).tenant();
    }

    @GetMapping("/overview")
    public Map<String, Object> overview(HttpServletRequest request) {
        long t = tenant(request);
        var out = new LinkedHashMap<String, Object>();
        out.put(
                "pools",
                db.rows(
                        "SELECT pool,SUM(state='active') AS active,SUM(state='standby') AS standby,SUM(state='blocked') AS blocked,SUM(state='disabled') AS disabled FROM mk_link_domain WHERE tenant_id=? GROUP BY pool ORDER BY pool",
                        t));
        out.put("click_queue", clicks.stats());
        out.put("probe_mode", config.get("MARKETING_LINK_PROBE", "mock"));
        out.put("interception_mode", config.get("MARKETING_LINK_INTERCEPTION", "mock"));
        out.put("workers_enabled", config.flag("MARKETING_WORKERS_ENABLED", false));
        return out;
    }

    // ---- domain pool ----

    @GetMapping("/domains")
    public List<Map<String, Object>> domains(HttpServletRequest request) {
        long t = tenant(request);
        return db.rows(
                "SELECT d.*,(SELECT COUNT(*) FROM mk_short_link l WHERE l.domain_id=d.id AND l.tenant_id=d.tenant_id) AS link_count FROM mk_link_domain d WHERE d.tenant_id=? ORDER BY d.pool,d.state,d.weight DESC,d.domain",
                t);
    }

    @PostMapping("/domains")
    public Map<String, Object> createDomain(
            @RequestBody
            Map<String, Object> body, HttpServletRequest request) {
        long t = tenant(request);
        String domain = Links.domain(text(body, "domain", 253));
        String id = MarketingStore.id("lnd");
        try {
            db.write(
                    "INSERT INTO mk_link_domain(id,tenant_id,domain,pool,icp_number,cert_expires_at,state,weight) VALUES(?,?,?,?,?,?,?,?)",
                    id,
                    t,
                    domain,
                    Links.pool(text(body, "pool", 64)),
                    text(body, "icp_number", 64),
                    date(body.get("cert_expires_at")),
                    state(body.getOrDefault("state", "standby")),
                    weight(body.getOrDefault("weight", 100)));
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new ApiError(409, "域名已存在");
        }
        return domain(t, id);
    }

    @PutMapping("/domains/{id}")
    public Map<String, Object> updateDomain(
            @PathVariable
            String id,
            @RequestBody
            Map<String, Object> body,
            HttpServletRequest request) {
        long t = tenant(request);
        var current = domain(t, id);
        String state =
                body.containsKey("state")
                        ? state(body.get("state"))
                        : current.get("state").toString();
        db.write(
                "UPDATE mk_link_domain SET pool=?,icp_number=?,cert_expires_at=?,state=?,weight=?,blocked_reason=IF(?='blocked',blocked_reason,NULL) WHERE tenant_id=? AND id=?",
                body.containsKey("pool") ? Links.pool(text(body, "pool", 64)) : current.get("pool"),
                body.containsKey("icp_number")
                        ? text(body, "icp_number", 64)
                        : current.get("icp_number"),
                body.containsKey("cert_expires_at")
                        ? date(body.get("cert_expires_at"))
                        : current.get("cert_expires_at"),
                state,
                body.containsKey("weight") ? weight(body.get("weight")) : current.get("weight"),
                state,
                t,
                id);
        return domain(t, id);
    }

    @PostMapping("/domains/{id}/probe")
    public Map<String, Object> probeDomain(@PathVariable
    String id, HttpServletRequest request) {
        long t = tenant(request);
        domain(t, id);
        probe.probe(t, id);
        return domain(t, id);
    }

    private Map<String, Object> domain(long tenant, String id) {
        var d = db.one("SELECT * FROM mk_link_domain WHERE tenant_id=? AND id=?", tenant, id);
        if (d == null) throw new ApiError(404, "域名不存在");
        return d;
    }

    // ---- landing whitelist ----

    @GetMapping("/landing-domains")
    public List<Map<String, Object>> landing(HttpServletRequest request) {
        return db.rows(
                "SELECT domain,note,created_at FROM mk_landing_domain WHERE tenant_id=? ORDER BY domain",
                tenant(request));
    }

    @PostMapping("/landing-domains")
    public Map<String, Object> addLanding(
            @RequestBody
            Map<String, Object> body, HttpServletRequest request) {
        var identity = access.operator(request);
        String raw = Objects.toString(body.get("domain"), "").trim().toLowerCase(Locale.ROOT);
        String domain =
                raw.startsWith("*.") ? "*." + Links.domain(raw.substring(2)) : Links.domain(raw);
        db.write(
                "INSERT INTO mk_landing_domain(tenant_id,domain,note,created_by) VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE note=VALUES(note)",
                identity.tenant(),
                domain,
                text(body, "note", 255),
                access.actor(identity));
        return Map.of("domain", domain);
    }

    @DeleteMapping("/landing-domains")
    public Map<String, Object> removeLanding(
            @RequestParam
            String domain, HttpServletRequest request) {
        int removed =
                db.write(
                        "DELETE FROM mk_landing_domain WHERE tenant_id=? AND domain=?",
                        tenant(request),
                        domain);
        return Map.of("removed", removed > 0);
    }

    // ---- delivery status mapping ----

    @GetMapping("/status-map")
    public List<Map<String, Object>> statusMap(HttpServletRequest request) {
        return db.rows(
                "SELECT tenant_id<>0 AS custom,provider,status_code,outcome,note FROM mk_sms_status_map WHERE tenant_id IN (0,?) ORDER BY tenant_id DESC,provider,status_code",
                tenant(request));
    }

    @PutMapping("/status-map")
    public Map<String, Object> putStatus(
            @RequestBody
            Map<String, Object> body, HttpServletRequest request) {
        long t = tenant(request);
        String outcome = text(body, "outcome", 48);
        if (outcome == null || !outcome.startsWith("SMS_") || !OutcomeCodes.ALL.contains(outcome))
            throw new ApiError(422, "结果码必须是短信类结果码");
        String code = text(body, "status_code", 64);
        if (code == null) throw new ApiError(422, "状态码不能为空");
        String provider = Objects.requireNonNullElse(text(body, "provider", 32), "*");
        db.write(
                "INSERT INTO mk_sms_status_map(tenant_id,provider,status_code,outcome,note) VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE outcome=VALUES(outcome),note=VALUES(note)",
                t,
                provider,
                code,
                outcome,
                text(body, "note", 255));
        return Map.of("provider", provider, "status_code", code, "outcome", outcome);
    }

    @DeleteMapping("/status-map")
    public Map<String, Object> deleteStatus(
            @RequestParam
            String provider,
            @RequestParam("status_code")
            String code,
            HttpServletRequest request) {
        int removed =
                db.write(
                        "DELETE FROM mk_sms_status_map WHERE tenant_id=? AND provider=? AND status_code=?",
                        tenant(request),
                        provider,
                        code);
        return Map.of("removed", removed > 0);
    }

    // ---- performance and click details ----

    @GetMapping("/performance")
    public List<Map<String, Object>> performance(
            @RequestParam(defaultValue = "campaign")
            String dimension,
            @RequestParam(defaultValue = "30")
            int days,
            HttpServletRequest request) {
        long t = tenant(request);
        String key =
                switch (dimension) {
                    case "campaign" -> "COALESCE(m.campaign_id,l.campaign_id)";
                    case "snapshot" -> "l.snapshot_id";
                    case "template" -> "m.template_id";
                    default -> throw new ApiError(422, "维度无效");
                };
        var since =
                Links.utc(Instant.now().minus(Math.max(1, Math.min(days, 180)), ChronoUnit.DAYS));
        var rows =
                db.rows(
                        "SELECT "
                                + key
                                + " AS dimension_key,COUNT(*) AS links,SUM(l.message_id IS NOT NULL) AS sent,SUM(m.state='DELIVERED') AS delivered,COALESCE(SUM(c.valid),0) AS valid_clicks,COALESCE(SUM(c.bot),0) AS bot_clicks,COALESCE(SUM(c.valid>0),0) AS clicked_links FROM mk_short_link l LEFT JOIN msg_message m ON m.tenant_id=l.tenant_id AND m.id=l.message_id LEFT JOIN (SELECT code,SUM(verdict='valid') AS valid,SUM(verdict='bot') AS bot FROM mk_link_click WHERE tenant_id=? AND clicked_at>=? GROUP BY code) c ON c.code=l.code WHERE l.tenant_id=? AND l.kind='personal' AND l.created_at>=? GROUP BY dimension_key ORDER BY sent DESC,links DESC LIMIT 200",
                        t,
                        since,
                        t,
                        since);
        for (var r : rows) {
            long delivered = number(r.get("delivered")), sent = number(r.get("sent"));
            long base = delivered > 0 ? delivered : sent;
            r.put(
                    "click_rate",
                    base == 0
                            ? 0
                            : Math.round(number(r.get("clicked_links")) * 10000.0 / base) / 100.0);
        }
        return rows;
    }

    @GetMapping("/clicks")
    public List<Map<String, Object>> clickDetails(
            @RequestParam(required = false)
            String code,
            @RequestParam(required = false)
            String verdict,
            @RequestParam(defaultValue = "200")
            int limit,
            HttpServletRequest request) {
        long t = tenant(request);
        var sql =
                new StringBuilder(
                        "SELECT c.id,c.code,c.clicked_at,c.verdict,c.reason,c.ip_prefix,c.user_agent,c.wechat,l.kind,l.domain,l.snapshot_id,l.message_id,m.recipient_cipher,m.recipient_mask FROM mk_link_click c JOIN mk_short_link l ON l.code=c.code AND l.tenant_id=c.tenant_id LEFT JOIN msg_message m ON m.tenant_id=l.tenant_id AND m.id=l.message_id WHERE c.tenant_id=?");
        List<Object> args = new ArrayList<>(List.of(t));
        if (code != null && !code.isBlank()) {
            sql.append(" AND c.code=?");
            args.add(code);
        }
        if (verdict != null && !verdict.isBlank()) {
            sql.append(" AND c.verdict=?");
            args.add(verdict);
        }
        sql.append(" ORDER BY c.id DESC LIMIT ?");
        args.add(Math.max(1, Math.min(limit, 500)));
        var rows = db.rows(sql.toString(), args.toArray());
        for (var r : rows) {
            Object cipher = r.remove("recipient_cipher"), mask = r.remove("recipient_mask");
            r.put("phone", masked(t, r.get("message_id"), cipher, mask));
        }
        return rows;
    }

    private String masked(long tenant, Object messageId, Object cipher, Object fallback) {
        if (cipher == null) return "-";
        try {
            return PhoneIdentity.mask(
                    secrets.decrypt("recipient:" + tenant + ":" + messageId, cipher.toString()));
        } catch (RuntimeException e) {
            return fallback == null ? "-" : fallback.toString();
        }
    }

    // ---- campaign public links ----

    @GetMapping("/public")
    public List<Map<String, Object>> publicLinks(HttpServletRequest request) {
        long t = tenant(request);
        var rows =
                db.rows(
                        "SELECT l.code,l.name,l.campaign_id,l.domain,l.target_url,l.expires_at,l.created_at,(SELECT COUNT(*) FROM mk_link_click c WHERE c.tenant_id=l.tenant_id AND c.code=l.code AND c.verdict<>'bot') AS pv,(SELECT COUNT(DISTINCT visitor_hash) FROM mk_link_click c WHERE c.tenant_id=l.tenant_id AND c.code=l.code AND c.verdict<>'bot') AS uv FROM mk_short_link l WHERE l.tenant_id=? AND l.kind='public' ORDER BY l.created_at DESC LIMIT 200",
                        t);
        for (var r : rows)
            r.put("url", links.url(r.get("domain").toString(), r.get("code").toString()));
        return rows;
    }

    @PostMapping("/public")
    public LinkMinter.ShortLink createPublic(
            @RequestBody
            Map<String, Object> body, HttpServletRequest request) {
        long t = tenant(request);
        Object expires = body.get("expires_at");
        Instant expiresAt =
                expires == null || expires.toString().isBlank()
                        ? Instant.now().plus(30, ChronoUnit.DAYS)
                        : parseInstant(expires.toString());
        return links.mintPublic(
                t,
                text(body, "pool", 64),
                text(body, "landing_url", 2048),
                text(body, "campaign_id", 64),
                text(body, "name", 128),
                expiresAt);
    }

    // ---- conversion postback (admin:marketing) ----

    @PostMapping("/conversions")
    public Map<String, Object> conversion(
            @RequestBody
            Map<String, Object> body, HttpServletRequest request) {
        long t = tenant(request);
        String code = text(body, "code", 7);
        var link =
                code == null
                        ? null
                        : db.one(
                                "SELECT * FROM mk_short_link WHERE tenant_id=? AND code=?",
                                t,
                                code);
        if (link == null) throw new ApiError(404, "短链不存在");
        if (!"personal".equals(link.get("kind")) || link.get("phone_hash") == null)
            throw new ApiError(422, "活动公共链接不记录个人转化");
        String messageId = (String) link.get("message_id");
        var dispatch =
                messageId == null ? null : attribution.find(t, Attribution.MESSAGE, messageId);
        if (dispatch == null)
            dispatch =
                    new Attribution.Dispatch(
                            null,
                            t,
                            OutcomeCodes.SMS,
                            "mk_short_link",
                            code,
                            link.get("phone_hash").toString(),
                            link.get("contact_id") == null
                                    ? null
                                    : ((Number) link.get("contact_id")).longValue(),
                            (String) link.get("snapshot_id"),
                            (String) link.get("journey_id"),
                            (String) link.get("journey_member_id"));
        var detail = new LinkedHashMap<String, Object>();
        detail.put("code", code);
        if (body.get("event") != null) detail.put("event", text(body, "event", 64));
        if (body.get("value") != null) detail.put("value", text(body, "value", 64));
        String id =
                touches.record(
                        new TouchEvents.Touch(
                                t,
                                link.get("phone_hash").toString(),
                                dispatch.contactId(),
                                OutcomeCodes.LINK,
                                OutcomeCodes.LINK_CONVERTED,
                                "mk_short_link",
                                code,
                                dispatch,
                                detail,
                                Links.utc(Instant.now())));
        return Map.of("recorded", id != null);
    }

    // ---- input helpers ----

    static String text(Map<String, Object> body, String key, int max) {
        Object v = body.get(key);
        if (v == null) return null;
        String s = v.toString().trim();
        if (s.isEmpty()) return null;
        if (s.length() > max) throw new ApiError(422, key + " 过长");
        return s;
    }

    static String state(Object value) {
        String s = Objects.toString(value, "");
        if (!STATES.contains(s)) throw new ApiError(422, "域名状态无效");
        return s;
    }

    static int weight(Object value) {
        try {
            int w = Integer.parseInt(value.toString());
            if (w < 0 || w > 10000) throw new NumberFormatException();
            return w;
        } catch (NumberFormatException e) {
            throw new ApiError(422, "权重应为 0 至 10000 的整数");
        }
    }

    static Object date(Object value) {
        if (value == null || value.toString().isBlank()) return null;
        String s = value.toString().trim();
        try {
            if (s.matches("\\d{4}-\\d{2}-\\d{2}")) return LocalDate.parse(s).atStartOfDay();
            return Links.utc(parseInstant(s));
        } catch (ApiError e) {
            throw new ApiError(422, "证书到期时间格式无效");
        } catch (RuntimeException e) {
            throw new ApiError(422, "证书到期时间格式无效");
        }
    }

    static Instant parseInstant(String s) {
        try {
            if (s.matches("\\d{4}-\\d{2}-\\d{2}"))
                return LocalDate.parse(s).atStartOfDay().toInstant(ZoneOffset.UTC);
            return Instant.parse(s);
        } catch (RuntimeException e) {
            throw new ApiError(422, "时间格式无效");
        }
    }

    static long number(Object v) {
        return v == null ? 0 : ((Number) v).longValue();
    }
}
