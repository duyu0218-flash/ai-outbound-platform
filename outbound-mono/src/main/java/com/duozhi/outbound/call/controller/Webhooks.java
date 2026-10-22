package com.duozhi.outbound.call.controller;

import com.duozhi.outbound.auth.service.Crypto;
import com.duozhi.outbound.call.service.CallLifecycle;
import com.duozhi.outbound.callback.service.CallbackInbox;
import com.duozhi.outbound.common.config.Config;
import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.common.support.JsonSupport;
import com.duozhi.outbound.common.support.Values;
import com.duozhi.outbound.common.web.Contract;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/webhooks")
public class Webhooks {
    private final Config config;
    private final Contract contract;
    private final JsonSupport json;
    private final CallLifecycle lifecycle;
    private final CallbackInbox inbox;
    private final VoiceCallbacks callbacks;

    public Webhooks(
            Config config,
            Contract contract,
            JsonSupport json,
            CallLifecycle lifecycle,
            CallbackInbox inbox,
            VoiceCallbacks callbacks) {
        this.config = config;
        this.contract = contract;
        this.json = json;
        this.lifecycle = lifecycle;
        this.inbox = inbox;
        this.callbacks = callbacks;
    }

    private void verify(byte[] body, HttpServletRequest request, boolean sms) {
        if (body.length > config.number("CALLBACK_INBOX_BODY_BYTES", 262144))
            throw new ApiError(413, "callback body too large");
        String token = config.get(sms ? "SMS_WEBHOOK_TOKEN" : "TELEPHONY_WEBHOOK_TOKEN", "").trim();
        String secret =
                config.get(sms ? "SMS_WEBHOOK_SECRET" : "TELEPHONY_WEBHOOK_SECRET", "").trim();
        if (config.production() && (token.isEmpty() || secret.isEmpty()))
            throw new ApiError(503, "webhook credentials missing");
        if (!token.isEmpty() && !Crypto.equal(token, request.getHeader("x-webhook-token")))
            throw new ApiError(401, "invalid telephony webhook token");
        if (secret.isEmpty()) return;
        String stamp = request.getHeader("x-webhook-timestamp"),
                signature = request.getHeader("x-webhook-signature");
        long timestamp;
        try {
            timestamp = Long.parseLong(stamp);
        } catch (RuntimeException e) {
            throw new ApiError(401, "invalid telephony webhook timestamp");
        }
        long now = Instant.now().getEpochSecond(),
                age =
                        Math.max(
                                30,
                                Math.min(
                                        3600, config.number("WEBHOOK_SIGNATURE_MAX_AGE_SEC", 300)));
        if (timestamp < now - age || timestamp > now + age)
            throw new ApiError(401, "expired telephony webhook signature");
        byte[] prefix = (stamp + ".").getBytes(StandardCharsets.US_ASCII);
        byte[] signed = Arrays.copyOf(prefix, prefix.length + body.length);
        System.arraycopy(body, 0, signed, prefix.length, body.length);
        if (signature != null && signature.startsWith("sha256="))
            signature = signature.substring(7);
        if (!Crypto.equal(JsonSupport.hmac(secret, signed), signature))
            throw new ApiError(401, "invalid telephony webhook signature");
    }

    @PostMapping("/telephony/status")
    public Map<String, Object> status(@RequestBody
    byte[] raw, HttpServletRequest request) {
        verify(raw, request, false);
        Map<String, Object> body =
                contract.input(
                        "WebhookEvent", json.object(new String(raw, StandardCharsets.UTF_8)), true);
        body.put("call_id", UUID.fromString(body.get("call_id").toString()).toString());
        body.putIfAbsent("payload", new LinkedHashMap<String, Object>());
        if (config.flag("CALLBACK_INBOX_ENABLED", false))
            return inbox.receive("telephony_status", body);
        return lifecycle.status(body);
    }

