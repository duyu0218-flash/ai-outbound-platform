package com.duozhi.outbound.operations.domain;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** Source fields and application defaults; no persistence framework state. */
public class CallbackAppointment {
    private UUID id = UUID.randomUUID();
    private Integer tenantId;
    private UUID sourceCallId;
    private String requestKey;
    private LocalDateTime scheduledAt;
    private String state = "confirmed";
    private Integer revision = 1;
    private UUID dialCallId;
    private LocalDateTime createdAt =
            LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
    private LocalDateTime updatedAt =
            LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);

    public UUID getId() {
        return id;
    }

    public void setId(UUID value) {
        this.id = value;
    }

    public Integer getTenantId() {
        return tenantId;
    }

    public void setTenantId(Integer value) {
        this.tenantId = value;
    }

    public UUID getSourceCallId() {
        return sourceCallId;
    }

    public void setSourceCallId(UUID value) {
        this.sourceCallId = value;
    }

    public String getRequestKey() {
        return requestKey;
    }

    public void setRequestKey(String value) {
        this.requestKey = value;
    }

    public LocalDateTime getScheduledAt() {
        return scheduledAt;
    }

    public void setScheduledAt(LocalDateTime value) {
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

    public UUID getDialCallId() {
        return dialCallId;
    }

    public void setDialCallId(UUID value) {
        this.dialCallId = value;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime value) {
        this.createdAt = value;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime value) {
        this.updatedAt = value;
    }
}
