package com.java3y.austin.handler.script.impl;

import cn.hutool.http.HttpRequest;
import com.alibaba.fastjson2.JSON;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.util.Collections;

/** Austin hands off a platform-owned message ID, never arbitrary recipients or content. */
@Component
public class OutboundSmsGateBridge {
    @Value("${outbound.smsgate.dispatch-url:}")
    private String url;
    @Value("${outbound.smsgate.token:}")
    private String token;

    public boolean dispatch(String messageId) {
        if (messageId == null || !messageId.matches("msg_[a-f0-9]{32}")) return false;
        if (token == null || token.length() < 32) return false;
        try {
            URI uri = URI.create(url);
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null
                    || !("https".equals(uri.getScheme()) || ("http".equals(uri.getScheme())
                    && ("localhost".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost()))))) return false;
            try (cn.hutool.http.HttpResponse response = HttpRequest.post(url)
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .setFollowRedirects(false).timeout(20000)
                    .body(JSON.toJSONString(Collections.singletonMap("message_id", messageId)))
                    .execute()) {
                // This records handoff only. Platform CMPP reports determine handset delivery.
                return response.getStatus() == 200;
            }
        } catch (Exception ignored) {
            // No recipient, content, token, or upstream response in logs; never fall back to another SMS provider.
            return false;
        }
    }
}
