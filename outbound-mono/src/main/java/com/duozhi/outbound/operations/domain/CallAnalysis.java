package com.duozhi.outbound.operations.domain;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** Source fields and application defaults; no persistence framework state. */
public class CallAnalysis {
    private Integer id;
    private Integer tenantId;
    private UUID callSessionId;
    private String resultCode = "unknown";
    private String sentiment = "neutral";
    private String intent = "unknown";
    private String summary = "";
    private Integer qaScore = 0;
    private String qaFlagsJson = "[]";
    private String structuredJson = "{}";
    private String automaticResultJson = "{}";
    private Boolean needsReview = false;
    private String reviewState = "auto";
    private Integer reviewedBy;
    private LocalDateTime reviewedAt;
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

    public String getResultCode() {
        return resultCode;
    }

    public void setResultCode(String value) {
        this.resultCode = value;
    }

    public String getSentiment() {
        return sentiment;
    }

    public void setSentiment(String value) {
        this.sentiment = value;
    }

    public String getIntent() {
        return intent;
    }

    public void setIntent(String value) {
        this.intent = value;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String value) {
        this.summary = value;
    }

    public Integer getQaScore() {
        return qaScore;
    }

    public void setQaScore(Integer value) {
        this.qaScore = value;
    }

    public String getQaFlagsJson() {
        return qaFlagsJson;
    }

    public void setQaFlagsJson(String value) {
        this.qaFlagsJson = value;
    }

    public String getStructuredJson() {
        return structuredJson;
    }

    public void setStructuredJson(String value) {
        this.structuredJson = value;
    }

    public String getAutomaticResultJson() {
        return automaticResultJson;
    }

    public void setAutomaticResultJson(String value) {
        this.automaticResultJson = value;
    }

    public Boolean getNeedsReview() {
        return needsReview;
    }

    public void setNeedsReview(Boolean value) {
        this.needsReview = value;
    }

    public String getReviewState() {
        return reviewState;
    }

    public void setReviewState(String value) {
        this.reviewState = value;
    }

    public Integer getReviewedBy() {
        return reviewedBy;
    }

    public void setReviewedBy(Integer value) {
        this.reviewedBy = value;
    }

    public LocalDateTime getReviewedAt() {
        return reviewedAt;
    }

    public void setReviewedAt(LocalDateTime value) {
        this.reviewedAt = value;
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
