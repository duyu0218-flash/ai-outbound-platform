package com.duozhi.outbound.messaging.service;

import java.util.Map;

/** One tenant and one immutable provider channel; simulated work uses the empty channel. */
public record MessagePartition(long tenant, String channel) {
    public static final MessagePartition START = new MessagePartition(0, "");

    public MessagePartition {
        channel = channel == null ? "" : channel;
    }

    static MessagePartition from(Map<String, Object> row) {
        return new MessagePartition(
                ((Number) row.get("tenant_id")).longValue(),
                row.get("channel_id") == null ? "" : row.get("channel_id").toString());
    }
}
