package com.duozhi.outbound.recording.domain;

/** Source fields and application defaults; no persistence framework state. */
public class RecordingAsset {
    private Integer id;
    private Integer tenantId;
    private java.util.UUID callSessionId;
    private Integer attempt = 0;
    private String providerRecordingId;
    private String providerUrl = "";
    private String storageUri = "";
    private String state = "available";
    private Integer durationSec;
    private String mediaFormat = "";
    private Integer channelCount = 1;
    private String checksumSha256;
    private java.time.LocalDateTime retentionUntil;
    private java.time.LocalDateTime deletedAt;
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

    public java.util.UUID getCallSessionId() {
        return callSessionId;
    }

    public void setCallSessionId(java.util.UUID value) {
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

    public java.time.LocalDateTime getRetentionUntil() {
        return retentionUntil;
    }

    public void setRetentionUntil(java.time.LocalDateTime value) {
        this.retentionUntil = value;
    }

    public java.time.LocalDateTime getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(java.time.LocalDateTime value) {
        this.deletedAt = value;
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
