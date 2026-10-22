package com.duozhi.outbound.call.domain;

/** Source fields and application defaults; no persistence framework state. */
public class CallUsage {
    private Integer id;
    private Integer tenantId;
    private java.util.UUID callSessionId;
    private Integer attempt;
    private java.time.LocalDateTime answeredAt;
    private java.time.LocalDateTime endedAt;
    private java.time.LocalDateTime aiEndedAt;
    private Double telephonySeconds;
    private Double aiSeconds;
    private String durationSource = "missing";
    private java.time.LocalDateTime createdAt =
            java.time.LocalDateTime.now(java.time.ZoneOffset.UTC)
                    .truncatedTo(java.time.temporal.ChronoUnit.MILLIS);

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

    public java.util.UUID getCallSessionId() {
        return callSessionId;
    }

    public void setCallSessionId(java.util.UUID value) {
        this.callSessionId = value;
    }

    public Integer getAttempt() {
        return attempt;
    }

    public void setAttempt(Integer value) {
        this.attempt = value;
    }

    public java.time.LocalDateTime getAnsweredAt() {
        return answeredAt;
    }

    public void setAnsweredAt(java.time.LocalDateTime value) {
        this.answeredAt = value;
    }

    public java.time.LocalDateTime getEndedAt() {
        return endedAt;
    }

    public void setEndedAt(java.time.LocalDateTime value) {
        this.endedAt = value;
    }

    public java.time.LocalDateTime getAiEndedAt() {
        return aiEndedAt;
    }

    public void setAiEndedAt(java.time.LocalDateTime value) {
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

    public java.time.LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(java.time.LocalDateTime value) {
        this.createdAt = value;
    }
}
