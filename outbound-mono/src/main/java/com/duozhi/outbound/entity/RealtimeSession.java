package com.duozhi.outbound.entity;

/** Source fields and application defaults; no persistence framework state. */
public class RealtimeSession {
    private Integer id;
    private Integer tenantId;
    private java.util.UUID callSessionId;
    private String providerSessionId;
    private Integer attempt = 0;
    private com.duozhi.outbound.entity.enums.RealtimeState state = com.duozhi.outbound.entity.enums.RealtimeState.CREATED;
    private String codec = "pcm_s16le";
    private Integer sampleRate = 16000;
    private Integer channelCount = 1;
    private Integer lastEventSequence;
    private Integer turnSequence = 0;
    private String playbackId;
    private java.time.LocalDateTime startedAt;
    private java.time.LocalDateTime endedAt;
    private java.time.LocalDateTime createdAt = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    private java.time.LocalDateTime updatedAt = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);

    public Integer getId() { return id; }
    public void setId(Integer value) { this.id = value; }

    public Integer getTenantId() { return tenantId; }
    public void setTenantId(Integer value) { this.tenantId = value; }

    public java.util.UUID getCallSessionId() { return callSessionId; }
    public void setCallSessionId(java.util.UUID value) { this.callSessionId = value; }

    public String getProviderSessionId() { return providerSessionId; }
    public void setProviderSessionId(String value) { this.providerSessionId = value; }

    public Integer getAttempt() { return attempt; }
    public void setAttempt(Integer value) { this.attempt = value; }

    public com.duozhi.outbound.entity.enums.RealtimeState getState() { return state; }
    public void setState(com.duozhi.outbound.entity.enums.RealtimeState value) { this.state = value; }

    public String getCodec() { return codec; }
    public void setCodec(String value) { this.codec = value; }

    public Integer getSampleRate() { return sampleRate; }
    public void setSampleRate(Integer value) { this.sampleRate = value; }

    public Integer getChannelCount() { return channelCount; }
    public void setChannelCount(Integer value) { this.channelCount = value; }

    public Integer getLastEventSequence() { return lastEventSequence; }
    public void setLastEventSequence(Integer value) { this.lastEventSequence = value; }

    public Integer getTurnSequence() { return turnSequence; }
    public void setTurnSequence(Integer value) { this.turnSequence = value; }

    public String getPlaybackId() { return playbackId; }
    public void setPlaybackId(String value) { this.playbackId = value; }

    public java.time.LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(java.time.LocalDateTime value) { this.startedAt = value; }

    public java.time.LocalDateTime getEndedAt() { return endedAt; }
    public void setEndedAt(java.time.LocalDateTime value) { this.endedAt = value; }

    public java.time.LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(java.time.LocalDateTime value) { this.createdAt = value; }

    public java.time.LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(java.time.LocalDateTime value) { this.updatedAt = value; }
}
