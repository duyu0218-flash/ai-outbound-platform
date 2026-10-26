package com.duozhi.outbound.voiceai.service;

import com.duozhi.outbound.integration.support.Support;
import java.io.IOException;
import java.util.*;

/**
 * Sample-clock final aggregation. Observe is the default; provider finals remain original evidence.
 */
public final class InputTurnController {
    @FunctionalInterface
    public interface Sink {
        void accept(Map<String, Object> event) throws Exception;
    }

    private final TurnManager vad;
    private final boolean enforce;
    private final Sink sink;
    private final LinkedHashMap<String, Map<String, Object>> pending = new LinkedHashMap<>();
    private final LinkedHashSet<String> seen = new LinkedHashSet<>();
    private final String id = UUID.randomUUID().toString();
    private boolean candidate;
    private long sequence, candidates, commits, finals;

    public InputTurnController(Map<String, Object> policy, int rate, Sink sink) {
        vad = new TurnManager(policy, rate);
        enforce = "enforce".equals(policy.get("input_turn_controller"));
        this.sink = sink;
    }

    public synchronized void audio(byte[] pcm) throws Exception {
        var signal = vad.audio(pcm);
        if (vad.lastVoiced()) candidate = false;
        if (signal == TurnManager.Signal.COMMIT) {
            candidate = true;
            candidates++;
            commit();
        }
    }

    public synchronized void recognition(Map<String, Object> event) throws Exception {
        vad.partial(Support.str(event, "text", ""));
        if (!Boolean.TRUE.equals(event.get("is_final"))) {
            sink.accept(event);
            return;
        }
        String key = Support.str(event, "utterance_id", Support.str(event, "event_id", ""));
        if (!seen.add(key)) return;
        while (seen.size() > 256) seen.remove(seen.iterator().next());
        finals++;
        if (!enforce) {
            sink.accept(event);
            return;
        }
        if (pending.size() >= 32) throw new IOException("input turn final budget exceeded");
        pending.put(key, new LinkedHashMap<>(event));
        var raw = new LinkedHashMap<>(event);
        raw.put("provider_final", true);
        raw.put("turn_committed", false);
        sink.accept(raw);
        commit();
    }

    private void commit() throws Exception {
        if (!enforce || !candidate || pending.isEmpty()) return;
        var records = new ArrayList<>(pending.values());
        var event = new LinkedHashMap<>(records.getLast());
        String text =
                String.join("", records.stream().map(e -> Support.str(e, "text", "")).toList());
        String turn = id + ":" + (++sequence);
        event.put("text", text);
        if (records.getFirst().containsKey("start_ms"))
            event.put("start_ms", records.getFirst().get("start_ms"));
        else event.remove("start_ms");
        event.put("utterance_id", turn);
        event.put("event_id", turn + ":committed");
        event.put("turn_id", turn);
        event.put("turn_revision", sequence);
        event.put("provider_final", false);
        event.put("turn_committed", true);
        event.put("source_utterances", List.copyOf(pending.keySet()));
        boolean gap =
                records.stream()
                        .anyMatch(
                                e ->
                                        Boolean.TRUE.equals(
                                                Support.map(e.get("recognition_quality"))
                                                        .get("audio_gap")));
        event.put("recognition_quality", RecognitionQuality.assess(event, gap));
        pending.clear();
        candidate = false;
        commits++;
        sink.accept(event);
    }

    public synchronized Map<String, Object> evidence() {
        return Map.of(
                "mode",
                enforce ? "enforce" : "observe",
                "endpoint_candidates",
                candidates,
                "provider_finals",
                finals,
                "committed_turns",
                commits,
                "pending_finals",
                pending.size());
    }
}
