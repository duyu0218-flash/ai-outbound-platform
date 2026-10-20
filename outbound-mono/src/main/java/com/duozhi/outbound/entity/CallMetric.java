package com.duozhi.outbound.entity;

/** Source fields and application defaults; no persistence framework state. */
public class CallMetric {
    private Integer id;
    private Integer tenantId;
    private java.util.UUID callSessionId;
    private String stage;
    private String provider = "";
    private Integer durationMs;
    private Boolean success = true;
    private String errorCode;
    private String detail = "";
    private java.time.LocalDateTime createdAt = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);

    public Integer getId() { return id; }
    public void setId(Integer value) { this.id = value; }

    public Integer getTenantId() { return tenantId; }
    public void setTenantId(Integer value) { this.tenantId = value; }

    public java.util.UUID getCallSessionId() { return callSessionId; }
    public void setCallSessionId(java.util.UUID value) { this.callSessionId = value; }

    public String getStage() { return stage; }
    public void setStage(String value) { this.stage = value; }

    public String getProvider() { return provider; }
    public void setProvider(String value) { this.provider = value; }

    public Integer getDurationMs() { return durationMs; }
    public void setDurationMs(Integer value) { this.durationMs = value; }

    public Boolean getSuccess() { return success; }
    public void setSuccess(Boolean value) { this.success = value; }

    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String value) { this.errorCode = value; }

    public String getDetail() { return detail; }
    public void setDetail(String value) { this.detail = value; }

    public java.time.LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(java.time.LocalDateTime value) { this.createdAt = value; }
}
