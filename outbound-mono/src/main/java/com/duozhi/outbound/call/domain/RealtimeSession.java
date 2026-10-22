package com.duozhi.outbound.call.domain;

import com.duozhi.outbound.call.domain.enums.RealtimeState;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** Source fields and application defaults; no persistence framework state. */
public class RealtimeSession {
    private Integer id;
    private Integer tenantId;
    private UUID callSessionId;
    private String providerSessionId;
    private Integer attempt = 0;
    private RealtimeState state = RealtimeState.CREATED;
    private String codec = "pcm_s16le";
    private Integer sampleRate = 16000;
    private Integer channelCount = 1;
    private Integer lastEventSequence;
    private Integer turnSequence = 0;
    private String playbackId;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
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

    public UUID getCallSessionId() {
        return callSessionId;
    }

    public void setCallSessionId(UUID value) {
        this.callSessionId = value;
    }

    public String getProviderSessionId() {
        return providerSessionId;
    }

    public void setProviderSessionId(String value) {
        this.providerSessionId = value;
    }

    public Integer getAttempt() {
        return attempt;
    }

    public void setAttempt(Integer value) {
        this.attempt = value;
    }

    public RealtimeState getState() {
        return state;
    }

    public void setState(RealtimeState value) {
        this.state = value;
    }

    public String getCodec() {
        return codec;
    }

    public void setCodec(String value) {
        this.codec = value;
    }

    public Integer getSampleRate() {
        return sampleRate;
    }

    public void setSampleRate(Integer value) {
        this.sampleRate = value;
    }

    public Integer getChannelCount() {
        return channelCount;
    }

    public void setChannelCount(Integer value) {
        this.channelCount = value;
    }

    public Integer getLastEventSequence() {
        return lastEventSequence;
    }

    public void setLastEventSequence(Integer value) {
        this.lastEventSequence = value;
    }

    public Integer getTurnSequence() {
        return turnSequence;
    }

    public void setTurnSequence(Integer value) {
        this.turnSequence = value;
    }

    public String getPlaybackId() {
        return playbackId;
    }

    public void setPlaybackId(String value) {
        this.playbackId = value;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(LocalDateTime value) {
        this.startedAt = value;
    }

    public LocalDateTime getEndedAt() {
        return endedAt;
    }

    public void setEndedAt(LocalDateTime value) {
        this.endedAt = value;
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
