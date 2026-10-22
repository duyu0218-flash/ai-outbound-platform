package com.duozhi.outbound.customer.domain;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** Source fields and application defaults; no persistence framework state. */
public class PhoneSuppression {
    private Integer id;
    private Integer tenantId;
    private String phone;
    private String reason = "";
    private UUID sourceCallId;
    private LocalDateTime createdAt =
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

    public String getReason() {
        return reason;
    }

    public void setReason(String value) {
        this.reason = value;
    }

    public UUID getSourceCallId() {
        return sourceCallId;
    }

    public void setSourceCallId(UUID value) {
        this.sourceCallId = value;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime value) {
        this.createdAt = value;
    }
}
