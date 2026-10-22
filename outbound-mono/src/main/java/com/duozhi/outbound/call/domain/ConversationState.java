package com.duozhi.outbound.call.domain;

/** Source fields and application defaults; no persistence framework state. */
public class ConversationState {
    private Integer id;
    private Integer tenantId;
    private java.util.UUID callId;
    private Integer attempt = 0;
    private String policyJson = "{}";
    private Integer policyVersionId;
    private String dataJson = "{}";
    private Integer generation = 0;
    private java.time.LocalDateTime deadline;
    private String timerKind = "";
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

    public java.util.UUID getCallId() {
        return callId;
    }

    public void setCallId(java.util.UUID value) {
        this.callId = value;
    }

    public Integer getAttempt() {
        return attempt;
    }

    public void setAttempt(Integer value) {
        this.attempt = value;
    }

    public String getPolicyJson() {
        return policyJson;
    }

    public void setPolicyJson(String value) {
        this.policyJson = value;
    }

    public Integer getPolicyVersionId() {
        return policyVersionId;
    }

    public void setPolicyVersionId(Integer value) {
        this.policyVersionId = value;
    }

    public String getDataJson() {
        return dataJson;
    }

    public void setDataJson(String value) {
        this.dataJson = value;
    }

    public Integer getGeneration() {
        return generation;
    }

    public void setGeneration(Integer value) {
        this.generation = value;
    }

    public java.time.LocalDateTime getDeadline() {
        return deadline;
    }

    public void setDeadline(java.time.LocalDateTime value) {
        this.deadline = value;
    }

    public String getTimerKind() {
        return timerKind;
    }

    public void setTimerKind(String value) {
        this.timerKind = value;
    }

    public java.time.LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(java.time.LocalDateTime value) {
        this.updatedAt = value;
    }
}
