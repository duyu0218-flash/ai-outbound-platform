package com.duozhi.outbound.voiceai.service;

import com.duozhi.outbound.integration.support.Support;
import java.util.*;

/** Confidence missing is unknown; damaged input cannot authorize business facts. */
public final class RecognitionQuality {
    private RecognitionQuality() {}

    public static Map<String, Object> assess(Map<String, Object> event, boolean audioGap) {
        var reasons = new ArrayList<String>();
        Object confidence = event.get("confidence");
        if (!(confidence instanceof Number)) reasons.add("confidence_unavailable");
        if (audioGap) reasons.add("audio_gap");
        String text = Support.str(event, "text", "");
        if (text.isBlank()) reasons.add("empty_transcript");
        return Map.of(
                "status",
                text.isBlank() ? "unusable" : audioGap ? "needs_confirmation" : "usable",
                "reasons",
                List.copyOf(reasons),
                "confidence_status",
                confidence instanceof Number ? "provided" : "unknown",
                "audio_gap",
                audioGap);
    }
}
