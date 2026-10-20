package com.duozhi.outbound.entity;

/** Source fields and application defaults; no persistence framework state. */
public class ScenarioVersion {
    private Integer id;
    private Integer tenantId;
    private Integer campaignId;
    private String policyJson = "{}";
    private Integer publishedBy;
    private java.time.LocalDateTime createdAt = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);

    public Integer getId() { return id; }
    public void setId(Integer value) { this.id = value; }

    public Integer getTenantId() { return tenantId; }
    public void setTenantId(Integer value) { this.tenantId = value; }

    public Integer getCampaignId() { return campaignId; }
    public void setCampaignId(Integer value) { this.campaignId = value; }

    public String getPolicyJson() { return policyJson; }
    public void setPolicyJson(String value) { this.policyJson = value; }

    public Integer getPublishedBy() { return publishedBy; }
    public void setPublishedBy(Integer value) { this.publishedBy = value; }

    public java.time.LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(java.time.LocalDateTime value) { this.createdAt = value; }
}
