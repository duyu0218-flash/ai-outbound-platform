package com.duozhi.outbound.marketing.link;

import com.duozhi.outbound.common.config.Config;
import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.marketing.common.ChannelSender;
import com.duozhi.outbound.marketing.common.LinkMinter;
import com.duozhi.outbound.marketing.common.MarketingStore;
import java.net.URI;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * Minting and resolution of short links. Codes are 7 random Base62 characters from SecureRandom
 * (62^7 ≈ 3.5e12 values), so they cannot be enumerated; resolution goes through a bounded
 * in-process cache before MySQL.
 */
@Service
public class Links implements LinkMinter {
    static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    static final Pattern CODE = Pattern.compile("[0-9A-Za-z]{7}");
    static final Pattern DOMAIN =
            Pattern.compile("(?=.{1,253}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}");
    static final Pattern POOL = Pattern.compile("[a-z0-9_-]{1,64}");
    static final String DEFAULT_POOL = "default";

    private final MarketingStore db;
    private final Config config;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public Links(MarketingStore db, Config config) {
        this.db = db;
        this.config = config;
    }

    /** What the redirect and the click writer need; immutable so it can be cached. */
    public record Link(
            String code,
            long tenant,
            String kind,
            String domain,
            String target,
            String phoneHash,
            Long contactId,
            String snapshotId,
            String journeyId,
            String journeyMemberId,
            String messageId,
            Instant expiresAt) {
        boolean personal() {
            return "personal".equals(kind);
        }
    }

    private record Cached(Link link, long loadedAt) {}

    @Override
    public ShortLink mint(
            long tenant,
            String domainPool,
            String landingUrl,
            String phoneHash,
            Long contactId,
            ChannelSender.Origin origin) {
        if (phoneHash == null || phoneHash.length() != 64) throw new ApiError(422, "短链需要收件人号码标识");
        var o = origin == null ? new ChannelSender.Origin(null, null, null) : origin;
        return insert(
                tenant,
                "personal",
                domainPool,
                landingUrl,
                phoneHash,
                contactId,
                o,
                null,
                null,
                Instant.now().plus(config.number("MARKETING_LINK_TTL_DAYS", 30), ChronoUnit.DAYS));
    }

    /** A campaign-wide link that only counts PV/UV; it carries no recipient. */
    public ShortLink mintPublic(
            long tenant,
            String domainPool,
            String landingUrl,
            String campaignId,
            String name,
            Instant expiresAt) {
        if (expiresAt == null || !expiresAt.isAfter(Instant.now()))
            throw new ApiError(422, "有效期必须晚于当前时间");
        return insert(
                tenant,
                "public",
                domainPool,
                landingUrl,
                null,
                null,
                new ChannelSender.Origin(null, null, null),
                campaignId,
                name,
                expiresAt);
    }

    private ShortLink insert(
            long tenant,
            String kind,
            String domainPool,
            String landingUrl,
            String phoneHash,
            Long contactId,
            ChannelSender.Origin o,
            String campaignId,
            String name,
            Instant expiresAt) {
        String target = approvedTarget(tenant, landingUrl);
        var domain =
                pick(
                        tenant,
                        domainPool == null || domainPool.isBlank() ? DEFAULT_POOL : domainPool);
        for (int attempt = 0; attempt < 8; attempt++) {
            String code = code();
            try {
                db.write(
                        "INSERT INTO mk_short_link(code,tenant_id,kind,domain_id,domain,target_url,phone_hash,contact_id,snapshot_id,journey_id,journey_member_id,campaign_id,name,expires_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                        code,
                        tenant,
                        kind,
                        domain.get("id"),
                        domain.get("domain"),
                        target,
                        phoneHash,
                        contactId,
                        o.snapshotId(),
                        o.journeyId(),
                        o.journeyMemberId(),
                        campaignId,
                        name,
                        utc(expiresAt));
                return new ShortLink(code, url(domain.get("domain").toString(), code));
            } catch (DuplicateKeyException collision) {
                // Astronomically rare with 62^7 codes; draw again.
            }
        }
        throw new ApiError(503, "短链编码生成失败，请重试");
    }

    String url(String domain, String code) {
        return config.get("MARKETING_LINK_SCHEME", "https") + "://" + domain + "/s/" + code;
    }

    @Override
    public void bindMessage(long tenant, String code, String messageId) {
        if (messageId == null || messageId.isBlank()) throw new ApiError(422, "消息 ID 不能为空");
        int changed =
                db.write(
                        "UPDATE mk_short_link SET message_id=? WHERE tenant_id=? AND code=? AND (message_id IS NULL OR message_id=?)",
                        messageId,
                        tenant,
                        code,
                        messageId);
        if (changed == 0) throw new ApiError(409, "短链不存在或已关联其他消息");
        cache.remove(code);
    }

