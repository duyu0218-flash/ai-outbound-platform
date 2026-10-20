package com.duozhi.outbound.entity;

/** Source fields and application defaults; no persistence framework state. */
public class CallSession {
    private java.util.UUID id = java.util.UUID.randomUUID();
    private Integer tenantId;
    private Integer campaignId;
    private Integer scriptFlowVersionId;
    private String flowNodeKey;
    private Integer contactId;
    private String campaignContactKey;
    private String phone;
    private com.duozhi.outbound.entity.enums.CallMode mode;
    private com.duozhi.outbound.entity.enums.CallStatus status = com.duozhi.outbound.entity.enums.CallStatus.CREATED;
    private Integer attempts = 0;
    private Integer maxAttempts = 1;
    private String lastError;
    private Integer humanAgentId;
    private String handoffReason;
    private String recordingUrl;
    private String aiSessionId;
    private String telephonyCallId;
    private Integer telephonyLineId;
    private String gatewayNodeId;
    private String gatewayEndpoint;
    private String conversationId;
    private String voiceAiPipeline = "pending";
    private String lastTranscript;
    private String summary;
    private java.time.LocalDateTime startedAt;
    private java.time.LocalDateTime finishedAt;
    private java.time.LocalDateTime nextAttemptAt;
    private java.time.LocalDateTime createdAt = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    private java.time.LocalDateTime updatedAt = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);

    public java.util.UUID getId() { return id; }
    public void setId(java.util.UUID value) { this.id = value; }

    public Integer getTenantId() { return tenantId; }
    public void setTenantId(Integer value) { this.tenantId = value; }

    public Integer getCampaignId() { return campaignId; }
    public void setCampaignId(Integer value) { this.campaignId = value; }

    public Integer getScriptFlowVersionId() { return scriptFlowVersionId; }
    public void setScriptFlowVersionId(Integer value) { this.scriptFlowVersionId = value; }

    public String getFlowNodeKey() { return flowNodeKey; }
    public void setFlowNodeKey(String value) { this.flowNodeKey = value; }

    public Integer getContactId() { return contactId; }
    public void setContactId(Integer value) { this.contactId = value; }

    public String getCampaignContactKey() { return campaignContactKey; }
    public void setCampaignContactKey(String value) { this.campaignContactKey = value; }

    public String getPhone() { return phone; }
    public void setPhone(String value) { this.phone = value; }

    public com.duozhi.outbound.entity.enums.CallMode getMode() { return mode; }
    public void setMode(com.duozhi.outbound.entity.enums.CallMode value) { this.mode = value; }

    public com.duozhi.outbound.entity.enums.CallStatus getStatus() { return status; }
    public void setStatus(com.duozhi.outbound.entity.enums.CallStatus value) { this.status = value; }

    public Integer getAttempts() { return attempts; }
    public void setAttempts(Integer value) { this.attempts = value; }

    public Integer getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(Integer value) { this.maxAttempts = value; }

    public String getLastError() { return lastError; }
    public void setLastError(String value) { this.lastError = value; }

    public Integer getHumanAgentId() { return humanAgentId; }
    public void setHumanAgentId(Integer value) { this.humanAgentId = value; }

    public String getHandoffReason() { return handoffReason; }
    public void setHandoffReason(String value) { this.handoffReason = value; }

    public String getRecordingUrl() { return recordingUrl; }
    public void setRecordingUrl(String value) { this.recordingUrl = value; }

    public String getAiSessionId() { return aiSessionId; }
    public void setAiSessionId(String value) { this.aiSessionId = value; }

    public String getTelephonyCallId() { return telephonyCallId; }
    public void setTelephonyCallId(String value) { this.telephonyCallId = value; }

    public Integer getTelephonyLineId() { return telephonyLineId; }
    public void setTelephonyLineId(Integer value) { this.telephonyLineId = value; }

    public String getGatewayNodeId() { return gatewayNodeId; }
    public void setGatewayNodeId(String value) { this.gatewayNodeId = value; }

    public String getGatewayEndpoint() { return gatewayEndpoint; }
    public void setGatewayEndpoint(String value) { this.gatewayEndpoint = value; }

    public String getConversationId() { return conversationId; }
    public void setConversationId(String value) { this.conversationId = value; }

    public String getVoiceAiPipeline() { return voiceAiPipeline; }
    public void setVoiceAiPipeline(String value) { this.voiceAiPipeline = value; }

    public String getLastTranscript() { return lastTranscript; }
    public void setLastTranscript(String value) { this.lastTranscript = value; }

    public String getSummary() { return summary; }
    public void setSummary(String value) { this.summary = value; }

    public java.time.LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(java.time.LocalDateTime value) { this.startedAt = value; }

    public java.time.LocalDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(java.time.LocalDateTime value) { this.finishedAt = value; }

    public java.time.LocalDateTime getNextAttemptAt() { return nextAttemptAt; }
    public void setNextAttemptAt(java.time.LocalDateTime value) { this.nextAttemptAt = value; }

    public java.time.LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(java.time.LocalDateTime value) { this.createdAt = value; }

    public java.time.LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(java.time.LocalDateTime value) { this.updatedAt = value; }
}
