package com.duozhi.outbound.call.domain;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** Source fields and application defaults; no persistence framework state. */
public class SpeechTurn {
    private Integer id;
    private Integer tenantId;
    private UUID callSessionId;
    private String providerEventKey;
    private Integer turnIndex = 0;
    private Integer attempt = 0;
    private String speakerRole = "customer";
    private String channelId = "inbound";
    private String transcript = "";
    private String normalizedTranscript = "";
    private Boolean isFinal = false;
    private Double confidence;
    private Integer startMs;
    private Integer endMs;
    private String asrProvider = "";
    private LocalDateTime createdAt =
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

    public String getProviderEventKey() {
        return providerEventKey;
    }

    public void setProviderEventKey(String value) {
        this.providerEventKey = value;
    }

    public Integer getTurnIndex() {
        return turnIndex;
    }

    public void setTurnIndex(Integer value) {
        this.turnIndex = value;
    }

    public Integer getAttempt() {
        return attempt;
    }

    public void setAttempt(Integer value) {
        this.attempt = value;
    }

    public String getSpeakerRole() {
        return speakerRole;
    }

    public void setSpeakerRole(String value) {
        this.speakerRole = value;
    }

    public String getChannelId() {
        return channelId;
    }

    public void setChannelId(String value) {
        this.channelId = value;
    }

    public String getTranscript() {
        return transcript;
    }

    public void setTranscript(String value) {
        this.transcript = value;
    }

    public String getNormalizedTranscript() {
        return normalizedTranscript;
    }

    public void setNormalizedTranscript(String value) {
        this.normalizedTranscript = value;
    }

    public Boolean getIsFinal() {
        return isFinal;
    }

    public void setIsFinal(Boolean value) {
        this.isFinal = value;
    }

    public Double getConfidence() {
        return confidence;
    }

    public void setConfidence(Double value) {
        this.confidence = value;
    }

    public Integer getStartMs() {
        return startMs;
    }

    public void setStartMs(Integer value) {
        this.startMs = value;
    }

    public Integer getEndMs() {
        return endMs;
    }

    public void setEndMs(Integer value) {
        this.endMs = value;
    }

    public String getAsrProvider() {
        return asrProvider;
    }

    public void setAsrProvider(String value) {
        this.asrProvider = value;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime value) {
        this.createdAt = value;
    }
}
