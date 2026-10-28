package com.duozhi.outbound.messaging.service;

import java.util.*;

/** Supplier implementations must explicitly declare delivery, query and billing capabilities. */
public interface MessageAdapter {
    record Submission(
            String messageId,
            String form,
            String recipient,
            String content,
            Map<String, Object> options) {}

    record Result(String state, String providerId, long purchaseAmount, boolean simulated) {}

    String id();

    Set<String> forms();

    Result submit(Submission submission) throws Exception;

    Result query(String messageId) throws Exception;
}
