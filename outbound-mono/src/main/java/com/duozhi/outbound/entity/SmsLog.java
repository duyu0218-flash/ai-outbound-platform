package com.duozhi.outbound.entity;

/** Source fields and application defaults; no persistence framework state. */
public class SmsLog {
    private Integer id;
    private Integer tenantId;
    private java.util.UUID callSessionId;
    private String toPhone;
    private String templateCode;
    private String content;
    private String state = "pending";
    private String providerMessageId;
    private String providerError;
    private java.time.LocalDateTime sentAt;
    private java.time.LocalDateTime createdAt = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    private java.time.LocalDateTime updatedAt = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);

    public Integer getId() { return id; }
    public void setId(Integer value) { this.id = value; }

    public Integer getTenantId() { return tenantId; }
    public void setTenantId(Integer value) { this.tenantId = value; }

    public java.util.UUID getCallSessionId() { return callSessionId; }
    public void setCallSessionId(java.util.UUID value) { this.callSessionId = value; }

    public String getToPhone() { return toPhone; }
    public void setToPhone(String value) { this.toPhone = value; }

    public String getTemplateCode() { return templateCode; }
    public void setTemplateCode(String value) { this.templateCode = value; }

    public String getContent() { return content; }
    public void setContent(String value) { this.content = value; }

    public String getState() { return state; }
    public void setState(String value) { this.state = value; }

    public String getProviderMessageId() { return providerMessageId; }
    public void setProviderMessageId(String value) { this.providerMessageId = value; }

    public String getProviderError() { return providerError; }
    public void setProviderError(String value) { this.providerError = value; }

    public java.time.LocalDateTime getSentAt() { return sentAt; }
    public void setSentAt(java.time.LocalDateTime value) { this.sentAt = value; }

    public java.time.LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(java.time.LocalDateTime value) { this.createdAt = value; }

    public java.time.LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(java.time.LocalDateTime value) { this.updatedAt = value; }
}
