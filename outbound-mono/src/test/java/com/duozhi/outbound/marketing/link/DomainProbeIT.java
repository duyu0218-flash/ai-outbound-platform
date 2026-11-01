package com.duozhi.outbound.marketing.link;

import static org.assertj.core.api.Assertions.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;

class DomainProbeIT extends LinkTestSupport {
    @Autowired
    DomainProbe probe;
    @Autowired
    MockDomainNetwork network;
    @Autowired
    MockInterceptionDetector interception;

    @Test
    void failingDomainIsBlockedAlertedAndItsPoolFailsOverToStandby() throws Exception {
        String primary = "p" + tenant + ".lnk.test", spare = "s" + tenant + ".lnk.test";
        var created =
                admin(
                        HttpMethod.POST,
                        "/api/v1/marketing/links/domains",
                        Map.of(
                                "domain", primary,
                                "pool", "promo",
                                "state", "active",
                                "weight", 100,
                                "icp_number", "京ICP备00000000号-1",
                                "cert_expires_at", "2030-01-01"));
        String primaryId = created.path("id").asText();
        String spareId =
                admin(
                                HttpMethod.POST,
                                "/api/v1/marketing/links/domains",
                                Map.of("domain", spare, "pool", "promo", "state", "standby"))
                        .path("id")
                        .asText();
        assertThat(
                        req(
                                        agent,
                                        HttpMethod.POST,
                                        "/api/v1/marketing/links/domains",
                                        Map.of("domain", "x" + tenant + ".lnk.test"))
                                .getStatusCode()
                                .value())
                .isEqualTo(403);
        admin(
                HttpMethod.POST,
                "/api/v1/marketing/links/landing-domains",
                Map.of("domain", "land.shop.test"));
        String phone = phones.of(tenant, "13700137001").hash();
        var before = links.mint(tenant, "promo", "https://land.shop.test/", phone, null, null);
        assertThat(before.url()).contains(primary);

        probe.probeDue(1000);
        assertThat(state(primaryId)).isEqualTo("active");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT probe_state FROM mk_link_domain WHERE id=?",
                                String.class,
                                primaryId))
                .isEqualTo("ok");

        interception.intercept(primary, "微信");
        try {
            var probed =
                    admin(
                            HttpMethod.POST,
                            "/api/v1/marketing/links/domains/" + primaryId + "/probe",
                            null);
            assertThat(probed.path("state").asText()).isEqualTo("blocked");
            assertThat(probed.path("blocked_reason").asText()).contains("微信");
        } finally {
            interception.reset(primary);
        }
        assertThat(state(spareId)).isEqualTo("active");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM ops_notice WHERE tenant_id=? AND path='/marketing/links' AND title LIKE ?",
                                Long.class,
                                tenant,
                                "%" + primary + "%"))
                .isEqualTo(1L);
        for (int i = 0; i < 20; i++)
            assertThat(
                            links.mint(
                                            tenant,
                                            "promo",
                                            "https://land.shop.test/",
                                            phone,
                                            null,
                                            null)
                                    .url())
                    .contains(spare);
        // Links already on the blocked domain keep working.
        assertThat(open(before.code(), BROWSER_UA).statusCode()).isEqualTo(302);

        // DNS failure and an expired certificate block too; the pool then has nothing left.
        network.failDns(spare, "NXDOMAIN");
        try {
            assertThat(probe.probe(tenant, spareId).state()).isEqualTo("blocked");
        } finally {
            network.reset(spare);
        }
        assertThatThrownBy(
                        () ->
                                links.mint(
                                        tenant,
                                        "promo",
                                        "https://land.shop.test/",
                                        phone,
                                        null,
                                        null))
                .hasMessageContaining("没有可用的短链域名");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM ops_notice WHERE tenant_id=? AND title LIKE '%没有可用域名%'",
                                Long.class, tenant))
                .isEqualTo(1L);

        String old = "c" + tenant + ".lnk.test";
        String oldId = domain(old, "certs", "active", 10);
        jdbc.update(
                "UPDATE mk_link_domain SET cert_expires_at=UTC_TIMESTAMP(6)-INTERVAL 1 DAY WHERE id=?",
                oldId);
        assertThat(probe.probe(tenant, oldId).reason()).isEqualTo("证书已过期");
        String soon = "w" + tenant + ".lnk.test";
        String soonId = domain(soon, "certs", "active", 10);
        network.certificate(soon, Instant.now().plus(3, ChronoUnit.DAYS));
        try {
            var outcome = probe.probe(tenant, soonId);
            assertThat(outcome.state()).isEqualTo("active");
            assertThat(outcome.reason()).contains("15 天");
        } finally {
            network.reset(soon);
        }
        assertThat(
                        probe.probe(
                                        tenant,
                                        domain("dead" + tenant + ".invalid", "certs", "standby", 1))
                                .state())
                .isEqualTo("blocked");

        // Operators can bring a domain back after an appeal.
        var restored =
                admin(
                        HttpMethod.PUT,
                        "/api/v1/marketing/links/domains/" + primaryId,
                        Map.of("state", "active"));
        assertThat(restored.path("state").asText()).isEqualTo("active");
        assertThat(restored.path("blocked_reason").isNull()).isTrue();
        assertThat(admin(HttpMethod.GET, "/api/v1/marketing/links/domains", null).size())
                .isEqualTo(5);
        assertThat(
                        admin(HttpMethod.GET, "/api/v1/marketing/links/overview", null)
                                .path("probe_mode")
                                .asText())
                .isEqualTo("mock");
    }

    String state(String id) {
        return jdbc.queryForObject("SELECT state FROM mk_link_domain WHERE id=?", String.class, id);
    }
}
