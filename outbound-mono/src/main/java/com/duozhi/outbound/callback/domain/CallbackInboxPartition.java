package com.duozhi.outbound.callback.domain;

/** Source fields and application defaults; no persistence framework state. */
public class CallbackInboxPartition {
    private Integer id;
    private Integer pendingCount = 0;
    private Integer pendingBytes = 0;

    public Integer getId() {
        return id;
    }

    public void setId(Integer value) {
        this.id = value;
    }

    public Integer getPendingCount() {
        return pendingCount;
    }

    public void setPendingCount(Integer value) {
        this.pendingCount = value;
    }

    public Integer getPendingBytes() {
        return pendingBytes;
    }

    public void setPendingBytes(Integer value) {
        this.pendingBytes = value;
    }
}
