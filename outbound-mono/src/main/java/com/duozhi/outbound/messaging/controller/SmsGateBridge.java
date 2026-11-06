package com.duozhi.outbound.messaging.controller;

import com.duozhi.outbound.common.config.Config;
import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.messaging.service.SmsGateGateway;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

/** Dedicated service authentication; no public API application can invoke carrier dispatch. */
@RestController
public class SmsGateBridge {
    private final Config config;
    private final SmsGateGateway gateway;

    public SmsGateBridge(Config config, SmsGateGateway gateway) {
        this.config = config;
        this.gateway = gateway;
    }

    @PostMapping("/internal/messaging/smsgate/dispatch")
    public Object dispatch(
            @RequestHeader(value = "Authorization", required = false)
            String auth,
            @RequestBody
            Map<String, Object> body) {
        String token = config.get("SMS_BRIDGE_TOKEN", "");
        if (token.length() < 32
                || auth == null
                || !MessageDigest.isEqual(
                        ("Bearer " + token).getBytes(StandardCharsets.UTF_8),
                        auth.getBytes(StandardCharsets.UTF_8))) throw new ApiError(401, "短信桥接凭证无效");
        if (!body.keySet().equals(java.util.Set.of("message_id"))
                || !(body.get("message_id") instanceof String id)
                || !id.matches("msg_[a-f0-9]{32}")) throw new ApiError(422, "短信编号无效");
        return gateway.dispatch(id);
    }
}
