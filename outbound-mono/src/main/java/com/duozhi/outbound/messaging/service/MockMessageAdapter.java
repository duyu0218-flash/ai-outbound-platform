package com.duozhi.outbound.messaging.service;

import java.util.*;
import org.springframework.stereotype.Component;

/** Offline adapter. No socket, real device, supplier credential or bill is involved. */
@Component
public class MockMessageAdapter implements MessageAdapter {
    public String id() {
        return "mock";
    }

    public Set<String> forms() {
        return Set.of("sms", "rcs", "yuexin", "oem_ip");
    }

    public Result submit(Submission s) {
        String outcome = Objects.toString(s.options().get("simulation_outcome"), "DELIVERED");
        if (!Set.of("DELIVERED", "REJECTED", "UNKNOWN").contains(outcome))
            throw new IllegalArgumentException("invalid simulation outcome");
        return new Result(outcome, "mock:" + s.messageId(), 0, true);
    }

    public Result query(String id) {
        return new Result("UNKNOWN", "mock:" + id, 0, true);
    }
}
