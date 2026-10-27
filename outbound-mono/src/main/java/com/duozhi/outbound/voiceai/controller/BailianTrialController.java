package com.duozhi.outbound.voiceai.controller;

import com.duozhi.outbound.auth.domain.*;
import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.common.service.Transactions;
import com.duozhi.outbound.integration.support.Support;
import com.duozhi.outbound.operations.controller.Operations;
import com.duozhi.outbound.voiceai.service.*;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/voice-ai/bailian")
@AdminPermission("settings:write")
public class BailianTrialController {
    private final BailianTrials trials;
    private final Operations operations;
    private final Transactions tx;

    public BailianTrialController(BailianTrials trials, Operations operations, Transactions tx) {
        this.trials = trials;
        this.operations = operations;
        this.tx = tx;
    }

    @GetMapping("/readiness")
    public Object readiness(HttpServletRequest r) {
        return trials.readiness(Identity.from(r).tenant());
    }

    @GetMapping("/trials")
    public Object list(HttpServletRequest r) {
        return trials.db.list(Identity.from(r).tenant());
    }

    @PostMapping("/trials")
    public Object create(@RequestBody
    Map<String, Object> body, HttpServletRequest r) {
        return trials.create(Identity.from(r), body);
    }

    @GetMapping("/trials/{id}")
    public Object get(@PathVariable
    String id, HttpServletRequest r) {
        return trials.get(Identity.from(r).tenant(), id);
    }

    @PostMapping("/trials/{id}/end")
    public Object end(@PathVariable
    String id, HttpServletRequest r) {
        trials.end(Identity.from(r), id);
        return get(id, r);
    }

    @GetMapping("/trials/{id}/audio/{audio}")
    public ResponseEntity<byte[]> audio(
            @PathVariable
            String id, @PathVariable
            String audio, HttpServletRequest r) {
        long tenant = Identity.from(r).tenant();
        trials.get(tenant, id);
        var row = trials.db.audioGet(tenant, id, audio);
        if (row == null) throw new ApiError(404, "录音不存在");
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .contentType(MediaType.parseMediaType("audio/wav"))
                .body(
                        VoiceAiAudio.wav(
                                (byte[]) row.get("pcm"),
                                ((Number) row.get("sample_rate")).intValue()));
    }

    @PostMapping("/trials/{id}/publish")
    public Object publish(
            @PathVariable
            String id, @RequestBody
            Map<String, Object> body, HttpServletRequest r) {
        var actor = Identity.from(r);
        actor.requireUser();
        if (!Boolean.TRUE.equals(body.get("confirmed")))
            throw new ApiError(400, "请核对试聊结果后确认写入测试客户跟进");
        String note = BailianTrials.text(body, "note", 1000),
                intent = BailianTrials.text(body, "intent", 30);
        String next = Objects.toString(body.get("next_at"), "");
        if (!Set.of("interested", "declined", "uncertain").contains(intent))
            throw new ApiError(400, "无效意向");
        String hash =
                Support.hex(
                        Support.sha(
                                Support.json(
                                        Map.of("note", note, "intent", intent, "next_at", next))));
        return tx.serialized(
                () -> {
                    var row = trials.get(actor.tenant(), id);
                    if (Set.of("created", "active").contains(row.get("status")))
                        throw new ApiError(409, "请先结束试聊，再确认结果");
                    var prior =
                            Support.object(
                                    row.get("published_json")
                                            .toString()
                                            .getBytes(StandardCharsets.UTF_8));
                    if (!prior.isEmpty()) {
                        if (!hash.equals(prior.get("hash")))
                            throw new ApiError(409, "本次试聊已按其他内容写入，请在跟进页面修改");
                        return prior;
                    }
                    if ("[]".equals(row.get("events_json")))
                        throw new ApiError(409, "没有试聊证据，不能发布结果");
                    var evidence =
                            (List<?>)
                                    Support.object(
                                                    ("{\"events\":" + row.get("events_json") + "}")
                                                            .getBytes(StandardCharsets.UTF_8))
                                            .get("events");
                    if (evidence.stream()
                            .noneMatch(e -> "user_text".equals(Support.map(e).get("type"))))
                        throw new ApiError(409, "没有用户转写证据，不能发布结果");
                    long contact = ((Number) row.get("contact_id")).longValue();
                    var lead = operations.leadDetail(contact, r);
                    long revision = ((Number) lead.getOrDefault("revision", 0)).longValue();
                    if (lead.get("owner_id") == null) {
                        var claimed =
                                operations.change(
                                        contact,
                                        Map.of(
                                                "action",
                                                "claim",
                                                "event_key",
                                                "trial-claim-" + id,
                                                "revision",
                                                revision),
                                        r);
                        revision = ((Number) claimed.get("revision")).longValue();
                    }
                    var change = new LinkedHashMap<String, Object>();
                    change.put("action", "follow");
                    change.put("event_key", "trial-follow-" + id);
                    change.put("revision", revision);
                    change.put(
                            "stage",
                            intent.equals("declined")
                                    ? "invalid"
                                    : intent.equals("interested") ? "in_progress" : "open");
                    change.put("note", "[平台试聊 " + id + "] " + note);
                    if (!next.isBlank()) {
                        try {
                            var when = java.time.Instant.parse(next);
                            change.put(
                                    "next_at",
                                    java.time.LocalDateTime.ofInstant(
                                                    when, java.time.ZoneOffset.UTC)
                                            .toString());
                        } catch (Exception e) {
                            throw new ApiError(400, "预约时间格式无效");
                        }
                    }
                    operations.change(contact, change, r);
                    var result =
                            Map.<String, Object>of(
                                    "saved",
                                    true,
                                    "contact_id",
                                    contact,
                                    "hash",
                                    hash,
                                    "source",
                                    "browser_trial",
                                    "confirmed_by",
                                    actor.userId());
                    trials.db.published(
                            actor.tenant(),
                            id,
                            new String(Support.json(result), StandardCharsets.UTF_8));
                    return result;
                });
    }
}
