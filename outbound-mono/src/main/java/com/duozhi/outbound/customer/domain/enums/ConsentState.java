package com.duozhi.outbound.customer.domain.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

public enum ConsentState {
    CONSENTED,
    NOT_CONSENTED,
    REVOKED,
    UNKNOWN;

    @JsonValue
    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    @JsonCreator
    public static ConsentState fromValue(String value) {
        for (ConsentState candidate : values()) {
            if (candidate.name().equals(value) || candidate.value().equals(value)) return candidate;
        }
        throw new IllegalArgumentException("Invalid ConsentState value: " + value);
    }
}
