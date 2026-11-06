package com.duozhi.outbound.messaging;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.integration.support.Support;
import com.duozhi.outbound.messaging.controller.MessageProviderCallback;
import com.duozhi.outbound.messaging.service.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class MessageProviderCallbackTest {
    @Test
    void validReceiptDatabaseFailureIsNotMisclassifiedAsNonRetryableJsonFailure() throws Exception {
        var channels = mock(MessageChannels.class);
        var delivery = mock(MessageDelivery.class);
        var channel = Map.<String, Object>of("protocol", "http");
        when(delivery.callbackChannel("channel")).thenReturn(channel);
        when(channels.credentials(channel)).thenReturn(Map.of("callback_secret", "test-secret"));
        when(delivery.ingest(anyString(), anyMap()))
                .thenThrow(
                        new org.springframework.dao.DataAccessResourceFailureException(
                                "database temporarily unavailable"));
        var controller = new MessageProviderCallback(channels, delivery);
        var request = new MockHttpServletRequest();
        byte[] body = Support.json(Map.of("event_id", "evt1", "kind", "delivery"));
        String stamp = "" + Instant.now().getEpochSecond();
        request.setContent(body);
        request.addHeader("X-Message-Timestamp", stamp);
        request.addHeader(
                "X-Message-Signature",
                Support.hmac(
                        "test-secret",
                        ("channel\n" + stamp + "\n" + Support.hex(Support.sha(body)) + "\n")
                                .getBytes(StandardCharsets.UTF_8)));
        assertThatThrownBy(() -> controller.receive("channel", request))
                .isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class)
                .isNotInstanceOf(ApiError.class);
    }
}
