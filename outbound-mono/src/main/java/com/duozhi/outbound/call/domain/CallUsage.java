package com.duozhi.outbound.call.domain;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** Source fields and application defaults; no persistence framework state. */
public class CallUsage {
    private Integer id;
    private Integer tenantId;
    private UUID callSessionId;
    private Integer attempt;
    private LocalDateTime answeredAt;
    private LocalDateTime endedAt;
    private LocalDateTime aiEndedAt;
    private Double telephonySeconds;
    private Double aiSeconds;
    private String durationSource = "missing";
    private LocalDateTime createdAt =
            LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);

    public Integer getId() {
        return id;
    }

    public void setId(Integer value) {
        this.id = value;
    }

    public Integer getTenantId() {
        return tenantId;
    }

    public void setTenantId(Integer value) {
        this.tenantId = value;
    }

    public UUID getCallSessionId() {
        return callSessionId;
    }

    public void setCallSessionId(UUID value) {
        this.callSessionId = value;
    }

    public Integer getAttempt() {
        return attempt;
    }

    public void setAttempt(Integer value) {
        this.attempt = value;
    }

    public LocalDateTime getAnsweredAt() {
        return answeredAt;
    }

    public void setAnsweredAt(LocalDateTime value) {
        this.answeredAt = value;
    }

    public LocalDateTime getEndedAt() {
        return endedAt;
    }

    public void setEndedAt(LocalDateTime value) {
        this.endedAt = value;
    }

    public LocalDateTime getAiEndedAt() {
        return aiEndedAt;
    }

    public void setAiEndedAt(LocalDateTime value) {
        this.aiEndedAt = value;
    }

    public Double getTelephonySeconds() {
        return telephonySeconds;
    }

    public void setTelephonySeconds(Double value) {
        this.telephonySeconds = value;
    }

    public Double getAiSeconds() {
        return aiSeconds;
    }

    public void setAiSeconds(Double value) {
        this.aiSeconds = value;
    }

    public String getDurationSource() {
        return durationSource;
    }

    public void setDurationSource(String value) {
        this.durationSource = value;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime value) {
        this.createdAt = value;
    }
}
