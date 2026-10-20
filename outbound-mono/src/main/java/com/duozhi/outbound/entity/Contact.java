package com.duozhi.outbound.entity;

/** Source fields and application defaults; no persistence framework state. */
public class Contact {
    private Integer id;
    private Integer tenantId;
    private String phone;
    private String name;
    private String tags = "";
    private com.duozhi.outbound.entity.enums.ConsentState consentState = com.duozhi.outbound.entity.enums.ConsentState.UNKNOWN;
    private java.time.LocalDateTime consentedAt;
    private String consentedBy;
    private Boolean dnc = false;
    private String dncReason;
    private String timezone = "Asia/Shanghai";
    private java.time.LocalDateTime createdAt = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    private java.time.LocalDateTime updatedAt = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);

    public Integer getId() { return id; }
    public void setId(Integer value) { this.id = value; }

    public Integer getTenantId() { return tenantId; }
    public void setTenantId(Integer value) { this.tenantId = value; }

    public String getPhone() { return phone; }
    public void setPhone(String value) { this.phone = value; }

    public String getName() { return name; }
    public void setName(String value) { this.name = value; }

    public String getTags() { return tags; }
    public void setTags(String value) { this.tags = value; }

    public com.duozhi.outbound.entity.enums.ConsentState getConsentState() { return consentState; }
    public void setConsentState(com.duozhi.outbound.entity.enums.ConsentState value) { this.consentState = value; }

    public java.time.LocalDateTime getConsentedAt() { return consentedAt; }
    public void setConsentedAt(java.time.LocalDateTime value) { this.consentedAt = value; }

    public String getConsentedBy() { return consentedBy; }
    public void setConsentedBy(String value) { this.consentedBy = value; }

    public Boolean getDnc() { return dnc; }
    public void setDnc(Boolean value) { this.dnc = value; }

    public String getDncReason() { return dncReason; }
    public void setDncReason(String value) { this.dncReason = value; }

    public String getTimezone() { return timezone; }
    public void setTimezone(String value) { this.timezone = value; }

    public java.time.LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(java.time.LocalDateTime value) { this.createdAt = value; }

    public java.time.LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(java.time.LocalDateTime value) { this.updatedAt = value; }
}
