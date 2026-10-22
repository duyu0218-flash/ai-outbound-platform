package com.duozhi.outbound.customer.domain;

/** Source fields and application defaults; no persistence framework state. */
public class ContactImportJob {
    private java.util.UUID id = java.util.UUID.randomUUID();
    private Integer tenantId;
    private String requestKey;
    private String state = "processing";
    private String resultJson = "{}";
    private String lastError = "";
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

    public String getRequestKey() {
        return requestKey;
    }

    public void setRequestKey(String value) {
        this.requestKey = value;
    }

    public String getState() {
        return state;
    }

    public void setState(String value) {
        this.state = value;
    }

    public String getResultJson() {
        return resultJson;
    }

    public void setResultJson(String value) {
        this.resultJson = value;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String value) {
        this.lastError = value;
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
