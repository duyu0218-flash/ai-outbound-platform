package com.duozhi.outbound.customer.domain;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** Source fields and application defaults; no persistence framework state. */
public class ContactImportJob {
    private UUID id = UUID.randomUUID();
    private Integer tenantId;
    private String requestKey;
    private String state = "processing";
    private String resultJson = "{}";
    private String lastError = "";
    private LocalDateTime createdAt =
            LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
    private LocalDateTime updatedAt =
            LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);

    public UUID getId() {
        return id;
    }

    public void setId(UUID value) {
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
