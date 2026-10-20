package com.duozhi.outbound.entity;

/** Source fields and application defaults; no persistence framework state. */
public class TaskOutbox {
    private java.util.UUID id = java.util.UUID.randomUUID();
    private Integer tenantId;
    private String taskType;
    private String aggregateId;
    private String idempotencyKey;
    private String payloadJson = "{}";
    private com.duozhi.outbound.entity.enums.TaskState state = com.duozhi.outbound.entity.enums.TaskState.PENDING;
    private Integer attempts = 0;
    private Integer maxAttempts = 5;
    private java.time.LocalDateTime availableAt = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    private java.time.LocalDateTime lockedAt;
    private String leaseToken;
    private String lastError = "";
    private java.time.LocalDateTime createdAt = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    private java.time.LocalDateTime updatedAt = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);

    public java.util.UUID getId() { return id; }
    public void setId(java.util.UUID value) { this.id = value; }

    public Integer getTenantId() { return tenantId; }
    public void setTenantId(Integer value) { this.tenantId = value; }

    public String getTaskType() { return taskType; }
    public void setTaskType(String value) { this.taskType = value; }

    public String getAggregateId() { return aggregateId; }
    public void setAggregateId(String value) { this.aggregateId = value; }

    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String value) { this.idempotencyKey = value; }

    public String getPayloadJson() { return payloadJson; }
    public void setPayloadJson(String value) { this.payloadJson = value; }

    public com.duozhi.outbound.entity.enums.TaskState getState() { return state; }
    public void setState(com.duozhi.outbound.entity.enums.TaskState value) { this.state = value; }

    public Integer getAttempts() { return attempts; }
    public void setAttempts(Integer value) { this.attempts = value; }

    public Integer getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(Integer value) { this.maxAttempts = value; }

    public java.time.LocalDateTime getAvailableAt() { return availableAt; }
    public void setAvailableAt(java.time.LocalDateTime value) { this.availableAt = value; }

    public java.time.LocalDateTime getLockedAt() { return lockedAt; }
    public void setLockedAt(java.time.LocalDateTime value) { this.lockedAt = value; }

    public String getLeaseToken() { return leaseToken; }
    public void setLeaseToken(String value) { this.leaseToken = value; }

    public String getLastError() { return lastError; }
    public void setLastError(String value) { this.lastError = value; }

    public java.time.LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(java.time.LocalDateTime value) { this.createdAt = value; }

    public java.time.LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(java.time.LocalDateTime value) { this.updatedAt = value; }
}
