package com.duozhi.outbound.entity.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

public enum CallMode {
    HUMAN_ONLY, AI_ONLY, AI_HANDOFF, AI_WITH_SMS, MIXED_HUMAN_FIRST;

    @JsonValue public String value() { return name().toLowerCase(Locale.ROOT); }
    @JsonCreator public static CallMode fromValue(String value) {
        for (CallMode candidate : values()) {
            if (candidate.name().equals(value) || candidate.value().equals(value)) return candidate;
        }
        throw new IllegalArgumentException("Invalid CallMode value: " + value);
    }
}
