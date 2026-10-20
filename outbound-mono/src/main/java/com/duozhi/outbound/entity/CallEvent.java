package com.duozhi.outbound.entity;

/** Source fields and application defaults; no persistence framework state. */
public class CallEvent {
    private Integer id;
    private java.util.UUID callSessionId;
    private String eventType;
    private String source = "platform";
    private String payload = "{}";
    private java.time.LocalDateTime createdAt = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);

    public Integer getId() { return id; }
    public void setId(Integer value) { this.id = value; }

    public java.util.UUID getCallSessionId() { return callSessionId; }
    public void setCallSessionId(java.util.UUID value) { this.callSessionId = value; }

    public String getEventType() { return eventType; }
    public void setEventType(String value) { this.eventType = value; }

    public String getSource() { return source; }
    public void setSource(String value) { this.source = value; }

    public String getPayload() { return payload; }
    public void setPayload(String value) { this.payload = value; }

    public java.time.LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(java.time.LocalDateTime value) { this.createdAt = value; }
}
