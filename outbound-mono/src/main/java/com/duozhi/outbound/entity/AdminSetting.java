package com.duozhi.outbound.entity;

/** Source fields and application defaults; no persistence framework state. */
public class AdminSetting {
    private Integer id;
    private Integer tenantId;
    private String section;
    private String dataJson = "{}";
    private Integer updatedBy;
    private java.time.LocalDateTime updatedAt = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);

    public Integer getId() { return id; }
    public void setId(Integer value) { this.id = value; }

    public Integer getTenantId() { return tenantId; }
    public void setTenantId(Integer value) { this.tenantId = value; }

    public String getSection() { return section; }
    public void setSection(String value) { this.section = value; }

    public String getDataJson() { return dataJson; }
    public void setDataJson(String value) { this.dataJson = value; }

    public Integer getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(Integer value) { this.updatedBy = value; }

    public java.time.LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(java.time.LocalDateTime value) { this.updatedAt = value; }
}
