package com.duozhi.outbound.entity;

/** Source fields and application defaults; no persistence framework state. */
public class CallbackInboxWorker {
    private String id;
    private java.time.LocalDateTime heartbeatAt = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    private Long processed;
    private Double maxLatencyMs = 0d;

    public String getId() { return id; }
    public void setId(String value) { this.id = value; }

    public java.time.LocalDateTime getHeartbeatAt() { return heartbeatAt; }
    public void setHeartbeatAt(java.time.LocalDateTime value) { this.heartbeatAt = value; }

    public Long getProcessed() { return processed; }
    public void setProcessed(Long value) { this.processed = value; }

    public Double getMaxLatencyMs() { return maxLatencyMs; }
    public void setMaxLatencyMs(Double value) { this.maxLatencyMs = value; }
}
