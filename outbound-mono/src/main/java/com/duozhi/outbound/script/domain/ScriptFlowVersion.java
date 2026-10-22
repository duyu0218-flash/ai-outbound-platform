package com.duozhi.outbound.script.domain;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/** Source fields and application defaults; no persistence framework state. */
public class ScriptFlowVersion {
    private Integer id;
    private Integer tenantId;
    private Integer scriptTemplateId;
    private Integer version = 1;
    private String name = "";
    private String description = "";
    private String status = "draft";
    private String graphJson = "{}";
    private Integer createdBy;
    private LocalDateTime publishedAt;
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

    public Integer getScriptTemplateId() {
        return scriptTemplateId;
    }

    public void setScriptTemplateId(Integer value) {
        this.scriptTemplateId = value;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer value) {
        this.version = value;
    }

    public String getName() {
        return name;
    }

    public void setName(String value) {
        this.name = value;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String value) {
        this.description = value;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String value) {
        this.status = value;
    }

    public String getGraphJson() {
        return graphJson;
    }

    public void setGraphJson(String value) {
        this.graphJson = value;
    }

    public Integer getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Integer value) {
        this.createdBy = value;
    }

    public LocalDateTime getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(LocalDateTime value) {
        this.publishedAt = value;
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
