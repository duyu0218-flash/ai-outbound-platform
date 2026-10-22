package com.duozhi.outbound.callback.domain;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** Source fields and application defaults; no persistence framework state. */
public class WebhookEventIngest {
    private Integer id;
    private UUID callSessionId;
    private String eventType;
    private String source;
    private String providerEventKey;
    private Integer repeatCount = 1;
    private LocalDateTime createdAt =
            LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);

    public Integer getId() {
        return id;
    }

    public void setId(Integer value) {
        this.id = value;
    }

    public UUID getCallSessionId() {
        return callSessionId;
    }

    public void setCallSessionId(UUID value) {
        this.callSessionId = value;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String value) {
        this.eventType = value;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String value) {
        this.source = value;
    }

    public String getProviderEventKey() {
        return providerEventKey;
    }

    public void setProviderEventKey(String value) {
        this.providerEventKey = value;
    }

    public Integer getRepeatCount() {
        return repeatCount;
    }

    public void setRepeatCount(Integer value) {
        this.repeatCount = value;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime value) {
        this.createdAt = value;
    }
}
