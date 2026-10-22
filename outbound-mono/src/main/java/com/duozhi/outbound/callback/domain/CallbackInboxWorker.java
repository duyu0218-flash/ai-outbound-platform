package com.duozhi.outbound.callback.domain;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/** Source fields and application defaults; no persistence framework state. */
public class CallbackInboxWorker {
    private String id;
    private LocalDateTime heartbeatAt =
            LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
    private Long processed;
    private Double maxLatencyMs = 0d;

    public String getId() {
        return id;
    }

    public void setId(String value) {
        this.id = value;
    }

    public LocalDateTime getHeartbeatAt() {
        return heartbeatAt;
    }

    public void setHeartbeatAt(LocalDateTime value) {
        this.heartbeatAt = value;
    }

    public Long getProcessed() {
        return processed;
    }

    public void setProcessed(Long value) {
        this.processed = value;
    }

    public Double getMaxLatencyMs() {
        return maxLatencyMs;
    }

    public void setMaxLatencyMs(Double value) {
        this.maxLatencyMs = value;
    }
}
