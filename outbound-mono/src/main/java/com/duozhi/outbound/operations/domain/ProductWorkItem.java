package com.duozhi.outbound.operations.domain;

/** Source fields and application defaults; no persistence framework state. */
public class ProductWorkItem {
    private java.util.UUID id = java.util.UUID.randomUUID();
    private Integer tenantId;
    private String eventKey;
    private java.util.UUID callId;
    private String kind;
    private String state = "open";
    private String phone = "";
    private String detailJson = "{}";
    private Integer assignedTo;
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

    public String getEventKey() {
        return eventKey;
    }

    public void setEventKey(String value) {
        this.eventKey = value;
    }

    public java.util.UUID getCallId() {
        return callId;
    }

    public void setCallId(java.util.UUID value) {
        this.callId = value;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String value) {
        this.kind = value;
    }

    public String getState() {
        return state;
    }

    public void setState(String value) {
        this.state = value;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String value) {
        this.phone = value;
    }

    public String getDetailJson() {
        return detailJson;
    }

    public void setDetailJson(String value) {
        this.detailJson = value;
    }

    public Integer getAssignedTo() {
        return assignedTo;
    }

    public void setAssignedTo(Integer value) {
        this.assignedTo = value;
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
