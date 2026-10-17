package com.duozhi.services;

import java.lang.management.ManagementFactory;
import java.util.*;

final class Metrics {
    private Metrics() { }
    static void append(StringBuilder text, String prefix, Map<String,?> values) {
        values.forEach((key, raw) -> {
            if (!key.matches("[a-zA-Z_][a-zA-Z0-9_]*")) throw new IllegalArgumentException("invalid metric name");
            Object value = raw instanceof Boolean flag ? (flag ? 1 : 0) : raw;
            if (value instanceof Number n && Double.isFinite(n.doubleValue())) text.append(prefix).append(key).append(' ').append(value).append('\n');
        });
    }
    static String jvm() {
        StringBuilder text = new StringBuilder();
        var memory = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        append(text, "ai_outbound_java_", Map.of("heap_used_bytes", memory.getUsed(), "heap_max_bytes", memory.getMax(),
            "threads", ManagementFactory.getThreadMXBean().getThreadCount(), "uptime_seconds", ManagementFactory.getRuntimeMXBean().getUptime() / 1000.0));
        long collections = 0, millis = 0;
        for (var collector : ManagementFactory.getGarbageCollectorMXBeans()) { if (collector.getCollectionCount() >= 0) collections += collector.getCollectionCount(); if (collector.getCollectionTime() >= 0) millis += collector.getCollectionTime(); }
        append(text, "ai_outbound_java_gc_", Map.of("collections_total", collections, "seconds_total", millis / 1000.0));
        return text.toString();
    }
}
