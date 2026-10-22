package com.duozhi.outbound.callback.domain;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** Source fields and application defaults; no persistence framework state. */
public class CallbackInbox {
    private Long id;
    private String receiptKey;
    private Integer partitionId;
    private UUID callId;
    private String kind;
    private String bodyJson;
    private String bodyDigest;
    private Integer bodyBytes;
    private String state = "pending";
    private LocalDateTime receivedAt =
            LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
    private LocalDateTime availableAt =
            LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
    private LocalDateTime completedAt;
    private Integer attempts = 0;
    private String errorType = "";

    public Long getId() {
        return id;
    }

    public void setId(Long value) {
        this.id = value;
    }

    public String getReceiptKey() {
        return receiptKey;
    }

    public void setReceiptKey(String value) {
        this.receiptKey = value;
    }

    public Integer getPartitionId() {
        return partitionId;
    }

    public void setPartitionId(Integer value) {
        this.partitionId = value;
    }

    public UUID getCallId() {
        return callId;
    }

    public void setCallId(UUID value) {
        this.callId = value;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String value) {
        this.kind = value;
    }

    public String getBodyJson() {
        return bodyJson;
    }

    public void setBodyJson(String value) {
        this.bodyJson = value;
    }

    public String getBodyDigest() {
        return bodyDigest;
    }

    public void setBodyDigest(String value) {
        this.bodyDigest = value;
    }

    public Integer getBodyBytes() {
        return bodyBytes;
    }

    public void setBodyBytes(Integer value) {
        this.bodyBytes = value;
    }

    public String getState() {
        return state;
    }

    public void setState(String value) {
        this.state = value;
    }

    public LocalDateTime getReceivedAt() {
        return receivedAt;
    }

    public void setReceivedAt(LocalDateTime value) {
        this.receivedAt = value;
    }

    public LocalDateTime getAvailableAt() {
        return availableAt;
    }

    public void setAvailableAt(LocalDateTime value) {
        this.availableAt = value;
    }

    public LocalDateTime getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(LocalDateTime value) {
        this.completedAt = value;
    }

    public Integer getAttempts() {
        return attempts;
    }

    public void setAttempts(Integer value) {
        this.attempts = value;
    }

    public String getErrorType() {
        return errorType;
    }

    public void setErrorType(String value) {
        this.errorType = value;
    }
}
