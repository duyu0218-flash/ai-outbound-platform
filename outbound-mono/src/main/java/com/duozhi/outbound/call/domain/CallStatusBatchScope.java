package com.duozhi.outbound.call.domain;

import com.duozhi.outbound.dial.domain.DialBatchScope;
import java.util.*;

/**
 * Initial-status facts loaded under partition/call locks for one transaction. The existing
 * lifecycle remains the only implementation of status transitions.
 */
public final class CallStatusBatchScope implements AutoCloseable {
    public boolean sharedResourcesLocked;
    public final Map<Long, Set<String>> terminalControls = new TreeMap<>();
    public final Map<String, List<Map<String, Object>>> handoffs = new HashMap<>();
    private static final ThreadLocal<CallStatusBatchScope> CURRENT = new ThreadLocal<>();
    public final Map<String, Map<String, Object>> calls = new HashMap<>(),
            usage = new HashMap<>(),
            events = new HashMap<>(),
            tasks = new HashMap<>(),
            receipts = new HashMap<>();
    private final CallStatusBatchScope previous = CURRENT.get();
    private final DialBatchScope settings = new DialBatchScope();

    public CallStatusBatchScope() {
        CURRENT.set(this);
    }

    public static CallStatusBatchScope current() {
        return CURRENT.get();
    }

    @Override
    public void close() {
        settings.close();
        if (previous == null) CURRENT.remove();
        else CURRENT.set(previous);
    }
}
