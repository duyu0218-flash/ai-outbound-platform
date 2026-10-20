package com.duozhi.outbound.entity;

/** Source fields and application defaults; no persistence framework state. */
public class GatewayNode {
    private String id;
    private String endpoint;
    private Integer capacity = 0;
    private Boolean ready = false;
    private java.time.LocalDateTime checkedAt = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    private java.time.LocalDateTime nextDialAt;

    public String getId() { return id; }
    public void setId(String value) { this.id = value; }

    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String value) { this.endpoint = value; }

    public Integer getCapacity() { return capacity; }
    public void setCapacity(Integer value) { this.capacity = value; }

    public Boolean getReady() { return ready; }
    public void setReady(Boolean value) { this.ready = value; }

    public java.time.LocalDateTime getCheckedAt() { return checkedAt; }
    public void setCheckedAt(java.time.LocalDateTime value) { this.checkedAt = value; }

    public java.time.LocalDateTime getNextDialAt() { return nextDialAt; }
    public void setNextDialAt(java.time.LocalDateTime value) { this.nextDialAt = value; }
}
