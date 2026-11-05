package com.duozhi.outbound.dial.domain;

import static org.junit.jupiter.api.Assertions.*;

import com.duozhi.outbound.common.exception.ApiError;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OpeningAdmissionFactsTest {
    @Test
    void rejectsStaleChangedVoiceCredentialTenantOrDisclosureAndCleansThreadContext() {
        var profile =
                Map.<String, Object>of(
                        "vendor",
                        "fixture",
                        "voice",
                        "one",
                        "credential_scope",
                        Map.of("tenant", 1, "revision", 1));
        var fact = OpeningAdmissionFacts.receipt(1, profile, "AI disclosure", new byte[] {1, 2});
        try (var scope = new OpeningAdmissionFacts(Map.of(fact.key(), fact))) {
            assertEquals(fact, OpeningAdmissionFacts.require(1, profile, "AI disclosure"));
            assertThrows(
                    ApiError.class,
                    () -> OpeningAdmissionFacts.require(2, profile, "AI disclosure"));
            assertThrows(
                    ApiError.class, () -> OpeningAdmissionFacts.require(1, profile, "changed"));
            var changed = new java.util.HashMap<>(profile);
            changed.put("credential_scope", Map.of("tenant", 1, "revision", 2));
            assertThrows(
                    ApiError.class,
                    () -> OpeningAdmissionFacts.require(1, changed, "AI disclosure"));
            var expired =
                    new OpeningAdmissionFacts.Receipt(
                            fact.key(), fact.sha256(), System.nanoTime() - 11_000_000_000L);
            try (var nested = new OpeningAdmissionFacts(Map.of(expired.key(), expired))) {
                assertThrows(
                        ApiError.class,
                        () -> OpeningAdmissionFacts.require(1, profile, "AI disclosure"));
            }
            assertEquals(fact, OpeningAdmissionFacts.require(1, profile, "AI disclosure"));
        }
        assertFalse(OpeningAdmissionFacts.active());
        assertThrows(
                ApiError.class, () -> OpeningAdmissionFacts.require(1, profile, "AI disclosure"));
    }
}
