package com.duozhi.outbound.recording.domain;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** Source fields and application defaults; no persistence framework state. */
public class RecordingAsset {
    private Integer id;
    private Integer tenantId;
    private UUID callSessionId;
    private Integer attempt = 0;
    private String providerRecordingId;
    private String providerUrl = "";
    private String storageUri = "";
    private String state = "available";
    private Integer durationSec;
    private String mediaFormat = "";
    private Integer channelCount = 1;
    private String checksumSha256;
    private LocalDateTime retentionUntil;
    private LocalDateTime deletedAt;
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

    public Integer getAttempt() {
        return attempt;
    }

    public void setAttempt(Integer value) {
        this.attempt = value;
    }

    public String getProviderRecordingId() {
        return providerRecordingId;
    }

    public void setProviderRecordingId(String value) {
        this.providerRecordingId = value;
    }

    public String getProviderUrl() {
        return providerUrl;
    }

    public void setProviderUrl(String value) {
        this.providerUrl = value;
    }

    public String getStorageUri() {
        return storageUri;
    }

    public void setStorageUri(String value) {
        this.storageUri = value;
    }

    public String getState() {
        return state;
    }

    public void setState(String value) {
        this.state = value;
    }

    public Integer getDurationSec() {
        return durationSec;
    }

    public void setDurationSec(Integer value) {
        this.durationSec = value;
    }

    public String getMediaFormat() {
        return mediaFormat;
    }

    public void setMediaFormat(String value) {
        this.mediaFormat = value;
    }

    public Integer getChannelCount() {
        return channelCount;
    }

    public void setChannelCount(Integer value) {
        this.channelCount = value;
    }

    public String getChecksumSha256() {
        return checksumSha256;
    }

    public void setChecksumSha256(String value) {
        this.checksumSha256 = value;
    }

    public LocalDateTime getRetentionUntil() {
        return retentionUntil;
    }

    public void setRetentionUntil(LocalDateTime value) {
        this.retentionUntil = value;
    }

    public LocalDateTime getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(LocalDateTime value) {
        this.deletedAt = value;
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
