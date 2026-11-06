package com.duozhi.outbound.messaging.service;

import java.util.*;

/**
 * A supplier adapter is transport only; authorization, state, budgets and receipts belong to the
 * platform.
 */
public interface ChannelProtocol {
    record Submission(
            String messageId,
            String form,
            String recipient,
            Map<String, Object> content,
            String senderRef,
            String templateRef,
            Map<String, String> assetRefs) {}

    record Part(String providerId, String state) {}

    record Result(String state, String providerId, List<Part> parts) {
        public static Result unknown() {
            return new Result("UNKNOWN", "", List.of());
        }
    }

    String protocol();

    Result submit(Map<String, Object> channel, Map<String, Object> credentials, Submission request)
            throws Exception;

    void probe(Map<String, Object> channel, Map<String, Object> credentials) throws Exception;

    default Result query(
            Map<String, Object> channel,
            Map<String, Object> credentials,
            String messageId,
            String providerId)
            throws Exception {
        return Result.unknown();
    }
}
