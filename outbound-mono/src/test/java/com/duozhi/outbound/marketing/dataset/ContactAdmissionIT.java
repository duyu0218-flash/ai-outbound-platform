package com.duozhi.outbound.marketing.dataset;

import static org.assertj.core.api.Assertions.*;

import com.duozhi.outbound.marketing.MarketingTestBase;
import com.duozhi.outbound.marketing.common.*;
import com.duozhi.outbound.marketing.journey.PolicyConfig;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class ContactAdmissionIT extends MarketingTestBase {
    @Autowired
    ChannelSender sender;
    @Autowired
    com.duozhi.outbound.common.dao.Store store;
    @Autowired
    PhoneIdentity phones;
    @Autowired
    PolicyConfig policies;
    @Autowired
    PlatformTransactionManager manager;

    long campaign() {
        return admin(
                        HttpMethod.POST,
                        "/api/v1/campaigns",
                        Map.of("name", "并发频控", "mode", "ai_only"))
                .path("id")
                .asLong();
    }

    ChannelSender.Recipient recipient(String number) {
        String e164 = "+86" + number;
        return new ChannelSender.Recipient(
                phones.hash(tenant, e164), phones.encrypt(tenant, e164), null);
    }

    @Test
    void competingCampaignsRespectLastDailySlotEvenWithAnOldOuterSnapshot() throws Exception {
        policies.save(tenant, Map.of("call_daily_cap", 1, "call_weekly_cap", 1), 0);
        var recipient = recipient("13600000001");
        // Pre-existing contact avoids treating contact uniqueness as the admission guard.
        store.insert(
                "contact",
                Map.of(
                        "tenant_id",
                        tenant,
                        "phone",
                        "13600000001",
                        "name",
                        "guard",
                        "consent_state",
                        "consented"));
        var campaigns = List.of(campaign(), campaign());
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var tx = new TransactionTemplate(manager);
        try (var pool = Executors.newFixedThreadPool(2)) {
            List<Future<ChannelSender.Result>> results = new ArrayList<>();
            for (long campaign : campaigns)
                results.add(
                        pool.submit(
                                () ->
                                        tx.execute(
                                                status -> {
                                                    jdbc.queryForObject(
                                                            "SELECT COUNT(*) FROM mk_dispatch WHERE tenant_id=?",
                                                            Long.class,
                                                            tenant);
                                                    ready.countDown();
                                                    try {
                                                        if (!start.await(10, TimeUnit.SECONDS))
                                                            throw new IllegalStateException(
                                                                    "start timeout");
                                                    } catch (InterruptedException e) {
                                                        Thread.currentThread().interrupt();
                                                        throw new IllegalStateException(e);
                                                    }
                                                    return sender.call(
                                                            tenant,
                                                            new ChannelSender.Origin(
                                                                    "snapshot-" + campaign,
                                                                    null,
                                                                    null),
                                                            recipient,
                                                            new ChannelSender.Call(campaign));
                                                })));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var a = results.get(0).get(30, TimeUnit.SECONDS);
            var b = results.get(1).get(30, TimeUnit.SECONDS);
            assertThat(List.of(a, b).stream().filter(ChannelSender.Result::accepted).count())
                    .isEqualTo(1);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM mk_dispatch WHERE tenant_id=?",
                                    Long.class,
                                    tenant))
                    .isEqualTo(1);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM callsession WHERE tenant_id=?",
                                    Long.class,
                                    tenant))
                    .isEqualTo(1);
        } finally {
            start.countDown();
        }
    }

    @Test
    void rollbackReleasesSlotAndRetryOfAnAcceptedTargetDoesNotConsumeAnother() {
        policies.save(tenant, Map.of("call_daily_cap", 1, "call_weekly_cap", 1), 0);
        var recipient = recipient("13600000002");
        long campaign = campaign();
        var call = new ChannelSender.Call(campaign);
        var origin = new ChannelSender.Origin("snapshot", null, null);
        new TransactionTemplate(manager)
                .executeWithoutResult(
                        status -> {
                            assertThat(sender.call(tenant, origin, recipient, call).accepted())
                                    .isTrue();
                            status.setRollbackOnly();
                        });
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM mk_dispatch WHERE tenant_id=?",
                                Long.class,
                                tenant))
                .isZero();
        var accepted = sender.call(tenant, origin, recipient, call);
        assertThat(accepted.accepted()).isTrue();
        assertThat(sender.call(tenant, origin, recipient, call).targetId())
                .isEqualTo(accepted.targetId());
        assertThat(
                        sender.call(tenant, origin, recipient, new ChannelSender.Call(campaign()))
                                .accepted())
                .isFalse();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM mk_dispatch WHERE tenant_id=?",
                                Long.class,
                                tenant))
                .isEqualTo(1);
    }

    @Test
    void recipientCannotBypassFrequencyWithAnUnrelatedHash() {
        var recipient = recipient("13600000003");
        var forged = new ChannelSender.Recipient("0".repeat(64), recipient.phoneCipher(), null);
        long campaign = campaign();
        assertThatThrownBy(
                        () -> sender.call(tenant, null, forged, new ChannelSender.Call(campaign)))
                .hasMessageContaining("号码身份");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM mk_dispatch WHERE tenant_id=?",
                                Long.class,
                                tenant))
                .isZero();
    }

    @Test
    void differentPhonesAndCampaignsCanAdmitConcurrently() throws Exception {
        var recipients = List.of(recipient("13600000004"), recipient("13600000005"));
        var campaigns = List.of(campaign(), campaign());
        for (String phone : List.of("13600000004", "13600000005"))
            store.insert(
                    "contact",
                    Map.of("tenant_id", tenant, "phone", phone, "consent_state", "consented"));
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            List<Future<ChannelSender.Result>> results = new ArrayList<>();
            for (int n = 0; n < 2; n++) {
                int index = n;
                results.add(
                        pool.submit(
                                () -> {
                                    if (!start.await(10, TimeUnit.SECONDS))
                                        throw new IllegalStateException("start timeout");
                                    return sender.call(
                                            tenant,
                                            null,
                                            recipients.get(index),
                                            new ChannelSender.Call(campaigns.get(index)));
                                }));
            }
            start.countDown();
            for (var result : results)
                assertThat(result.get(30, TimeUnit.SECONDS).accepted()).isTrue();
        } finally {
            start.countDown();
        }
    }

    @Autowired
    com.duozhi.outbound.messaging.service.Messaging messaging;

    String resource(String kind, Map<String, Object> payload) {
        var resource =
                (Map<?, ?>)
                        messaging.createResource(
                                tenant, kind, Map.of("name", kind, "payload", payload));
        String id = String.valueOf(resource.get("id"));
        messaging.resourceState(tenant, id, "APPROVED");
        return id;
    }

    @Test
    void smsRetryRemainsIdempotentAtCapAndCallSeesCommittedSmsBeyondItsSnapshot() throws Exception {
        policies.save(
                tenant,
                Map.of("sms_daily_cap", 1, "sms_weekly_cap", 1, "sms_call_gap_minutes", 30),
                0);
        String product =
                resource(
                        "product",
                        Map.of("form", "sms", "unit_price", 1000, "daily_recipient_limit", 100));
        String identity = resource("sender", Map.of("brand", "test"));
        String template = resource("template", Map.of("form", "sms", "body", "您好"));
        messaging.credit(tenant, "test-credit", 1_000_000_000L);
        var sms = new ChannelSender.Sms(null, product, template, identity, Map.of(), null, null);
        var recipient = recipient("13600000006");
        var origin = new ChannelSender.Origin("snapshot", null, null);
        long campaign = campaign();
        var tx = new TransactionTemplate(manager);
        tx.executeWithoutResult(
                status -> {
                    jdbc.queryForObject(
                            "SELECT COUNT(*) FROM mk_dispatch WHERE tenant_id=?",
                            Long.class,
                            tenant);
                    try (var pool = Executors.newSingleThreadExecutor()) {
                        var sent =
                                pool.submit(() -> sender.sms(tenant, origin, recipient, sms))
                                        .get(30, TimeUnit.SECONDS);
                        assertThat(sent.accepted()).isTrue();
                        assertThat(sender.sms(tenant, origin, recipient, sms).targetId())
                                .isEqualTo(sent.targetId());
                        var refused =
                                sender.call(
                                        tenant,
                                        origin,
                                        recipient,
                                        new ChannelSender.Call(campaign));
                        assertThat(refused.accepted()).isFalse();
                        assertThat(refused.reason()).contains("分钟内不外呼");
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                });
        assertThat(
                        sender.sms(
                                        tenant,
                                        new ChannelSender.Origin("other", null, null),
                                        recipient,
                                        sms)
                                .accepted())
                .isFalse();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM msg_message WHERE tenant_id=?",
                                Long.class,
                                tenant))
                .isEqualTo(1);
    }

    @Test
    void upgradeFromV35PreservesExistingDispatchesAndIsRepeatable() throws Exception {
        String schema = "guard_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        String url =
                "jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(3306) + "/" + schema;
        try (var connection =
                        java.sql.DriverManager.getConnection(
                                MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
                var sql = connection.createStatement()) {
            sql.execute(
                    "CREATE DATABASE "
                            + schema
                            + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin");
            try {
                org.flywaydb.core.Flyway.configure()
                        .dataSource(url, "root", MYSQL.getPassword())
                        .locations("classpath:db/migration")
                        .target("35")
                        .load()
                        .migrate();
                try (var legacy =
                                java.sql.DriverManager.getConnection(
                                        url, "root", MYSQL.getPassword());
                        var statement = legacy.createStatement()) {
                    statement.executeUpdate(
                            "INSERT INTO mk_dispatch(id,tenant_id,channel,target_type,target_id,phone_hash) "
                                    + "VALUES('historical',1,'call','callsession','existing','"
                                    + "a".repeat(64)
                                    + "')");
                }
                var flyway =
                        org.flywaydb.core.Flyway.configure()
                                .dataSource(url, "root", MYSQL.getPassword())
                                .locations("classpath:db/migration")
                                .load();
                assertThat(flyway.migrate().migrationsExecuted).isEqualTo(3);
                assertThat(flyway.migrate().migrationsExecuted).isZero();
                try (var upgraded =
                                java.sql.DriverManager.getConnection(
                                        url, "root", MYSQL.getPassword());
                        var statement = upgraded.createStatement()) {
                    try (var rows =
                            statement.executeQuery(
                                    "SELECT COUNT(*) FROM mk_dispatch WHERE id='historical'")) {
                        assertThat(rows.next()).isTrue();
                        assertThat(rows.getLong(1)).isEqualTo(1);
                    }
                    try (var rows =
                            statement.executeQuery("SELECT COUNT(*) FROM mk_contact_guard")) {
                        assertThat(rows.next()).isTrue();
                        assertThat(rows.getLong(1)).isZero();
                    }
                }
            } finally {
                sql.execute("DROP DATABASE " + schema);
            }
        }
    }

    @Test
    void differentSmsRecipientsDoNotDeadlockOnIdempotencyGaps() throws Exception {
        String product =
                resource(
                        "product",
                        Map.of("form", "sms", "unit_price", 1000, "daily_recipient_limit", 100));
        String identity = resource("sender", Map.of("brand", "test"));
        String template = resource("template", Map.of("form", "sms", "body", "您好"));
        messaging.credit(tenant, "parallel-sms-credit", 1_000_000_000L);
        var sms = new ChannelSender.Sms(null, product, template, identity, Map.of(), null, null);
        var recipients = List.of(recipient("13600000007"), recipient("13600000008"));
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            List<Future<ChannelSender.Result>> results = new ArrayList<>();
            for (var recipient : recipients) {
                results.add(
                        pool.submit(
                                () -> {
                                    if (!start.await(10, TimeUnit.SECONDS))
                                        throw new IllegalStateException("start timeout");
                                    return sender.sms(tenant, null, recipient, sms);
                                }));
            }
            start.countDown();
            for (var result : results)
                assertThat(result.get(30, TimeUnit.SECONDS).accepted()).isTrue();
        } finally {
            start.countDown();
        }
    }
}
