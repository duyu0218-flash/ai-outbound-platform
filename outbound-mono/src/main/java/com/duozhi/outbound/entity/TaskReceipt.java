package com.duozhi.outbound.entity;

/** Source fields and application defaults; no persistence framework state. */
public class TaskReceipt {
    private java.util.UUID id;
    private Integer tenantId;
    private String taskType;
    private String aggregateId;
    private String idempotencyKey;
    private java.time.LocalDateTime completedAt = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);

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

    public java.time.LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(java.time.LocalDateTime value) { this.completedAt = value; }
}
