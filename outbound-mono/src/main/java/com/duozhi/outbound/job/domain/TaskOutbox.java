package com.duozhi.outbound.job.domain;

import com.duozhi.outbound.job.domain.enums.TaskState;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** Source fields and application defaults; no persistence framework state. */
public class TaskOutbox {
    private UUID id = UUID.randomUUID();
    private Integer tenantId;
    private String taskType;
    private String aggregateId;
    private String idempotencyKey;
    private String payloadJson = "{}";
    private TaskState state = TaskState.PENDING;
    private Integer attempts = 0;
    private Integer maxAttempts = 5;
    private LocalDateTime availableAt =
            LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
    private LocalDateTime lockedAt;
    private String leaseToken;
    private String lastError = "";
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

    public String getTaskType() {
        return taskType;
    }

    public void setTaskType(String value) {
        this.taskType = value;
    }

    public String getAggregateId() {
        return aggregateId;
    }

    public void setAggregateId(String value) {
        this.aggregateId = value;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String value) {
        this.idempotencyKey = value;
    }

    public String getPayloadJson() {
        return payloadJson;
    }

    public void setPayloadJson(String value) {
        this.payloadJson = value;
    }

    public TaskState getState() {
        return state;
    }

    public void setState(TaskState value) {
        this.state = value;
    }

    public Integer getAttempts() {
        return attempts;
    }

    public void setAttempts(Integer value) {
        this.attempts = value;
    }

    public Integer getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(Integer value) {
        this.maxAttempts = value;
    }

    public LocalDateTime getAvailableAt() {
        return availableAt;
    }

    public void setAvailableAt(LocalDateTime value) {
        this.availableAt = value;
    }

    public LocalDateTime getLockedAt() {
        return lockedAt;
    }

    public void setLockedAt(LocalDateTime value) {
        this.lockedAt = value;
    }

    public String getLeaseToken() {
        return leaseToken;
    }

    public void setLeaseToken(String value) {
        this.leaseToken = value;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String value) {
        this.lastError = value;
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
