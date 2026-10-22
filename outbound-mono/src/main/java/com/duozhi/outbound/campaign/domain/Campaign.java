package com.duozhi.outbound.campaign.domain;

import com.duozhi.outbound.call.domain.enums.CallMode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/** Source fields and application defaults; no persistence framework state. */
public class Campaign {
    private Integer id;
    private Integer tenantId;
    private String name;
    private String script = "";
    private Integer scriptTemplateId;
    private Integer scriptFlowVersionId;
    private CallMode mode = CallMode.AI_HANDOFF;
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

    public String getName() {
        return name;
    }

    public void setName(String value) {
        this.name = value;
    }

    public String getScript() {
        return script;
    }

    public void setScript(String value) {
        this.script = value;
    }

    public Integer getScriptTemplateId() {
        return scriptTemplateId;
    }

    public void setScriptTemplateId(Integer value) {
        this.scriptTemplateId = value;
    }

    public Integer getScriptFlowVersionId() {
        return scriptFlowVersionId;
    }

    public void setScriptFlowVersionId(Integer value) {
        this.scriptFlowVersionId = value;
    }

    public CallMode getMode() {
        return mode;
    }

    public void setMode(CallMode value) {
        this.mode = value;
    }

    public Integer getConcurrency() {
        return concurrency;
    }

    public void setConcurrency(Integer value) {
        this.concurrency = value;
    }

    public Integer getRetryLimit() {
        return retryLimit;
    }

    public void setRetryLimit(Integer value) {
        this.retryLimit = value;
    }

    public Integer getRetryIntervalSec() {
        return retryIntervalSec;
    }

    public void setRetryIntervalSec(Integer value) {
        this.retryIntervalSec = value;
    }

    public Integer getAttemptIntervalSec() {
        return attemptIntervalSec;
    }

    public void setAttemptIntervalSec(Integer value) {
        this.attemptIntervalSec = value;
    }

    public Boolean getRecordingEnabled() {
        return recordingEnabled;
    }

    public void setRecordingEnabled(Boolean value) {
        this.recordingEnabled = value;
    }

    public Boolean getHangupSmsEnabled() {
        return hangupSmsEnabled;
    }

    public void setHangupSmsEnabled(Boolean value) {
        this.hangupSmsEnabled = value;
    }

    public String getVoiceAiPipeline() {
        return voiceAiPipeline;
    }

    public void setVoiceAiPipeline(String value) {
        this.voiceAiPipeline = value;
    }

    public Integer getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Integer value) {
        this.createdBy = value;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String value) {
        this.status = value;
    }

    public Boolean getDispatchEnabled() {
        return dispatchEnabled;
    }

    public void setDispatchEnabled(Boolean value) {
        this.dispatchEnabled = value;
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
