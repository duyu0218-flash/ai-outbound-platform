package com.duozhi.outbound.entity.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

public enum RealtimeState {
    CREATED, LISTENING, THINKING, SPEAKING, INTERRUPTED, CLOSED;

    @JsonValue public String value() { return name().toLowerCase(Locale.ROOT); }
    @JsonCreator public static RealtimeState fromValue(String value) {
        for (RealtimeState candidate : values()) {
            if (candidate.name().equals(value) || candidate.value().equals(value)) return candidate;
        }
        throw new IllegalArgumentException("Invalid RealtimeState value: " + value);
    }
}
