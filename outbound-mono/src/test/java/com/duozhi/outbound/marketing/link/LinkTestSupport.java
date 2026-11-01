package com.duozhi.outbound.marketing.link;

import com.duozhi.outbound.marketing.MarketingTestBase;
import com.duozhi.outbound.marketing.common.PhoneIdentity;
import com.duozhi.outbound.messaging.service.MessageSecrets;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

/** Fixtures shared by the link module tests: domains, whitelist, messages and raw redirects. */
abstract class LinkTestSupport extends MarketingTestBase {
    static final String BROWSER_UA =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1";
    static final String WECHAT_UA = BROWSER_UA + " MicroMessenger/8.0.42(0x18002a2c) NetType/WIFI";

    @Autowired
    Links links;
    @Autowired
    Clicks clicks;
    @Autowired
    PhoneIdentity phones;
    @Autowired
    MessageSecrets secrets;

    @Value("${local.server.port}")
    int port;

    final HttpClient raw =
            HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    String domain(String name, String pool, String state, int weight) {
        String id = "lnd_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.update(
                "INSERT INTO mk_link_domain(id,tenant_id,domain,pool,state,weight) VALUES(?,?,?,?,?,?)",
                id,
                tenant,
                name,
                pool,
                state,
                weight);
        return id;
    }

    void approve(String domain) {
        jdbc.update("INSERT INTO mk_landing_domain(tenant_id,domain) VALUES(?,?)", tenant, domain);
    }

    /** A message row as the messaging module would store it; times are UTC wall time. */
    String message(String phone, String state, LocalDateTime submittedAt, String campaign) {
        String id = "msg_" + UUID.randomUUID().toString().replace("-", "");
        var p = phones.of(tenant, phone);
        jdbc.update(
                "INSERT INTO msg_message(id,tenant_id,application_id,idempotency_key,request_hash,product_id,template_id,sender_id,campaign_id,recipient_cipher,recipient_hash,recipient_mask,payload,state,requested_form,sales_amount,price_version,scheduled_at,expires_at,submitted_at,actual_transport) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id,
                tenant,
                "app-test",
                id,
                "0".repeat(64),
                "prod-test",
                "tpl-welcome",
                "sender-test",
                campaign,
                secrets.encrypt("recipient:" + tenant + ":" + id, p.e164()),
                p.hash(),
                "+861****" + p.e164().substring(p.e164().length() - 4),
                "{}",
                state,
                "sms",
                1000,
                "prod-test",
                now().minusHours(80),
                now().plusDays(1),
                submittedAt,
                "mock");
        return id;
    }

    void event(String messageId, String type, String payload) {
        jdbc.update(
                "INSERT INTO msg_event(id,tenant_id,application_id,message_id,type,payload) VALUES(?,?,?,?,?,?)",
                "evt_" + UUID.randomUUID().toString().replace("-", ""),
                tenant,
                "app-test",
                messageId,
                type,
                payload);
    }

    static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    HttpResponse<String> open(String code, String userAgent) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/s/" + code));
        if (userAgent != null) request.header("User-Agent", userAgent);
        return raw.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }
}
