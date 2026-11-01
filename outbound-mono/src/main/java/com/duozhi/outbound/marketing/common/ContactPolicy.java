package com.duozhi.outbound.marketing.common;

/**
 * Admission before any marketing send or call. The foundation implementation enforces the
 * suppression lists; the journey module supplies a {@code @Primary} policy that adds frequency
 * caps, channel spacing and contact windows on top of this one.
 */
public interface ContactPolicy {
    record Admission(boolean allowed, String reason) {
        public static final Admission OK = new Admission(true, "");

        public static Admission deny(String reason) {
            return new Admission(false, reason);
        }
    }

    /**
     * @param e164 normalized phone (needed by the legacy phonesuppression table)
     * @param channel {@link OutcomeCodes#SMS} or {@link OutcomeCodes#CALL}
     */
    Admission admit(long tenant, String e164, String phoneHash, String channel);
}
