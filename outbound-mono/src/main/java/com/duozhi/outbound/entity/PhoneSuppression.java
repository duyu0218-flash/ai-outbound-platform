package com.duozhi.outbound.entity;

/** Source fields and application defaults; no persistence framework state. */
public class PhoneSuppression {
    private Integer id;
    private Integer tenantId;
    private String phone;
    private String reason = "";
    private java.util.UUID sourceCallId;
    private java.time.LocalDateTime createdAt = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);

    public Integer getId() { return id; }
    public void setId(Integer value) { this.id = value; }

    public Integer getTenantId() { return tenantId; }
    public void setTenantId(Integer value) { this.tenantId = value; }

    public String getPhone() { return phone; }
    public void setPhone(String value) { this.phone = value; }

    public String getReason() { return reason; }
    public void setReason(String value) { this.reason = value; }

    public java.util.UUID getSourceCallId() { return sourceCallId; }
    public void setSourceCallId(java.util.UUID value) { this.sourceCallId = value; }

    public java.time.LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(java.time.LocalDateTime value) { this.createdAt = value; }
}
