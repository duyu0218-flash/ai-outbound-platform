package com.duozhi.outbound.marketing.link;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Offline stand-in: no socket is opened. Domains under the reserved .invalid TLD fail DNS, and
 * tests or operators in a sandbox can script a failure or a certificate expiry per domain.
 */
@Component
@ConditionalOnProperty(name = "MARKETING_LINK_PROBE", havingValue = "mock", matchIfMissing = true)
public class MockDomainNetwork implements DomainNetwork {
    private final Map<String, String> failures = new ConcurrentHashMap<>();
    private final Map<String, Instant> certificates = new ConcurrentHashMap<>();

    public void failDns(String domain, String reason) {
        failures.put(domain, reason);
    }

    public void certificate(String domain, Instant expiresAt) {
        certificates.put(domain, expiresAt);
    }

    public void reset(String domain) {
        failures.remove(domain);
        certificates.remove(domain);
    }

    @Override
    public String dnsFailure(String domain) {
        if (failures.containsKey(domain)) return failures.get(domain);
        return domain.endsWith(".invalid") ? "域名无法解析" : null;
    }

    @Override
    public Instant certificateExpiry(String domain) {
        return certificates.get(domain);
    }
}
