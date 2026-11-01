package com.duozhi.outbound.marketing.link;

import static org.assertj.core.api.Assertions.*;

import com.duozhi.outbound.common.config.Config;
import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.marketing.common.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionTemplate;

class ShortLinkIT extends LinkTestSupport {
    @Autowired
    Attribution attribution;
    @Autowired
    TouchEvents touches;
    @Autowired
    MarketingStore store;
    @Autowired
    TransactionTemplate tx;

    String hash(String phone) {
        return phones.of(tenant, phone).hash();
    }

    @Test
    void mintingIsUniqueWeightedAndOnlyForWhitelistedLandingPages() {
        String a = "a" + tenant + ".lnk.test", b = "b" + tenant + ".lnk.test";
        domain(a, "default", "active", 3);
        domain(b, "default", "active", 1);
        domain("standby" + tenant + ".lnk.test", "default", "standby", 100);
        domain("zero" + tenant + ".lnk.test", "default", "active", 0);
        approve("land.shop.test");
        approve("*.brand.test");
        String phone = hash("13800138001");
        Set<String> codes = new HashSet<>();
        Map<String, Integer> perDomain = new HashMap<>();
        for (int i = 0; i < 400; i++) {
            var link = links.mint(tenant, null, "https://land.shop.test/p?i=" + i, phone, 9L, null);
            assertThat(link.code()).matches("[0-9A-Za-z]{7}");
            assertThat(codes.add(link.code())).isTrue();
            String host = java.net.URI.create(link.url()).getHost();
            perDomain.merge(host, 1, Integer::sum);
            assertThat(link.url()).endsWith("/s/" + link.code());
        }
        assertThat(perDomain.keySet()).containsExactlyInAnyOrder(a, b);
        assertThat(perDomain.get(a)).isGreaterThan(perDomain.get(b));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(DISTINCT code) FROM mk_short_link WHERE tenant_id=?",
                                Long.class,
                                tenant))
                .isEqualTo(400L);

        assertThat(
                        links.mint(
                                        tenant,
                                        "default",
                                        "https://shop.brand.test/x",
                                        phone,
                                        null,
                                        null)
                                .code())
                .hasSize(7);
        for (String bad :
                List.of(
                        "https://evil.test/phish",
                        "https://brand.test.evil.test/",
                        "javascript:alert(1)",
                        "https://user@land.shop.test/",
                        "ftp://land.shop.test/file",
                        "//land.shop.test/x"))
            assertThatThrownBy(() -> links.mint(tenant, null, bad, phone, null, null))
                    .isInstanceOf(ApiError.class)
                    .extracting(e -> ((ApiError) e).status)
                    .isEqualTo(422);
        assertThatThrownBy(
                        () ->
                                links.mint(
                                        tenant,
                                        "empty-pool",
                                        "https://land.shop.test/",
                                        phone,
                                        null,
                                        null))
                .hasMessageContaining("没有可用的短链域名");
        // Another tenant's whitelist never applies.
        assertThatThrownBy(
                        () ->
                                links.mint(
                                        tenant + 100000,
                                        null,
                                        "https://land.shop.test/",
                                        phone,
                                        null,
                                        null))
                .isInstanceOf(ApiError.class);
    }

    @Test
    void redirectAnswers302WechatInterstitialExpiredAndBlockedDomainStillRedirects()
            throws Exception {
        String dom = "r" + tenant + ".lnk.test";
        String domainId = domain(dom, "default", "active", 100);
        approve("land.shop.test");
        var link =
                links.mint(
                        tenant,
                        null,
                        "https://land.shop.test/offer?a=1",
                        hash("13800138002"),
                        null,
                        null);

        var r = open(link.code(), BROWSER_UA);
        assertThat(r.statusCode()).isEqualTo(302);
        assertThat(r.headers().firstValue("Location")).contains("https://land.shop.test/offer?a=1");

        var wx = open(link.code(), WECHAT_UA);
        assertThat(wx.statusCode()).isEqualTo(200);
        assertThat(wx.body()).contains("请在浏览器中打开");
        assertThat(wx.headers().firstValue("Location")).isEmpty();

        jdbc.update("UPDATE mk_link_domain SET state='blocked' WHERE id=?", domainId);
        assertThat(open(link.code(), BROWSER_UA).statusCode()).isEqualTo(302);

        jdbc.update(
                "UPDATE mk_short_link SET expires_at=UTC_TIMESTAMP(6)-INTERVAL 1 MINUTE WHERE code=?",
                link.code());
        links.evict(link.code());
        var expired = open(link.code(), BROWSER_UA);
        assertThat(expired.statusCode()).isEqualTo(410);
        assertThat(expired.body()).contains("活动已结束");

        assertThat(open("ZZZZZZZ", BROWSER_UA).statusCode()).isEqualTo(404);
        assertThat(open("bad", BROWSER_UA).statusCode()).isEqualTo(404);

        clicks.flush();
        var stored =
                jdbc.queryForList(
                        "SELECT ip_prefix,wechat FROM mk_link_click WHERE tenant_id=? AND code=?",
                        tenant,
                        link.code());
        assertThat(stored).hasSize(3);
        assertThat(stored)
                .allSatisfy(c -> assertThat(c.get("ip_prefix").toString()).endsWith(".0/24"));
        assertThat(stored).anySatisfy(c -> assertThat(c.get("wechat")).isEqualTo(true));
    }

    @Test
    void botClicksAreSeparatedAndRepeatsWithinThirtyMinutesCountOnce() throws Exception {
        domain("bot" + tenant + ".lnk.test", "default", "active", 100);
        approve("land.shop.test");
        var link =
                links.mint(
                        tenant, null, "https://land.shop.test/", hash("13800138003"), null, null);
        String msg = message("13800138003", "DELIVERED", now().minusHours(1), "cmp-1");
        links.bindMessage(tenant, link.code(), msg);

        Instant t0 = Instant.now();
        clicks.offer(new Clicks.Click(link.code(), t0, "10.1.2.0/24", null, false));
        clicks.offer(new Clicks.Click(link.code(), t0, "10.1.2.0/24", "curl/8.4.0", false));
        open(link.code(), null); // JDK client UA: Java-http-client, a known scanner
        clicks.offer(new Clicks.Click(link.code(), t0, "10.1.2.0/24", BROWSER_UA, false));
        clicks.offer(
                new Clicks.Click(
                        link.code(),
                        t0.plus(5, ChronoUnit.MINUTES),
                        "10.1.2.0/24",
                        BROWSER_UA,
                        false));
        clicks.offer(
                new Clicks.Click(
                        link.code(),
                        t0.plus(29, ChronoUnit.MINUTES),
                        "10.1.2.0/24",
                        BROWSER_UA,
                        false));
        clicks.flush();
        clicks.offer(
                new Clicks.Click(
                        link.code(),
                        t0.plus(31, ChronoUnit.MINUTES),
                        "10.1.2.0/24",
                        BROWSER_UA,
                        false));
        clicks.flush();
        var verdicts =
                jdbc.queryForList(
                        "SELECT verdict,reason FROM mk_link_click WHERE tenant_id=? AND code=? ORDER BY id",
                        tenant,
                        link.code());
        assertThat(verdicts)
                .extracting(v -> v.get("verdict") + ":" + v.get("reason"))
                .containsExactly(
                        "bot:no_ua",
                        "bot:scanner_ua",
                        "bot:scanner_ua",
                        "valid:first",
                        "repeat:within_30m",
                        "repeat:within_30m",
                        "valid:null");

        // A click within 10 seconds of submission is a link scanner, whatever its UA says.
        var fast =
                links.mint(
                        tenant, null, "https://land.shop.test/", hash("13800138004"), null, null);
        links.bindMessage(
                tenant,
                fast.code(),
                message("13800138004", "DELIVERED", now().minusSeconds(2), "cmp-1"));
        assertThat(open(fast.code(), BROWSER_UA).statusCode()).isEqualTo(302);
        clicks.flush();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT CONCAT(verdict,':',reason) FROM mk_link_click WHERE code=?",
                                String.class,
                                fast.code()))
                .isEqualTo("bot:too_fast");
        assertThat(touches.history(tenant, hash("13800138004"), 10)).isEmpty();

        var perf =
                admin(
                        HttpMethod.GET,
                        "/api/v1/marketing/links/performance?dimension=campaign",
                        null);
        var row = perf.get(0);
        assertThat(row.path("dimension_key").asText()).isEqualTo("cmp-1");
        assertThat(row.path("sent").asLong()).isEqualTo(2);
        assertThat(row.path("delivered").asLong()).isEqualTo(2);
        assertThat(row.path("valid_clicks").asLong()).isEqualTo(2);
        assertThat(row.path("bot_clicks").asLong()).isEqualTo(4);
        assertThat(row.path("click_rate").asDouble()).isEqualTo(50.0);
        var details =
                admin(HttpMethod.GET, "/api/v1/marketing/links/clicks?code=" + link.code(), null);
        assertThat(details.size()).isEqualTo(7);
        assertThat(details.get(0).path("phone").asText()).isEqualTo("138****8003");
        assertThat(details.toString()).doesNotContain("13800138003");
        assertThat(
                        req(agent, HttpMethod.GET, "/api/v1/marketing/links/clicks", null)
                                .getStatusCode()
                                .value())
                .isEqualTo(403);
    }

    @Test
    void firstValidClickRecordsExactlyOneAttributedLinkClickedAndConversionPostback()
            throws Exception {
        domain("fc" + tenant + ".lnk.test", "default", "active", 100);
        approve("land.shop.test");
        String phone = hash("13800138005");
        var origin = new ChannelSender.Origin("snap-1", "jr-1", "jm-1");
        var link = links.mint(tenant, null, "https://land.shop.test/", phone, 77L, origin);
        String msg = message("13800138005", "DELIVERED", now().minusHours(2), null);
        var dispatch =
                attribution.register(
                        tenant,
                        OutcomeCodes.SMS,
                        Attribution.MESSAGE,
                        msg,
                        phone,
                        77L,
                        "snap-1",
                        "jr-1",
                        "jm-1");
        links.bindMessage(tenant, link.code(), msg);
        assertThatThrownBy(() -> links.bindMessage(tenant, link.code(), "msg_other"))
                .isInstanceOf(ApiError.class);

        open(link.code(), BROWSER_UA);
        open(link.code(), WECHAT_UA);
        clicks.flush();
        clicks.offer(
                new Clicks.Click(
                        link.code(),
                        Instant.now().plus(2, ChronoUnit.HOURS),
                        "10.0.0.0/24",
                        BROWSER_UA,
                        false));
        clicks.flush();

        var events =
                jdbc.queryForList(
                        "SELECT * FROM mk_touch_event WHERE tenant_id=? AND code='LINK_CLICKED'",
                        tenant);
        assertThat(events).hasSize(1);
        var e = events.getFirst();
        assertThat(e)
                .containsEntry("phone_hash", phone)
                .containsEntry("channel", "link")
                .containsEntry("source_type", "mk_short_link")
                .containsEntry("source_id", link.code())
                .containsEntry("dispatch_id", dispatch.id())
                .containsEntry("snapshot_id", "snap-1")
                .containsEntry("journey_id", "jr-1")
                .containsEntry("journey_member_id", "jm-1");
        assertThat(((Number) e.get("contact_id")).longValue()).isEqualTo(77L);
        assertThat(e.get("detail").toString()).contains(msg);

        var body = Map.of("code", link.code(), "event", "form_submit");
        assertThat(
                        admin(HttpMethod.POST, "/api/v1/marketing/links/conversions", body)
                                .path("recorded")
                                .asBoolean())
                .isTrue();
        assertThat(
                        admin(HttpMethod.POST, "/api/v1/marketing/links/conversions", body)
                                .path("recorded")
                                .asBoolean())
                .isFalse();
        assertThat(
                        req(agent, HttpMethod.POST, "/api/v1/marketing/links/conversions", body)
                                .getStatusCode()
                                .value())
                .isEqualTo(403);
        assertThat(
                        req(null, HttpMethod.POST, "/api/v1/marketing/links/conversions", body)
                                .getStatusCode()
                                .value())
                .isEqualTo(401);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT snapshot_id FROM mk_touch_event WHERE tenant_id=? AND code='LINK_CONVERTED'",
                                String.class,
                                tenant))
                .isEqualTo("snap-1");
    }

    @Test
    void publicCampaignLinkCountsPageViewsAndVisitorsOnly() throws Exception {
        domain("pub" + tenant + ".lnk.test", "default", "active", 100);
        admin(
                HttpMethod.POST,
                "/api/v1/marketing/links/landing-domains",
                Map.of("domain", "*.shop.test"));
        var created =
                admin(
                        HttpMethod.POST,
                        "/api/v1/marketing/links/public",
                        Map.of(
                                "landing_url",
                                "https://land.shop.test/event",
                                "name",
                                "双十一",
                                "campaign_id",
                                "c-11"));
        String code = created.path("code").asText();
        open(code, BROWSER_UA);
        open(code, BROWSER_UA);
        clicks.offer(new Clicks.Click(code, Instant.now(), "10.9.9.0/24", BROWSER_UA, false));
        clicks.flush();
        var list = admin(HttpMethod.GET, "/api/v1/marketing/links/public", null);
        assertThat(list.get(0).path("pv").asLong()).isEqualTo(3);
        assertThat(list.get(0).path("uv").asLong()).isEqualTo(2);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM mk_touch_event WHERE tenant_id=?",
                                Long.class,
                                tenant))
                .isZero();
        assertThat(
                        req(
                                        admin,
                                        HttpMethod.POST,
                                        "/api/v1/marketing/links/conversions",
                                        Map.of("code", code))
                                .getStatusCode()
                                .value())
                .isEqualTo(422);
    }

    @Test
    void fullQueueDropsAndCountsInsteadOfBlocking() {
        var tiny =
                new Clicks(
                        store,
                        links,
                        touches,
                        attribution,
                        tx,
                        new Config(
                                new MockEnvironment()
                                        .withProperty("MARKETING_LINK_CLICK_QUEUE", "2")
                                        .withProperty("MARKETING_LINK_CLICK_FLUSH_MS", "600000")));
        try {
            for (int i = 0; i < 5; i++)
                tiny.offer(new Clicks.Click("ZZZZZZZ", Instant.now(), null, BROWSER_UA, false));
            assertThat(tiny.stats()).containsEntry("dropped", 3L).containsEntry("queued", 2);
            assertThat(tiny.flush()).isEqualTo(2);
        } finally {
            tiny.close();
        }
        assertThat(Clicks.ipPrefix("203.0.113.77")).isEqualTo("203.0.113.0/24");
        assertThat(Clicks.ipPrefix("2001:db8:abcd:12::1")).isEqualTo("2001:db8:abcd::/48");
    }
}
