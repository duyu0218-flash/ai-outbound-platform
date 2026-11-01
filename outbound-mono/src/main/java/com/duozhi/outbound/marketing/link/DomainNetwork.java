package com.duozhi.outbound.marketing.link;

import java.time.Instant;

/**
 * DNS and TLS facts about a short-link domain. The default is {@link MockDomainNetwork}; the real
 * resolver ({@link NetworkDomainNetwork}) runs only with MARKETING_LINK_PROBE=network.
 */
public interface DomainNetwork {
    /** Null when the domain resolves; otherwise a short reason. */
    String dnsFailure(String domain);

    /** Expiry of the certificate served on 443, or null when it cannot be observed. */
    Instant certificateExpiry(String domain);
}
