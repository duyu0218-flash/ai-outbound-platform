package com.duozhi.outbound.entity;

/** Source fields and application defaults; no persistence framework state. */
public class TelephonyLine {
    private Integer id;
    private Integer tenantId;
    private String name;
    private String provider = "http";
    private String gatewayUrl = "";
    private String callerId = "";
    private Integer maxConcurrency = 10;
    private Integer priority = 100;
    private Integer weight = 1;
    private String credentialRef = "";
    private Boolean enabled = true;
    private java.time.LocalDateTime createdAt = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    private java.time.LocalDateTime updatedAt = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);

    public Integer getId() { return id; }
    public void setId(Integer value) { this.id = value; }

    public Integer getTenantId() { return tenantId; }
    public void setTenantId(Integer value) { this.tenantId = value; }

    public String getName() { return name; }
    public void setName(String value) { this.name = value; }

    public String getProvider() { return provider; }
    public void setProvider(String value) { this.provider = value; }

    public String getGatewayUrl() { return gatewayUrl; }
    public void setGatewayUrl(String value) { this.gatewayUrl = value; }

    public String getCallerId() { return callerId; }
    public void setCallerId(String value) { this.callerId = value; }

    public Integer getMaxConcurrency() { return maxConcurrency; }
    public void setMaxConcurrency(Integer value) { this.maxConcurrency = value; }

    public Integer getPriority() { return priority; }
    public void setPriority(Integer value) { this.priority = value; }

    public Integer getWeight() { return weight; }
    public void setWeight(Integer value) { this.weight = value; }

    public String getCredentialRef() { return credentialRef; }
    public void setCredentialRef(String value) { this.credentialRef = value; }

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean value) { this.enabled = value; }

    public java.time.LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(java.time.LocalDateTime value) { this.createdAt = value; }

    public java.time.LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(java.time.LocalDateTime value) { this.updatedAt = value; }
}
