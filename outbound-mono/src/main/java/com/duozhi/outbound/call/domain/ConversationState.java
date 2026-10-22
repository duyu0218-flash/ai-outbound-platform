package com.duozhi.outbound.call.domain;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** Source fields and application defaults; no persistence framework state. */
public class ConversationState {
    private Integer id;
    private Integer tenantId;
    private UUID callId;
    private Integer attempt = 0;
    private String policyJson = "{}";
    private Integer policyVersionId;
    private String dataJson = "{}";
    private Integer generation = 0;
    private LocalDateTime deadline;
    private String timerKind = "";
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

    public UUID getCallId() {
        return callId;
    }

    public void setCallId(UUID value) {
        this.callId = value;
    }

    public Integer getAttempt() {
        return attempt;
    }

    public void setAttempt(Integer value) {
        this.attempt = value;
    }

    public String getPolicyJson() {
        return policyJson;
    }

    public void setPolicyJson(String value) {
        this.policyJson = value;
    }

    public Integer getPolicyVersionId() {
        return policyVersionId;
    }

    public void setPolicyVersionId(Integer value) {
        this.policyVersionId = value;
    }

    public String getDataJson() {
        return dataJson;
    }

    public void setDataJson(String value) {
        this.dataJson = value;
    }

    public Integer getGeneration() {
        return generation;
    }

    public void setGeneration(Integer value) {
        this.generation = value;
    }

    public LocalDateTime getDeadline() {
        return deadline;
    }

    public void setDeadline(LocalDateTime value) {
        this.deadline = value;
    }

    public String getTimerKind() {
        return timerKind;
    }

    public void setTimerKind(String value) {
        this.timerKind = value;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime value) {
        this.updatedAt = value;
    }
}
