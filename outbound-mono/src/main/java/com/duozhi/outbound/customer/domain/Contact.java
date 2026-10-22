package com.duozhi.outbound.customer.domain;

import com.duozhi.outbound.customer.domain.enums.ConsentState;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/** Source fields and application defaults; no persistence framework state. */
public class Contact {
    private Integer id;
    private Integer tenantId;
    private String phone;
    private String name;
    private String tags = "";
    private ConsentState consentState = ConsentState.UNKNOWN;
    private LocalDateTime consentedAt;
    private String consentedBy;
    private Boolean dnc = false;
    private String dncReason;
    private String timezone = "Asia/Shanghai";
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

    public String getPhone() {
        return phone;
    }

    public void setPhone(String value) {
        this.phone = value;
    }

    public String getName() {
        return name;
    }

    public void setName(String value) {
        this.name = value;
    }

    public String getTags() {
        return tags;
    }

    public void setTags(String value) {
        this.tags = value;
    }

    public ConsentState getConsentState() {
        return consentState;
    }

    public void setConsentState(ConsentState value) {
        this.consentState = value;
    }

    public LocalDateTime getConsentedAt() {
        return consentedAt;
    }

    public void setConsentedAt(LocalDateTime value) {
        this.consentedAt = value;
    }

    public String getConsentedBy() {
        return consentedBy;
    }

    public void setConsentedBy(String value) {
        this.consentedBy = value;
    }

    public Boolean getDnc() {
        return dnc;
    }

    public void setDnc(Boolean value) {
        this.dnc = value;
    }

    public String getDncReason() {
        return dncReason;
    }

    public void setDncReason(String value) {
        this.dncReason = value;
    }

    public String getTimezone() {
        return timezone;
    }

    public void setTimezone(String value) {
        this.timezone = value;
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
