package com.duozhi.outbound.operations.domain;

/** Source fields and application defaults; no persistence framework state. */
public class CallAnalysis {
    private Integer id;
    private Integer tenantId;
    private java.util.UUID callSessionId;
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
    private java.time.LocalDateTime reviewedAt;
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

    public java.time.LocalDateTime getReviewedAt() {
        return reviewedAt;
    }

    public void setReviewedAt(java.time.LocalDateTime value) {
        this.reviewedAt = value;
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