    private Map<String, Object> receive(
            String kind, String schema, byte[] raw, HttpServletRequest request) {
        verify(raw, request, "sms_status".equals(kind));
        Map<String, Object> body =
                contract.input(schema, json.object(new String(raw, StandardCharsets.UTF_8)), true);
        if (body.get("call_id") != null)
            body.put("call_id", UUID.fromString(body.get("call_id").toString()).toString());
        if (config.flag("CALLBACK_INBOX_ENABLED", false) && !"sms_status".equals(kind))
            return inbox.receive(kind, body);
        return callbacks.handle(kind, body);
    }

    @PostMapping("/telephony/speech")
    public Map<String, Object> speech(@RequestBody
    byte[] raw, HttpServletRequest request) {
        return receive("telephony_speech", "SpeechWebhookEvent", raw, request);
    }

    @PostMapping("/telephony/transcript")
    public Map<String, Object> transcript(@RequestBody
    byte[] raw, HttpServletRequest request) {
        return receive("telephony_transcript", "WebhookEvent", raw, request);
    }

    @PostMapping("/telephony/media")
    public Map<String, Object> media(@RequestBody
    byte[] raw, HttpServletRequest request) {
        return receive("telephony_media", "MediaWebhookEvent", raw, request);
    }

    @PostMapping("/telephony/recording")
    public Map<String, Object> recording(@RequestBody
    byte[] raw, HttpServletRequest request) {
        return receive("telephony_recording", "WebhookEvent", raw, request);
    }

    @PostMapping("/sms/status")
    public Map<String, Object> sms(@RequestBody
    byte[] raw, HttpServletRequest request) {
        return receive("sms_status", "SmsStatusWebhook", raw, request);
    }

    @PostMapping("/telephony/dtmf")
    public Map<String, Object> dtmf(@RequestBody
    byte[] raw, HttpServletRequest request) {
        return receive("telephony_dtmf", "WebhookEvent", raw, request);
    }

    @PostMapping("/telephony/batch")
    @SuppressWarnings("unchecked")
    public Map<String, Object> batch(@RequestBody
    byte[] raw, HttpServletRequest request) {
        return receiveBatch(raw, request, 1);
    }

    @PostMapping("/telephony/batch-v2")
    public Map<String, Object> batchV2(@RequestBody
    byte[] raw, HttpServletRequest request) {
        return receiveBatch(raw, request, 2);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> receiveBatch(byte[] raw, HttpServletRequest request, int version) {
        verify(raw, request, false);
        if (version == 1 && raw.length > 65536)
            throw new ApiError(413, "callback batch exceeds 64 KiB");
        if (version == 2 && raw.length > 262144)
            throw new ApiError(413, "callback batch exceeds 256 KiB");
        Map<String, Object> input =
                contract.input(
                        version == 1 ? "TelephonyBatch" : "TelephonyBatchV2",
                        json.object(new String(raw, StandardCharsets.UTF_8)),
                        true);
        if (!config.flag("CALLBACK_INBOX_ENABLED", false))
            throw new ApiError(409, "durable callback batching is disabled");
        List<Map<String, Object>> events = (List<Map<String, Object>>) input.get("events");
        Set<Object> ids = new HashSet<>();
        Map<String, String> schemas =
                Values.map(
                        "status",
                        "WebhookEvent",
                        "transcript",
                        "WebhookEvent",
                        "speech",
                        "SpeechWebhookEvent",
                        "dtmf",
                        "WebhookEvent",
                        "media",
                        "MediaWebhookEvent",
                        "recording",
                        "WebhookEvent");
        for (Map<String, Object> event : events) {
            if (!ids.add(event.get("id"))) throw new ApiError(422, "duplicate batch event id");
            Map<String, Object> body =
                    contract.input(
                            schemas.get(event.get("kind")),
                            (Map<String, Object>) event.get("payload"),
                            true);
            body.put("call_id", UUID.fromString(body.get("call_id").toString()).toString());
            if ("WebhookEvent".equals(schemas.get(event.get("kind"))))
                body.putIfAbsent("payload", new LinkedHashMap<>());
            event.put("payload", body);
        }
        var ack = inbox.batch(events);
        ack.put("version", version);
        return ack;
    }
}
