package com.duozhi.outbound.script.domain;

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
    private java.time.LocalDateTime publishedAt;
    private java.time.LocalDateTime createdAt =
            java.time.LocalDateTime.now(java.time.ZoneOffset.UTC)
                    .truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    private java.time.LocalDateTime updatedAt =
            java.time.LocalDateTime.now(java.time.ZoneOffset.UTC)
                    .truncatedTo(java.time.temporal.ChronoUnit.MILLIS);

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

    public java.time.LocalDateTime getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(java.time.LocalDateTime value) {
        this.publishedAt = value;
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
