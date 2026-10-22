package com.duozhi.outbound.system.domain;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/** Source fields and application defaults; no persistence framework state. */
public class GatewayNode {
    private String id;
    private String endpoint;
    private Integer capacity = 0;
    private Boolean ready = false;
    private LocalDateTime checkedAt =
            LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
    private LocalDateTime nextDialAt;

    public String getId() {
        return id;
    }

    public void setId(String value) {
        this.id = value;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String value) {
        this.endpoint = value;
    }

    public Integer getCapacity() {
        return capacity;
    }

    public void setCapacity(Integer value) {
        this.capacity = value;
    }

    public Boolean getReady() {
        return ready;
    }

    public void setReady(Boolean value) {
        this.ready = value;
    }

    public LocalDateTime getCheckedAt() {
        return checkedAt;
    }

    public void setCheckedAt(LocalDateTime value) {
        this.checkedAt = value;
    }

    public LocalDateTime getNextDialAt() {
        return nextDialAt;
    }

    public void setNextDialAt(LocalDateTime value) {
        this.nextDialAt = value;
    }
}
