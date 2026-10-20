package com.duozhi.outbound.entity;

/** Source fields and application defaults; no persistence framework state. */
public class AuditLog {
    private Integer id;
    private Integer tenantId;
    private Integer actorUserId;
    private String actorUsername = "system";
    private String action;
    private String resourceType;
    private String resourceId;
    private String detail = "";
    private java.time.LocalDateTime createdAt = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);

    public Integer getId() { return id; }
    public void setId(Integer value) { this.id = value; }

    public Integer getTenantId() { return tenantId; }
    public void setTenantId(Integer value) { this.tenantId = value; }

    public Integer getActorUserId() { return actorUserId; }
    public void setActorUserId(Integer value) { this.actorUserId = value; }

    public String getActorUsername() { return actorUsername; }
    public void setActorUsername(String value) { this.actorUsername = value; }

    public String getAction() { return action; }
    public void setAction(String value) { this.action = value; }

    public String getResourceType() { return resourceType; }
    public void setResourceType(String value) { this.resourceType = value; }

    public String getResourceId() { return resourceId; }
    public void setResourceId(String value) { this.resourceId = value; }

    public String getDetail() { return detail; }
    public void setDetail(String value) { this.detail = value; }

    public java.time.LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(java.time.LocalDateTime value) { this.createdAt = value; }
}
