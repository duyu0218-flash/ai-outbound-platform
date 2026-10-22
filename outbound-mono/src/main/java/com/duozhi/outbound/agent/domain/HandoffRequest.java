package com.duozhi.outbound.agent.domain;

/** Source fields and application defaults; no persistence framework state. */
public class HandoffRequest {
    private Integer id;
    private Integer tenantId;
    private java.util.UUID callSessionId;
    private Integer assignedAgentId;
    private com.duozhi.outbound.agent.domain.enums.HandoffState state =
            com.duozhi.outbound.agent.domain.enums.HandoffState.WAITING;
    private String reason = "";
    private String targetGroup = "";
    private java.time.LocalDateTime requestedAt =
            java.time.LocalDateTime.now(java.time.ZoneOffset.UTC)
                    .truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    private java.time.LocalDateTime respondedAt;
    private java.time.LocalDateTime completedAt;
    private java.time.LocalDateTime updatedAt =
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

    public Integer getAssignedAgentId() {
        return assignedAgentId;
    }

    public void setAssignedAgentId(Integer value) {
        this.assignedAgentId = value;
    }

    public com.duozhi.outbound.agent.domain.enums.HandoffState getState() {
        return state;
    }

    public void setState(com.duozhi.outbound.agent.domain.enums.HandoffState value) {
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

    public java.time.LocalDateTime getRequestedAt() {
        return requestedAt;
    }

    public void setRequestedAt(java.time.LocalDateTime value) {
        this.requestedAt = value;
    }

    public java.time.LocalDateTime getRespondedAt() {
        return respondedAt;
    }

    public void setRespondedAt(java.time.LocalDateTime value) {
        this.respondedAt = value;
    }

    public java.time.LocalDateTime getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(java.time.LocalDateTime value) {
        this.completedAt = value;
    }

    public java.time.LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(java.time.LocalDateTime value) {
        this.updatedAt = value;
    }
}
