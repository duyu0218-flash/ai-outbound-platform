package com.duozhi.outbound.agent.domain.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

public enum HandoffState {
    WAITING,
    ACCEPTING,
    ACCEPTED,
    REJECTED,
    COMPLETED,
    EXPIRED;

    @JsonValue
    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    @JsonCreator
    public static HandoffState fromValue(String value) {
        for (HandoffState candidate : values()) {
            if (candidate.name().equals(value) || candidate.value().equals(value)) return candidate;
        }
        throw new IllegalArgumentException("Invalid HandoffState value: " + value);
    }
}
