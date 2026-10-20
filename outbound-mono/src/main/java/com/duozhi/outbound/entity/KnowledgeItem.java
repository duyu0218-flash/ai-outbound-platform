package com.duozhi.outbound.entity;

/** Source fields and application defaults; no persistence framework state. */
public class KnowledgeItem {
    private Integer id;
    private Integer tenantId;
    private String title;
    private String content;
    private String category = "default";
    private String keywords = "";
    private String source = "";
    private java.time.LocalDateTime validFrom;
    private java.time.LocalDateTime validUntil;
    private Integer campaignId;
    private Boolean isActive = true;
    private Integer version = 1;
    private Integer createdBy;
    private java.time.LocalDateTime createdAt = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    private java.time.LocalDateTime updatedAt = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);

    public Integer getId() { return id; }
    public void setId(Integer value) { this.id = value; }

    public Integer getTenantId() { return tenantId; }
    public void setTenantId(Integer value) { this.tenantId = value; }

    public String getTitle() { return title; }
    public void setTitle(String value) { this.title = value; }

    public String getContent() { return content; }
    public void setContent(String value) { this.content = value; }

    public String getCategory() { return category; }
    public void setCategory(String value) { this.category = value; }

    public String getKeywords() { return keywords; }
    public void setKeywords(String value) { this.keywords = value; }

    public String getSource() { return source; }
    public void setSource(String value) { this.source = value; }

    public java.time.LocalDateTime getValidFrom() { return validFrom; }
    public void setValidFrom(java.time.LocalDateTime value) { this.validFrom = value; }

    public java.time.LocalDateTime getValidUntil() { return validUntil; }
    public void setValidUntil(java.time.LocalDateTime value) { this.validUntil = value; }

    public Integer getCampaignId() { return campaignId; }
    public void setCampaignId(Integer value) { this.campaignId = value; }

    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean value) { this.isActive = value; }

    public Integer getVersion() { return version; }
    public void setVersion(Integer value) { this.version = value; }

    public Integer getCreatedBy() { return createdBy; }
    public void setCreatedBy(Integer value) { this.createdBy = value; }

    public java.time.LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(java.time.LocalDateTime value) { this.createdAt = value; }

    public java.time.LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(java.time.LocalDateTime value) { this.updatedAt = value; }
}
