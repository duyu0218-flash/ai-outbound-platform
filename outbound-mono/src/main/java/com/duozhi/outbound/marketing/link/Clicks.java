package com.duozhi.outbound.marketing.link;

import com.duozhi.outbound.common.config.Config;
import com.duozhi.outbound.marketing.common.Attribution;
import com.duozhi.outbound.marketing.common.MarketingStore;
import com.duozhi.outbound.marketing.common.OutcomeCodes;
import com.duozhi.outbound.marketing.common.TouchEvents;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Click capture off the redirect path. The redirect only offers to a bounded queue (dropping and
 * counting when full); a single writer drains it in batches, classifies each click and writes
 * mk_link_click, then records the first valid click of a personal link as LINK_CLICKED.
 */
@Service
public class Clicks {
    private static final Logger log = LoggerFactory.getLogger(Clicks.class);
    static final Duration DEDUPE = Duration.ofMinutes(30), TOO_FAST = Duration.ofSeconds(10);
    static final Pattern SCANNER =
            Pattern.compile(
                    "(?i).*(bot|spider|crawl|slurp|curl|wget|python-requests|python-urllib|aiohttp|go-http-client|java-http-client|java/|okhttp|apache-httpclient|httpclient|libwww|scrapy|axios|node-fetch|headless|phantomjs|scanner|nmap|masscan|zgrab|preview|facebookexternalhit|monitor).*");

    public record Click(
            String code, Instant at, String ipPrefix, String userAgent, boolean wechat) {}

    private final MarketingStore db;
    private final Links links;
    private final TouchEvents touches;
    private final Attribution attribution;
    private final TransactionTemplate tx;
    private final BlockingQueue<Click> queue;
    private final AtomicLong dropped = new AtomicLong(), written = new AtomicLong();
    private final ReentrantLock writer = new ReentrantLock();
    private final ScheduledExecutorService drain =
            Executors.newSingleThreadScheduledExecutor(
                    Thread.ofPlatform().daemon().name("link-click-writer").factory());

    public Clicks(
            MarketingStore db,
            Links links,
            TouchEvents touches,
            Attribution attribution,
            TransactionTemplate tx,
            Config config) {
        this.db = db;
        this.links = links;
        this.touches = touches;
        this.attribution = attribution;
        this.tx = tx;
        queue =
                new ArrayBlockingQueue<>(
                        Math.max(1, config.number("MARKETING_LINK_CLICK_QUEUE", 20000)));
        long every = Math.max(20, config.number("MARKETING_LINK_CLICK_FLUSH_MS", 200));
        drain.scheduleWithFixedDelay(this::background, every, every, TimeUnit.MILLISECONDS);
    }

    /** Never blocks: a full queue drops the click and counts it. */
    public boolean offer(Click click) {
        if (queue.offer(click)) return true;
        dropped.incrementAndGet();
        return false;
    }

    public Map<String, Object> stats() {
        return Map.of(
                "queued", queue.size(),
                "capacity", queue.size() + queue.remainingCapacity(),
                "dropped", dropped.get(),
                "written", written.get());
    }

    private void background() {
        try {
            flush();
        } catch (RuntimeException e) {
            log.warn("link click writer failed: {}", e.getClass().getSimpleName());
        }
    }

    /** Drains everything queued so far and writes it; synchronous, so tests can call it. */
    public int flush() {
        writer.lock();
        try {
            int total = 0;
            List<Click> batch = new ArrayList<>(500);
            while (queue.drainTo(batch, 500) > 0) {
                write(batch);
                total += batch.size();
                batch.clear();
            }
            written.addAndGet(total);
            return total;
        } finally {
            writer.unlock();
        }
    }

    private record Row(
            Click click, Links.Link link, String verdict, String reason, String visitor) {}

    private void write(List<Click> batch) {
        Map<String, Optional<Instant>> submitted = new HashMap<>(), lastValid = new HashMap<>();
        List<Row> rows = new ArrayList<>(batch.size());
        for (Click c : batch) {
            var link = links.resolve(c.code());
            if (link == null) continue;
            String visitor =
                    sha256(
                            Objects.toString(c.ipPrefix(), "")
                                    + "|"
                                    + Objects.toString(c.userAgent(), ""));
            String reason = botReason(c, link, submitted);
            String verdict = reason == null ? "valid" : "bot";
            if (reason == null) {
                // Personal links dedupe per code; a public link serves many people, so per visitor.
                String key = link.code() + (link.personal() ? "" : "|" + visitor);
                var last = lastValid.computeIfAbsent(key, k -> lastValid(link, visitor));
                if (last.isPresent()
                        && Duration.between(last.get(), c.at()).compareTo(DEDUPE) < 0) {
                    verdict = "repeat";
                    reason = "within_30m";
                } else {
                    rows.add(new Row(c, link, verdict, last.isPresent() ? null : "first", visitor));
                    lastValid.put(key, Optional.of(c.at()));
                    continue;
                }
            }
            rows.add(new Row(c, link, verdict, reason, visitor));
        }
        if (rows.isEmpty()) return;
        tx.executeWithoutResult(
                s -> {
                    db.jdbc()
                            .batchUpdate(
                                    "INSERT INTO mk_link_click(tenant_id,code,clicked_at,verdict,reason,ip_prefix,user_agent,visitor_hash,wechat) VALUES(?,?,?,?,?,?,?,?,?)",
                                    rows.stream()
                                            .map(
                                                    r ->
                                                            new Object[] {
                                                                r.link().tenant(),
                                                                r.link().code(),
                                                                Links.utc(r.click().at()),
                                                                r.verdict(),
                                                                r.reason(),
                                                                r.click().ipPrefix(),
                                                                truncate(
                                                                        r.click().userAgent(), 255),
                                                                r.visitor(),
                                                                r.click().wechat()
                                                            })
                                            .toList());
                    for (Row r : rows)
                        if ("first".equals(r.reason()) && r.link().personal()) firstClick(r);
                });
    }

