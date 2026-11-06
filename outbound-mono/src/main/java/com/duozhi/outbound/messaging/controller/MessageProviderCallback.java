package com.duozhi.outbound.messaging.controller;

import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.integration.support.Support;
import com.duozhi.outbound.messaging.service.*;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.springframework.web.bind.annotation.*;

@RestController
public class MessageProviderCallback {
    private final MessageChannels channels;
    private final MessageDelivery delivery;

    public MessageProviderCallback(MessageChannels channels, MessageDelivery delivery) {
        this.channels = channels;
        this.delivery = delivery;
    }

    @PostMapping("/api/v1/webhooks/messaging/{id}")
    public Object receive(@PathVariable
    String id, HttpServletRequest request)
            throws java.io.IOException {
        byte[] body = request.getInputStream().readNBytes(16385);
        if (body.length > 16384) throw new ApiError(413, "回执过大");
        String stamp = request.getHeader("X-Message-Timestamp"),
                signature = request.getHeader("X-Message-Signature");
        long seconds;
        try {
            seconds = Long.parseLong(stamp);
        } catch (Exception e) {
            throw new ApiError(401, "回执签名无效");
        }
        long now = Instant.now().getEpochSecond();
        if (seconds < now - 300 || seconds > now + 300) throw new ApiError(401, "回执时间戳失效");
        var channel = delivery.callbackChannel(id);
        if (!"http".equals(channel.get("protocol"))) throw new ApiError(401, "回执通道无效");
        String canonical = id + "\n" + stamp + "\n" + Support.hex(Support.sha(body)) + "\n";
        String expected =
                Support.hmac(
                        channels.credentials(channel).get("callback_secret").toString(),
                        canonical.getBytes(StandardCharsets.UTF_8));
        if (signature == null || !Support.equals(signature, expected))
            throw new ApiError(401, "回执签名无效");
        java.util.Map<String, Object> event;
        try {
            var mapper =
                    Support.JSON
                            .copy()
                            .enable(
                                    com.fasterxml.jackson.core.JsonParser.Feature
                                            .STRICT_DUPLICATE_DETECTION);
            var value = mapper.readValue(body, Object.class);
            if (!(value instanceof java.util.Map<?, ?>)) throw new IllegalArgumentException();
            event = Support.map(value);

        } catch (Exception e) {
            throw new ApiError(422, "回执 JSON 无效");
        }
        return delivery.ingest(id, event);
    }
}
