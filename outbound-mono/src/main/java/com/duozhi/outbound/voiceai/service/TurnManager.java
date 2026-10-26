package com.duozhi.outbound.voiceai.service;

import com.duozhi.outbound.integration.support.Support;
import java.util.Map;

/** Sample-clock VAD; parameters are copied from the immutable call policy, never live settings. */
public final class TurnManager {
    public enum Signal {
        NONE,
        START,
        COMMIT,
        CLEAR
    }

    private final int rate, startMs, endMs, longEndMs, maxMs, idleMs, minimumRms;
    private final boolean adaptive;
    private long voicedSamples, silentSamples, totalSamples;
    private boolean speaking;
    private double noise;
    private String partial = "";

    public TurnManager(Map<String, Object> policy, int rate) {
        this.rate = rate;
        startMs = value(policy, "vad_start_ms", 150, 20, 1000);
        endMs = value(policy, "vad_end_ms", 400, 200, 2000);
        longEndMs = value(policy, "vad_long_end_ms", 800, endMs, 3000);
        maxMs = value(policy, "vad_max_input_ms", 20000, 1000, 60000);
        idleMs = value(policy, "vad_idle_clear_ms", 2000, 500, 10000);
        minimumRms = value(policy, "vad_rms", 200, 1, 32767);
        adaptive = !Boolean.FALSE.equals(policy.get("vad_adaptive"));
        noise = minimumRms / 3.0;
    }

    private static int value(Map<String, Object> p, String key, int fallback, int min, int max) {
        long n = Support.integer(p, key, fallback);
        if (n < min || n > max) throw new IllegalArgumentException("invalid " + key);
        return (int) n;
    }

    public synchronized void partial(String text) {
        partial = text;
    }

    public synchronized Signal audio(byte[] pcm) {
        int samples = pcm.length / 2;
        double energy = 0;
        for (int i = 0; i + 1 < pcm.length; i += 2) {
            short v = (short) ((pcm[i] & 255) | (pcm[i + 1] << 8));
            energy += (double) v * v;
        }
        double rms = Math.sqrt(energy / Math.max(1, samples));
        double threshold = adaptive ? Math.max(minimumRms, noise * 3) : minimumRms;
        boolean voiced = rms >= threshold;
        totalSamples += samples;
        Signal signal = Signal.NONE;
        if (voiced) {
            voicedSamples += samples;
            silentSamples = 0;
            if (!speaking && voicedSamples * 1000 >= (long) rate * startMs) {
                speaking = true;
                signal = Signal.START;
            }
        } else {
            silentSamples += samples;
            if (!speaking) {
                voicedSamples = 0;
                if (adaptive) noise = .98 * noise + .02 * rms;
            }
        }
        int silence =
                partial.matches("(?s).*(?:[0-9]|地址|号码|姓名|如果|但是|然后|不要)[^。！？!?]*$")
                        ? longEndMs
                        : endMs;
        if (speaking
                && (silentSamples * 1000 >= (long) rate * silence
                        || totalSamples * 1000 >= (long) rate * maxMs)) {
            reset();
            return Signal.COMMIT;
        }
        if (!speaking && totalSamples * 1000 >= (long) rate * idleMs) {
            reset();
            return Signal.CLEAR;
        }
        return signal;
    }

    public synchronized void reset() {
        speaking = false;
        voicedSamples = silentSamples = totalSamples = 0;
        partial = "";
    }
}
