package com.duozhi.outbound.marketing.journey;

import com.duozhi.outbound.marketing.common.ContactPolicy;
import com.duozhi.outbound.marketing.common.SuppressionContactPolicy;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

/**
 * The admission every marketing sender sees: hard suppression first (delegated to the foundation),
 * then {@link FrequencyRules}.
 */
@Primary
@Service
public class JourneyContactPolicy implements ContactPolicy {
    private final SuppressionContactPolicy suppression;
    private final FrequencyRules frequency;

    public JourneyContactPolicy(SuppressionContactPolicy suppression, FrequencyRules frequency) {
        this.suppression = suppression;
        this.frequency = frequency;
    }

    @Override
    public Admission admit(long tenant, String e164, String phoneHash, String channel) {
        Admission base = suppression.admit(tenant, e164, phoneHash, channel);
        if (!base.allowed()) return base;
        FrequencyRules.Decision d = frequency.checkForSend(tenant, phoneHash, channel);
        return d.allowed() ? Admission.OK : Admission.deny(d.reason());
    }
}
