package com.duozhi.outbound.operations.domain;

/** Source fields and application defaults; no persistence framework state. */
public class CallbackAppointment {
    private java.util.UUID id = java.util.UUID.randomUUID();
    private Integer tenantId;
    private java.util.UUID sourceCallId;
    private String requestKey;
    private java.time.LocalDateTime scheduledAt;
    private String state = "confirmed";
    private Integer revision = 1;
    private java.util.UUID dialCallId;
    private java.time.LocalDateTime createdAt =
            java.time.LocalDateTime.now(java.time.ZoneOffset.UTC)
                    .truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    private java.time.LocalDateTime updatedAt =
            java.time.LocalDateTime.now(java.time.ZoneOffset.UTC)
                    .truncatedTo(java.time.temporal.ChronoUnit.MILLIS);

    public java.util.UUID getId() {
        return id;
    }

    public void setId(java.util.UUID value) {
        this.id = value;
    }

    public Integer getTenantId() {
        return tenantId;
    }

    public void setTenantId(Integer value) {
        this.tenantId = value;
    }

    public java.util.UUID getSourceCallId() {
        return sourceCallId;
    }

    public void setSourceCallId(java.util.UUID value) {
        this.sourceCallId = value;
    }

    public String getRequestKey() {
        return requestKey;
    }

    public void setRequestKey(String value) {
        this.requestKey = value;
    }

    public java.time.LocalDateTime getScheduledAt() {
        return scheduledAt;
    }

    public void setScheduledAt(java.time.LocalDateTime value) {
        this.scheduledAt = value;
    }

    public String getState() {
        return state;
    }

    public void setState(String value) {
        this.state = value;
    }

    public Integer getRevision() {
        return revision;
    }

    public void setRevision(Integer value) {
        this.revision = value;
    }

    public java.util.UUID getDialCallId() {
        return dialCallId;
    }

    public void setDialCallId(java.util.UUID value) {
        this.dialCallId = value;
    }

    public java.time.LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(java.time.LocalDateTime value) {
        this.createdAt = value;
    }

    public java.time.LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(java.time.LocalDateTime value) {
        this.updatedAt = value;
    }
}
