package com.duozhi.outbound.marketing.link;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Offline interception check: only domains scripted through {@link #intercept} are blocked. */
@Component
@ConditionalOnProperty(
        name = "MARKETING_LINK_INTERCEPTION",
        havingValue = "mock",
        matchIfMissing = true)
public class MockInterceptionDetector implements InterceptionDetector {
    private final Map<String, String> intercepted = new ConcurrentHashMap<>();

    public void intercept(String domain, String reason) {
        intercepted.put(domain, reason);
    }

    public void reset(String domain) {
        intercepted.remove(domain);
    }

    @Override
    public String intercepted(String domain) {
        return intercepted.get(domain);
    }
}
