package com.duozhi.outbound.entity;

/** Source fields and application defaults; no persistence framework state. */
public class WebhookEventIngest {
    private Integer id;
    private java.util.UUID callSessionId;
    private String eventType;
    private String source;
    private String providerEventKey;
    private Integer repeatCount = 1;
    private java.time.LocalDateTime createdAt = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);

    public Integer getId() { return id; }
    public void setId(Integer value) { this.id = value; }

    public java.util.UUID getCallSessionId() { return callSessionId; }
    public void setCallSessionId(java.util.UUID value) { this.callSessionId = value; }

    public String getEventType() { return eventType; }
    public void setEventType(String value) { this.eventType = value; }

    public String getSource() { return source; }
    public void setSource(String value) { this.source = value; }

    public String getProviderEventKey() { return providerEventKey; }
    public void setProviderEventKey(String value) { this.providerEventKey = value; }

    public Integer getRepeatCount() { return repeatCount; }
    public void setRepeatCount(Integer value) { this.repeatCount = value; }

    public java.time.LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(java.time.LocalDateTime value) { this.createdAt = value; }
}
