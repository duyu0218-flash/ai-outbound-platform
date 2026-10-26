package com.duozhi.outbound.voiceai.service;

import com.duozhi.outbound.integration.support.Support;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;

/** Approval binds the immutable cumulative text and sentence to this response owner. */
public final class SegmentSafetyGate {
    private final ResponseScope owner;
    private final ContentSafety.Reviewer reviewer;
    private final TextCommitter.Sink sink;
    private final Consumer<Map<String, Object>> trace;
    private final TextCommitter committer;
    private final StringBuilder approved = new StringBuilder();
    private final String response = UUID.randomUUID().toString();
    private int sequence;

    public SegmentSafetyGate(
            ResponseScope owner,
            Map<String, Object> guard,
            ContentSafety.Reviewer reviewer,
            TextCommitter.Sink sink,
            Consumer<Map<String, Object>> trace) {
        this.owner = owner;
        this.reviewer = reviewer;
        this.sink = sink;
        this.trace = trace;
        committer = new TextCommitter(guard, this::approve);
    }

    private void approve(String sentence) throws Exception {
        owner.check();
        String cumulative = approved.toString() + sentence;
        long began = System.nanoTime();
        Map<String, Object> decision;
        try {
            decision = reviewer.review(cumulative, owner);
            owner.check();
            if (!"none".equals(decision.get("risk_level"))
                    || Support.str(decision, "request_id", "").isBlank())
                throw new ContentSafety.Failure("VOICE_SAFETY_RESULT_INVALID");
        } catch (Exception error) {
            trace.accept(
                    Map.of(
                            "status",
                            "blocked",
                            "segment_id",
                            response + ":" + (sequence + 1),
                            "code",
                            error instanceof ContentSafety.Failure f
                                    ? f.code
                                    : "VOICE_SAFETY_UNAVAILABLE"));
            throw error;
        }
        trace.accept(
                Map.of(
                        "status",
                        "approved",
                        "segment_id",
                        response + ":" + (++sequence),
                        "text_sha256",
                        Support.hex(Support.sha(cumulative.getBytes(StandardCharsets.UTF_8))),
                        "segment_sha256",
                        Support.hex(Support.sha(sentence.getBytes(StandardCharsets.UTF_8))),
                        "review_ms",
                        (System.nanoTime() - began) / 1000000,
                        "decision",
                        decision,
                        "playback_policy",
                        "review_then_tts"));
        owner.check();
        sink.accept(sentence);
        approved.append(sentence);
    }

    public void append(String delta) throws Exception {
        owner.check();
        committer.append(delta);
    }

    public String finish() throws Exception {
        owner.check();
        return committer.finish();
    }
}
