package com.duozhi.outbound.auth.domain;

/** Source fields and application defaults; no persistence framework state. */
public class SysUser {
    private Integer id;
    private Integer tenantId;
    private String username;
    private String passwordHash = "";
    private String fullName;
    private String phone;
    private String role = "agent";
    private Boolean isSupervisor = false;
    private String agentStatus = "offline";
    private java.time.LocalDateTime lastSeenAt;
    private Integer failedLoginAttempts = 0;
    private java.time.LocalDateTime lockedUntil;
    private Integer tokenVersion = 0;
    private java.time.LocalDateTime lastLoginAt;
    private Boolean enabled = true;
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

    public String getUsername() {
        return username;
    }

    public void setUsername(String value) {
        this.username = value;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String value) {
        this.passwordHash = value;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String value) {
        this.fullName = value;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String value) {
        this.phone = value;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String value) {
        this.role = value;
    }

    public Boolean getIsSupervisor() {
        return isSupervisor;
    }

    public void setIsSupervisor(Boolean value) {
        this.isSupervisor = value;
    }

    public String getAgentStatus() {
        return agentStatus;
    }

    public void setAgentStatus(String value) {
        this.agentStatus = value;
    }

    public java.time.LocalDateTime getLastSeenAt() {
        return lastSeenAt;
    }

    public void setLastSeenAt(java.time.LocalDateTime value) {
        this.lastSeenAt = value;
    }

    public Integer getFailedLoginAttempts() {
        return failedLoginAttempts;
    }

    public void setFailedLoginAttempts(Integer value) {
        this.failedLoginAttempts = value;
    }

    public java.time.LocalDateTime getLockedUntil() {
        return lockedUntil;
    }

    public void setLockedUntil(java.time.LocalDateTime value) {
        this.lockedUntil = value;
    }

    public Integer getTokenVersion() {
        return tokenVersion;
    }

    public void setTokenVersion(Integer value) {
        this.tokenVersion = value;
    }

    public java.time.LocalDateTime getLastLoginAt() {
        return lastLoginAt;
    }

    public void setLastLoginAt(java.time.LocalDateTime value) {
        this.lastLoginAt = value;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean value) {
        this.enabled = value;
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
