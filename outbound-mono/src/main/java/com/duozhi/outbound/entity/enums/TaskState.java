package com.duozhi.outbound.entity.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

public enum TaskState {
    PENDING, PROCESSING, COMPLETED, FAILED, DEAD;

    @JsonValue public String value() { return name().toLowerCase(Locale.ROOT); }
    @JsonCreator public static TaskState fromValue(String value) {
        for (TaskState candidate : values()) {
            if (candidate.name().equals(value) || candidate.value().equals(value)) return candidate;
        }
        throw new IllegalArgumentException("Invalid TaskState value: " + value);
    }
}