    private void firstClick(Row r) {
        var link = r.link();
        var dispatch =
                link.messageId() == null
                        ? null
                        : attribution.find(link.tenant(), Attribution.MESSAGE, link.messageId());
        if (dispatch == null)
            dispatch =
                    new Attribution.Dispatch(
                            null,
                            link.tenant(),
                            OutcomeCodes.SMS,
                            "mk_short_link",
                            link.code(),
                            link.phoneHash(),
                            link.contactId(),
                            link.snapshotId(),
                            link.journeyId(),
                            link.journeyMemberId());
        var detail = new LinkedHashMap<String, Object>();
        detail.put("code", link.code());
        detail.put("domain", link.domain());
        if (link.messageId() != null) detail.put("message_id", link.messageId());
        detail.put("wechat", r.click().wechat());
        touches.record(
                new TouchEvents.Touch(
                        link.tenant(),
                        link.phoneHash(),
                        link.contactId(),
                        OutcomeCodes.LINK,
                        OutcomeCodes.LINK_CLICKED,
                        "mk_short_link",
                        link.code(),
                        dispatch,
                        detail,
                        Links.utc(r.click().at())));
    }

    private String botReason(Click c, Links.Link link, Map<String, Optional<Instant>> submitted) {
        String ua = c.userAgent();
        if (ua == null || ua.isBlank()) return "no_ua";
        if (SCANNER.matcher(ua).matches()) return "scanner_ua";
        if (link.messageId() != null) {
            var sent =
                    submitted.computeIfAbsent(
                            link.messageId(),
                            id ->
                                    Optional.ofNullable(
                                            Links.instant(
                                                    Optional.ofNullable(
                                                                    db.one(
                                                                            "SELECT submitted_at FROM msg_message WHERE tenant_id=? AND id=?",
                                                                            link.tenant(),
                                                                            id))
                                                            .map(m -> m.get("submitted_at"))
                                                            .orElse(null))));
            if (sent.isPresent() && Duration.between(sent.get(), c.at()).compareTo(TOO_FAST) < 0)
                return "too_fast";
        }
        return null;
    }

    private Optional<Instant> lastValid(Links.Link link, String visitor) {
        var r =
                link.personal()
                        ? db.one(
                                "SELECT MAX(clicked_at) AS at FROM mk_link_click WHERE tenant_id=? AND code=? AND verdict='valid'",
                                link.tenant(),
                                link.code())
                        : db.one(
                                "SELECT MAX(clicked_at) AS at FROM mk_link_click WHERE tenant_id=? AND code=? AND verdict='valid' AND visitor_hash=?",
                                link.tenant(),
                                link.code(),
                                visitor);
        return Optional.ofNullable(r == null ? null : Links.instant(r.get("at")));
    }

    /** IPv4 keeps /24, IPv6 keeps /48; nothing finer is ever stored. */
    static String ipPrefix(String address) {
        if (address == null || address.isBlank()) return null;
        if (address.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
            String[] p = address.split("\\.");
            return p[0] + "." + p[1] + "." + p[2] + ".0/24";
        }
        if (address.contains(":")) {
            try {
                byte[] b = java.net.InetAddress.getByName(address).getAddress();
                if (b.length == 16)
                    return String.format(
                                    "%x:%x:%x::",
                                    ((b[0] & 0xff) << 8) | (b[1] & 0xff),
                                    ((b[2] & 0xff) << 8) | (b[3] & 0xff),
                                    ((b[4] & 0xff) << 8) | (b[5] & 0xff))
                            + "/48";
                if (b.length == 4)
                    return ipPrefix(java.net.InetAddress.getByAddress(b).getHostAddress());
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @PreDestroy
    public void close() {
        drain.shutdown();
        try {
            flush();
        } catch (RuntimeException ignored) {
            // Shutting down; the dropped counter is not persisted anyway.
        }
    }
}
