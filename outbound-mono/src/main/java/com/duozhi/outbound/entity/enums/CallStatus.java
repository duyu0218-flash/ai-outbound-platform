package com.duozhi.outbound.entity.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

public enum CallStatus {
    CREATED, QUEUED, DIALING, ANSWERED, IN_AI, WAITING_HUMAN, HANDOFF_TRANSFERRING, IN_HUMAN, COMPLETED, FAILED, NO_ANSWER, BUSY, VOICEMAIL;

    @JsonValue public String value() { return name().toLowerCase(Locale.ROOT); }
    @JsonCreator public static CallStatus fromValue(String value) {
        for (CallStatus candidate : values()) {
            if (candidate.name().equals(value) || candidate.value().equals(value)) return candidate;
        }
        throw new IllegalArgumentException("Invalid CallStatus value: " + value);
    }
}
