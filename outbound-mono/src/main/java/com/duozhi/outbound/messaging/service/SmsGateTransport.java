package com.duozhi.outbound.messaging.service;

import java.util.List;
import java.util.function.Consumer;

/** Protocol boundary, replaced by a deterministic fake only in functional tests. */
public interface SmsGateTransport {
    record Part(String providerId, String state) {}

    List<Part> submit(String phone, String content) throws Exception;

    void onReport(Consumer<Part> sink);
}
