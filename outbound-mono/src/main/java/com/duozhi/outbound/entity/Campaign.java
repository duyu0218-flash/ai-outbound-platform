package com.duozhi.outbound.entity;

/** Source fields and application defaults; no persistence framework state. */
public class Campaign {
    private Integer id;
    private Integer tenantId;
    private String name;
    private String script = "";
    private Integer scriptTemplateId;
    private Integer scriptFlowVersionId;
    private com.duozhi.outbound.entity.enums.CallMode mode = com.duozhi.outbound.entity.enums.CallMode.AI_HANDOFF;
    private Integer concurrency = 5;
    private Integer retryLimit = 1;
    private Integer retryIntervalSec = 30;
    private Integer attemptIntervalSec = 1800;
    private Boolean recordingEnabled = true;
    private Boolean hangupSmsEnabled = true;
    private String voiceAiPipeline = "inherit";
    private Integer createdBy;
    private String status = "draft";
    private Boolean dispatchEnabled = false;
    private java.time.LocalDateTime createdAt = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    private java.time.LocalDateTime updatedAt = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);

    public Integer getId() { return id; }
    public void setId(Integer value) { this.id = value; }

    public Integer getTenantId() { return tenantId; }
    public void setTenantId(Integer value) { this.tenantId = value; }

    public String getName() { return name; }
    public void setName(String value) { this.name = value; }

    public String getScript() { return script; }
    public void setScript(String value) { this.script = value; }

    public Integer getScriptTemplateId() { return scriptTemplateId; }
    public void setScriptTemplateId(Integer value) { this.scriptTemplateId = value; }

    public Integer getScriptFlowVersionId() { return scriptFlowVersionId; }
    public void setScriptFlowVersionId(Integer value) { this.scriptFlowVersionId = value; }

    public com.duozhi.outbound.entity.enums.CallMode getMode() { return mode; }
    public void setMode(com.duozhi.outbound.entity.enums.CallMode value) { this.mode = value; }

    public Integer getConcurrency() { return concurrency; }
    public void setConcurrency(Integer value) { this.concurrency = value; }

    public Integer getRetryLimit() { return retryLimit; }
    public void setRetryLimit(Integer value) { this.retryLimit = value; }

    public Integer getRetryIntervalSec() { return retryIntervalSec; }
    public void setRetryIntervalSec(Integer value) { this.retryIntervalSec = value; }

    public Integer getAttemptIntervalSec() { return attemptIntervalSec; }
    public void setAttemptIntervalSec(Integer value) { this.attemptIntervalSec = value; }

    public Boolean getRecordingEnabled() { return recordingEnabled; }
    public void setRecordingEnabled(Boolean value) { this.recordingEnabled = value; }

    public Boolean getHangupSmsEnabled() { return hangupSmsEnabled; }
    public void setHangupSmsEnabled(Boolean value) { this.hangupSmsEnabled = value; }

    public String getVoiceAiPipeline() { return voiceAiPipeline; }
    public void setVoiceAiPipeline(String value) { this.voiceAiPipeline = value; }

    public Integer getCreatedBy() { return createdBy; }
    public void setCreatedBy(Integer value) { this.createdBy = value; }

    public String getStatus() { return status; }
    public void setStatus(String value) { this.status = value; }

    public Boolean getDispatchEnabled() { return dispatchEnabled; }
    public void setDispatchEnabled(Boolean value) { this.dispatchEnabled = value; }

    public java.time.LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(java.time.LocalDateTime value) { this.createdAt = value; }

    public java.time.LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(java.time.LocalDateTime value) { this.updatedAt = value; }
}
