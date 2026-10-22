package com.duozhi.outbound.job.domain;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** Source fields and application defaults; no persistence framework state. */
public class TaskReceipt {
    private UUID id;
    private Integer tenantId;
    private String taskType;
    private String aggregateId;
    private String idempotencyKey;
    private LocalDateTime completedAt =
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

    public LocalDateTime getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(LocalDateTime value) {
        this.completedAt = value;
    }
}
