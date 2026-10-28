package com.duozhi.outbound.messaging.service;

import java.net.URI;
import java.util.Map;

public interface WebhookTransport {
    record Reply(int status, String challenge) {}

    Reply post(URI uri, Map<String, String> headers, byte[] body) throws Exception;
}
