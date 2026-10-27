package com.duozhi.outbound.voiceai.service;

import com.duozhi.outbound.integration.config.Settings;
import com.duozhi.outbound.integration.support.Support;
import java.io.IOException;
import java.util.*;

/** Suite protocol, separate from Qwen Omni. No telephone entry point or automatic retry. */
public final class BailianDialog implements AutoCloseable {
    public interface Listener {
        void event(String name, Map<String, Object> output) throws Exception;

        void audio(byte[] pcm) throws Exception;
    }

    private final String task = UUID.randomUUID().toString();
    private final VendorSocket socket;
    private volatile String dialog = "", state = "Starting";
    private volatile boolean sending;

    public BailianDialog(
            Settings settings,
            String user,
            String script,
            Map<String, Object> business,
            Listener listener)
            throws Exception {
        var profiles = new VoiceAiProfiles(settings);
        String url =
                settings.get(
                        "VOICE_AI_BAILIAN_URL",
                        VoiceAiProfiles.DEFAULTS.get("VOICE_AI_BAILIAN_URL"));
        socket =
                new VendorSocket(
                        Support.client(),
                        profiles.endpoint(url, false),
                        Map.of("Authorization", "Bearer " + settings.secret("DASHSCOPE_API_KEY")),
                        (binary, data) -> {
                            if (binary) {
                                listener.audio(data);
                                return;
                            }
                            var message = Support.object(data);
                            var header = Support.map(message.get("header"));
                            if (!task.equals(header.get("task_id")))
                                throw new IOException("bailian task mismatch");
                            if ("task-failed".equals(header.get("event")))
                                throw new IOException("bailian task failed");
                            var output =
                                    Support.map(Support.map(message.get("payload")).get("output"));
                            String name = Support.str(output, "event", "");
                            String received = Support.str(output, "dialog_id", "");
                            if (!received.isBlank()) {
                                if (!dialog.isBlank() && !dialog.equals(received))
                                    throw new IOException("bailian dialog mismatch");
                                dialog = received;
                            }
                            if (name.equals("DialogStateChanged"))
                                state = Support.str(output, "state", "");
                            if (name.equals("SpeechEnded")) sending = false;
                            if (name.equals("Error") || name.equals("Stopped"))
                                throw new IOException("bailian session ended");
                            listener.event(name, output);
                        });
        try {
            var downstream =
                    new LinkedHashMap<String, Object>(
                            Map.of("sample_rate", 24000, "audio_format", "pcm"));
            String voice = settings.get("VOICE_AI_BAILIAN_VOICE", "");
            if (!voice.isBlank()) downstream.put("voice", voice);
            var payload = new LinkedHashMap<String, Object>();
            payload.put("task_group", "aigc");
            payload.put("task", "multimodal-generation");
            payload.put("function", "generation");
            payload.put("model", "multimodal-dialog");
            payload.put(
                    "input",
                    Map.of(
                            "directive",
                            "Start",
                            "workspace_id",
                            settings.get("VOICE_AI_BAILIAN_WORKSPACE_ID", ""),
                            "app_id",
                            settings.get("VOICE_AI_BAILIAN_APP_ID", "")));
            payload.put(
                    "parameters",
                    Map.of(
                            "upstream",
                            Map.of(
                                    "type",
                                    "AudioOnly",
                                    "mode",
                                    "push2talk",
                                    "audio_format",
                                    "pcm",
                                    "sample_rate",
                                    16000),
                            "downstream",
                            downstream,
                            "client_info",
                            Map.of("user_id", user),
                            "biz_params",
                            Map.of(
                                    "user_prompt_params",
                                    Map.of("business_script", script),
                                    "user_defined_params",
                                    business)));
            send("run-task", payload);
        } catch (Exception e) {
            close();
            throw e;
        }
    }

    private void send(String action, Object payload) throws Exception {
        socket.json(
                Map.of(
                        "header",
                        Map.of("action", action, "task_id", task, "streaming", "duplex"),
                        "payload",
                        payload));
    }

    public synchronized void command(String directive, Map<String, Object> extra) throws Exception {
        var input = new LinkedHashMap<String, Object>(extra);
        input.put("directive", directive);
        input.put("dialog_id", dialog);
        send("continue-task", Map.of("input", input));
    }

    public synchronized void beginSpeech() throws Exception {
        if (!state.equals("Listening") || sending)
            throw new IOException("bailian is not listening");
        command("SendSpeech", Map.of());
        sending = true;
    }

    public synchronized void audio(byte[] pcm) throws Exception {
        if (!sending || pcm.length == 0 || pcm.length > 6400 || pcm.length % 2 != 0)
            throw new IOException("invalid bailian audio state");
        socket.bytes(pcm);
    }

    public synchronized void endSpeech() throws Exception {
        if (sending) {
            state = "Thinking";
            command("StopSpeech", Map.of());
            sending = false;
        }
    }

    public synchronized void prompt(String text) throws Exception {
        if (!state.equals("Listening") || sending)
            throw new IOException("bailian is not listening");
        state = "Thinking";
        command("RequestToRespond", Map.of("type", "prompt", "text", text));
    }

    public synchronized boolean interrupt() throws Exception {
        if (!sending && state.equals("Listening")) return false;
        String directive = sending ? "CancelSpeech" : "RequestToSpeak";
        sending = false;
        state = "Interrupting";
        command(directive, Map.of());
        return true;
    }

    public void check() throws Exception {
        socket.check();
    }

    public String taskId() {
        return task;
    }

    public void close() {
        socket.close();
    }
}
