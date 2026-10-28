package com.duozhi.outbound.messaging;

import static org.assertj.core.api.Assertions.*;

import com.duozhi.outbound.messaging.service.*;
import java.net.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class WebhookSafetyTest {
    @Test
    void rejectsPrivateReservedAndTransitionAddresses() throws Exception {
        for (String address :
                List.of(
                        "127.0.0.1",
                        "10.0.0.1",
                        "172.16.0.1",
                        "192.168.0.1",
                        "169.254.169.254",
                        "100.64.0.1",
                        "198.18.0.1",
                        "::1",
                        "fc00::1",
                        "fe80::1",
                        "2001:db8::1",
                        "2002:a00:1::1"))
            assertThat(PinnedWebhookTransport.publicAddress(InetAddress.getByName(address)))
                    .as(address)
                    .isFalse();
        assertThat(PinnedWebhookTransport.publicAddress(InetAddress.getByName("8.8.8.8"))).isTrue();
        assertThat(
                        PinnedWebhookTransport.publicAddress(
                                InetAddress.getByName("2606:4700:4700::1111")))
                .isTrue();
    }

    @Test
    void invalidSchemesUserInfoPortsAndFragmentsAreNotAccepted() {
        for (String address :
                List.of(
                        "http://example.com/hook",
                        "https://user@example.com/hook",
                        "https://example.com:8443/hook",
                        "https://example.com/hook#fragment"))
            assertThatThrownBy(() -> PinnedWebhookTransport.validate(URI.create(address)))
                    .isInstanceOf(java.io.IOException.class);
    }

    @Test
    void canonicalQuerySortsEscapesAndRejectsDuplicateKeys() {
        assertThat(MessageApiAuth.query("z=a+b&a=%E4%B8%AD")).isEqualTo("a=%E4%B8%AD&z=a%20b");
        assertThatThrownBy(() -> MessageApiAuth.query("a=1&%61=2"))
                .isInstanceOf(RuntimeException.class);
    }
}
