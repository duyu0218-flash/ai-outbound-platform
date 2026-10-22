package com.duozhi.outbound.system.domain;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** Source fields and application defaults; no persistence framework state. */
public class SmsLog {
    private Integer id;
    private Integer tenantId;
    private UUID callSessionId;
    private String toPhone;
    private String templateCode;
    private String content;
    private String state = "pending";
    private String providerMessageId;
    private String providerError;
    private LocalDateTime sentAt;
    private LocalDateTime createdAt =
            LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
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

    public String getToPhone() {
        return toPhone;
    }

    public void setToPhone(String value) {
        this.toPhone = value;
    }

    public String getTemplateCode() {
        return templateCode;
    }

    public void setTemplateCode(String value) {
        this.templateCode = value;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String value) {
        this.content = value;
    }

    public String getState() {
        return state;
    }

    public void setState(String value) {
        this.state = value;
    }

    public String getProviderMessageId() {
        return providerMessageId;
    }

    public void setProviderMessageId(String value) {
        this.providerMessageId = value;
    }

    public String getProviderError() {
        return providerError;
    }

    public void setProviderError(String value) {
        this.providerError = value;
    }

    public LocalDateTime getSentAt() {
        return sentAt;
    }

    public void setSentAt(LocalDateTime value) {
        this.sentAt = value;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime value) {
        this.createdAt = value;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime value) {
        this.updatedAt = value;
    }
}
