package com.duozhi.outbound.agent.domain;

import com.duozhi.outbound.agent.domain.enums.HandoffState;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** Source fields and application defaults; no persistence framework state. */
public class HandoffRequest {
    private Integer id;
    private Integer tenantId;
    private UUID callSessionId;
    private Integer assignedAgentId;
    private HandoffState state = HandoffState.WAITING;
    private String reason = "";
    private String targetGroup = "";
    private LocalDateTime requestedAt =
            LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
    private LocalDateTime respondedAt;
    private LocalDateTime completedAt;
    private LocalDateTime updatedAt =
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

    public Integer getAssignedAgentId() {
        return assignedAgentId;
    }

    public void setAssignedAgentId(Integer value) {
        this.assignedAgentId = value;
    }

    public HandoffState getState() {
        return state;
    }

    public void setState(HandoffState value) {
        this.state = value;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String value) {
        this.reason = value;
    }

    public String getTargetGroup() {
        return targetGroup;
    }

    public void setTargetGroup(String value) {
        this.targetGroup = value;
    }

    public LocalDateTime getRequestedAt() {
        return requestedAt;
    }

    public void setRequestedAt(LocalDateTime value) {
        this.requestedAt = value;
    }

    public LocalDateTime getRespondedAt() {
        return respondedAt;
    }

    public void setRespondedAt(LocalDateTime value) {
        this.respondedAt = value;
    }

    public LocalDateTime getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(LocalDateTime value) {
        this.completedAt = value;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime value) {
        this.updatedAt = value;
    }
}