    String code() {
        char[] c = new char[7];
        for (int i = 0; i < c.length; i++)
            c[i] = ALPHABET.charAt(random.nextInt(ALPHABET.length()));
        return new String(c);
    }

    /** Weighted draw among the pool's active domains only. */
    private Map<String, Object> pick(long tenant, String pool) {
        var domains =
                db.rows(
                        "SELECT id,domain,weight FROM mk_link_domain WHERE tenant_id=? AND pool=? AND state='active' AND weight>0 ORDER BY id",
                        tenant,
                        pool);
        if (domains.isEmpty()) throw new ApiError(409, "域名池「" + pool + "」没有可用的短链域名");
        long total = 0;
        for (var d : domains) total += ((Number) d.get("weight")).longValue();
        long draw = random.nextLong(total);
        for (var d : domains) {
            draw -= ((Number) d.get("weight")).longValue();
            if (draw < 0) return d;
        }
        return domains.getLast();
    }

    /** Normalizes the landing URL and refuses any host that is not on the tenant's whitelist. */
    String approvedTarget(long tenant, String landingUrl) {
        if (landingUrl == null || landingUrl.isBlank() || landingUrl.length() > 2048)
            throw new ApiError(422, "落地页地址无效");
        URI uri;
        try {
            uri = new URI(landingUrl.trim());
        } catch (Exception e) {
            throw new ApiError(422, "落地页地址无效");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!Set.of("http", "https").contains(scheme)
                || uri.getHost() == null
                || uri.getRawUserInfo() != null) throw new ApiError(422, "落地页地址无效");
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (!approved(tenant, host)) throw new ApiError(422, "落地页域名「" + host + "」不在白名单内");
        return uri.toASCIIString();
    }

    boolean approved(long tenant, String host) {
        List<String> candidates = new ArrayList<>(List.of(host));
        for (int i = host.indexOf('.'); i >= 0; i = host.indexOf('.', i + 1))
            candidates.add("*" + host.substring(i));
        return db.count(
                        "SELECT COUNT(*) FROM mk_landing_domain WHERE tenant_id=? AND domain IN ("
                                + String.join(",", Collections.nCopies(candidates.size(), "?"))
                                + ")",
                        concat(tenant, candidates))
                > 0;
    }

    private static Object[] concat(Object first, List<String> rest) {
        Object[] args = new Object[rest.size() + 1];
        args[0] = first;
        for (int i = 0; i < rest.size(); i++) args[i + 1] = rest.get(i);
        return args;
    }

    /**
     * Code lookup for the public redirect. Codes are globally unique, so this is the one read that
     * does not bind a tenant: the tenant comes from the row itself.
     */
    public Link resolve(String code) {
        if (code == null || !CODE.matcher(code).matches()) return null;
        long now = System.currentTimeMillis();
        var hit = cache.get(code);
        long ttl = config.number("MARKETING_LINK_CACHE_SECONDS", 60) * 1000L;
        if (hit != null && now - hit.loadedAt() < ttl) return hit.link();
        var r = db.one("SELECT * FROM mk_short_link WHERE code=?", code);
        if (r == null) return null;
        var link =
                new Link(
                        code,
                        ((Number) r.get("tenant_id")).longValue(),
                        (String) r.get("kind"),
                        (String) r.get("domain"),
                        (String) r.get("target_url"),
                        (String) r.get("phone_hash"),
                        r.get("contact_id") == null
                                ? null
                                : ((Number) r.get("contact_id")).longValue(),
                        (String) r.get("snapshot_id"),
                        (String) r.get("journey_id"),
                        (String) r.get("journey_member_id"),
                        (String) r.get("message_id"),
                        instant(r.get("expires_at")));
        if (cache.size() >= config.number("MARKETING_LINK_CACHE_SIZE", 100_000)) cache.clear();
        cache.put(code, new Cached(link, now));
        return link;
    }

    void evict(String code) {
        cache.remove(code);
    }

    /** DATETIME columns hold UTC wall time, the same convention as the messaging tables. */
    static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    static Instant instant(Object value) {
        if (value == null) return null;
        if (value instanceof Timestamp t) return t.toLocalDateTime().toInstant(ZoneOffset.UTC);
        if (value instanceof LocalDateTime l) return l.toInstant(ZoneOffset.UTC);
        if (value instanceof Instant i) return i;
        throw new IllegalArgumentException("unsupported time " + value.getClass());
    }

    static String domain(String value) {
        String d = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!DOMAIN.matcher(d).matches()) throw new ApiError(422, "域名格式无效");
        return d;
    }

    static String pool(String value) {
        String p = value == null || value.isBlank() ? DEFAULT_POOL : value.trim();
        if (!POOL.matcher(p).matches()) throw new ApiError(422, "域名池名称只能包含小写字母、数字、_ 或 -");
        return p;
    }
}
