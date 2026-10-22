package com.duozhi.outbound.voice.controller;

import com.duozhi.outbound.common.config.Config;
import com.duozhi.outbound.dial.service.DialDailyCounters;
import com.duozhi.outbound.dial.service.DialDrainControl;
import com.duozhi.outbound.integration.config.Settings;
import com.duozhi.outbound.integration.config.UnifiedServiceSettings;
import com.duozhi.outbound.integration.dao.LedgerDatabase;
import com.duozhi.outbound.integration.dao.LocalLedger;
import com.duozhi.outbound.integration.support.Support;
import com.duozhi.outbound.media.service.MediaCluster;
import com.duozhi.outbound.voice.service.VoiceAdmissionBatchScope;
import com.duozhi.outbound.voice.service.VoiceAuthorizations;
import com.duozhi.outbound.voice.service.VoiceGateway;
import com.duozhi.outbound.voice.service.VoiceQuota;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.*;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
public class VoiceController {
    @Autowired
    private ObjectProvider<DialDrainControl> dialControl;

    @Autowired
    private ObjectProvider<DialDailyCounters> dailyCounters;

    private final Settings settings;
    public final VoiceGateway gateway;
    private final Semaphore downloads = new Semaphore(2);

    @Autowired
    public VoiceController(Config config, LedgerDatabase database) throws Exception {
        this.settings = new UnifiedServiceSettings(config, "voice");
        gateway = new VoiceGateway(settings, database);
        gateway.start();
    }

    public VoiceController(Settings settings) throws Exception {
        this.settings = settings;
        gateway = new VoiceGateway(settings);
        gateway.start();
    }

    public VoiceGateway gateway() {
        return gateway;
    }

    @PreDestroy
    public void close() throws Exception {
        gateway.close();
    }

    public Map<String, Object> health() {
        return Map.of(
                "status",
                "ok",
                "draining",
                gateway.draining,
                "driver",
                settings.get("VOICE_GATEWAY_DRIVER", "mock"),
                "voice_ai_pipeline",
                settings.get("VOICE_AI_PIPELINE", "legacy"),
                "media_protocol",
                settings.get("PIPECAT_MEDIA_PROTOCOL", "raw_pcm"),
                "pipecat_version",
                "",
                "pipecat_stt_provider",
                gateway.media == null
                        ? ""
                        : settings.get("PIPECAT_STT_PROVIDER", "openai-realtime"),
                "pipecat_active_sessions",
                gateway.media == null ? 0 : gateway.media.sessions().size(),
                "pipecat_max_active_sessions",
                settings.number("PIPECAT_MAX_ACTIVE_SESSIONS", 100, 1, 10000),
                "rtp_port_range",
                List.of(
                        settings.number("RTP_PORT_START", 20000, 1024, 65535),
                        settings.number("RTP_PORT_END", 30000, 1024, 65535)));
    }

    public Map<String, Object> ready() {
        if (!gateway.ready()) throw new Support.Failure(503, "voice gateway is not ready");
        return Map.of(
                "status",
                "ready",
                "node_id",
                settings.get("VOICE_NODE_ID", ""),
                "call_capacity",
                Math.min(
                        settings.number("VOICE_MAX_CONCURRENT", 20, 1, 100000),
                        gateway.media == null ? Integer.MAX_VALUE : gateway.media.capacity()),
                "driver",
                settings.get("VOICE_GATEWAY_DRIVER", "mock"),
                "voice_ai_pipeline",
                settings.get("VOICE_AI_PIPELINE", "legacy"),
                "callback_oldest_age_sec",
                gateway.ledger.summary().get("oldest_callback_age_sec"));
    }

    @PostMapping("/v1/call/{action}")
    public Map<String, Object> call(
            @PathVariable
            String action, @RequestBody
            byte[] body, HttpServletRequest request)
            throws Exception {
        return commandInternal(
                action,
                body,
                Map.of(
                        "Authorization",
                        Objects.toString(request.getHeader("Authorization"), ""),
                        "x-voice-timestamp",
                        Objects.toString(request.getHeader("x-voice-timestamp"), ""),
                        "x-voice-nonce",
                        Objects.toString(request.getHeader("x-voice-nonce"), ""),
                        "x-voice-signature",
                        Objects.toString(request.getHeader("x-voice-signature"), "")));
    }

    public Map<String, Object> commandInternal(
            String action, byte[] body, Map<String, String> headers) throws Exception {
        Support.auth(settings, "SERVICE_TOKEN", headers.get("Authorization"), !gateway.real());
        var payload = Support.object(body);
        boolean signed =
                gateway.real()
                        || VoiceAuthorizations.version(payload) == 2
                        || action.equals("dial-authorize");
        if (signed) {
            if (settings.secret("VOICE_COMMAND_SECRET").length() < 32)
                throw new Support.Failure(
                        503, "dial protocol requires an independent strong signing secret");
            gateway.security.command(
                    "/v1/call/" + action,
                    body,
                    headers.get("x-voice-timestamp"),
                    headers.get("x-voice-nonce"),
                    headers.get("x-voice-signature"));
        }
        return gateway.post(action, payload);
    }

    public record Command(String action, byte[] body, Map<String, String> headers) {}

    public record CommandResult(
            Map<String, Object> response, int status, boolean rejectedBeforeAdmission) {}

    public List<CommandResult> commandsInternalBatch(List<Command> commands) {
        if (commands.isEmpty() || commands.size() > 100)
            throw new Support.Failure(422, "invalid command batch size");
        var result = new ArrayList<CommandResult>();
        Runnable work =
                () -> {
                    for (var command : commands) {
                        if (Thread.currentThread().isInterrupted())
                            throw new Support.Failure(503, "command batch interrupted");
                        try {
                            result.add(
                                    new CommandResult(
                                            commandInternal(
                                                    command.action(),
                                                    command.body(),
                                                    command.headers()),
                                            200,
                                            false));
                        } catch (Support.Failure failure) {
                            if (settings.flag("JAVA_DIAL_PROFILE", false))
                                LoggerFactory.getLogger(getClass())
                                        .info(
                                                "voice_command_rejected action={} status={}"
                                                        + " reason={}",
                                                command.action(),
                                                failure.status,
                                                failure.getMessage());
                            result.add(
                                    new CommandResult(
                                            null,
                                            failure.status,
                                            failure.status == 429
                                                    && "false"
                                                            .equals(
                                                                    failure.headers.get(
                                                                            "X-Voice-Dial-Admitted"))));
                        } catch (Exception failure) {
                            result.add(new CommandResult(null, 500, false));
                        }
                    }
                };
        // Synthetic callbacks use the same durable writer, grouped in one commit.
        // Real PBX commands retain individual authorization/admission transactions.
        if (gateway.real()) work.run();
        else
            gateway.callbacks.atomic(
                    () -> {
                        if (settings.flag("VOICE_ADMISSION_BATCH_CACHE_ENABLED", false)
                                && (commands.stream().allMatch(c -> c.action().equals("dial"))
                                        || commands.stream()
                                                .allMatch(
                                                        c -> c.action().equals("dial-authorize"))))
                            try (var scope = new VoiceAdmissionBatchScope()) {
                                scope.preload(gateway.ledger, commands);
                                work.run();
                                scope.flush(gateway.ledger);
                            }
                        else work.run();
                    });
        return result;
    }

    private void admin(String auth) {
        Support.auth(
                settings,
                gateway.real() ? "VOICE_SECURITY_ADMIN_TOKEN" : "SERVICE_TOKEN",
                auth,
                !gateway.real());
    }

    @PostMapping("/v1/admin/dial-control/{action}")
    public Map<String, Object> dialControl(
            @PathVariable
            String action,
            @RequestHeader(value = "Authorization", required = false)
            String auth) {
        // A global maintenance operation always requires the independent global
        // credential, including in development; a tenant admin is insufficient.
        Support.auth(settings, "VOICE_SECURITY_ADMIN_TOKEN", auth, false);
        if (dialControl == null)
            throw new Support.Failure(503, "monolith dial control unavailable");
        var control = dialControl.getObject();
        return switch (action) {
            case "begin" -> control.begin();
            case "close-sending" -> control.closeSending();
            case "freeze-mock" -> control.freezeMockFacts();
            case "cancel" -> control.cancelDrain();
            case "status" -> control.snapshot();
            default -> throw new Support.Failure(404, "unknown dial control action");
        };
    }

    @PostMapping("/v1/admin/dial-counters/{action}")
    public Map<String, Object> dialCounters(
            @PathVariable
            String action,
            @RequestParam(defaultValue = "0")
            long generation,
            @RequestParam(defaultValue = "false")
            boolean read,
            @RequestHeader(value = "Authorization", required = false)
            String auth) {
        Support.auth(settings, "VOICE_SECURITY_ADMIN_TOKEN", auth, false);
        if (dailyCounters == null) throw new Support.Failure(503, "monolith counters unavailable");
        var counters = dailyCounters.getObject();
        return switch (action) {
            case "build" -> counters.build(Instant.now());
            case "verify" -> counters.verify(generation);
            case "publish" -> counters.publish(generation, read);
            default -> throw new Support.Failure(404, "unknown daily counter action");
        };
    }

    @PostMapping("/v1/admin/drain")
    Map<String, Object> drain(
            @RequestParam(defaultValue = "true")
            boolean enabled,
            @RequestHeader(value = "Authorization", required = false)
            String auth) {
        admin(auth);
        gateway.draining = enabled;
        return Map.of("draining", enabled);
    }

    @PostMapping("/v1/admin/security/stop")
    Map<String, Object> stop(
            @RequestBody
            Map<String, Object> payload,
            @RequestHeader(value = "Authorization", required = false)
            String auth) {
        admin(auth);
        if (!(payload.get("stopped") instanceof Boolean stopped))
            throw new Support.Failure(422, "stopped must be a boolean");
        gateway.ledger.stopped(stopped, Support.required(payload, "reason", 300));
        return gateway.ledger.summary();
    }

    @GetMapping("/v1/admin/security")
    Map<String, Object> security(
            @RequestHeader(value = "Authorization", required = false)
            String auth) {
        admin(auth);
        return gateway.ledger.summary();
    }

    @GetMapping("/v1/admin/capacity")
    Map<String, Object> capacity(
            @RequestHeader(value = "Authorization", required = false)
            String auth) {
        admin(auth);
        return Map.of(
                "node_id",
                settings.get("VOICE_NODE_ID", ""),
                "call_capacity",
                settings.number("VOICE_MAX_CONCURRENT", 20, 1, 100000),
                "cps",
                settings.number("VOICE_CPS", 2, 1, 1000),
                "routes",
                gateway.security.routes,
                "recording_disk",
                gateway.recordings.disk());
    }

    public Map<String, Map<String, ?>> metricValues() {
        Map<String, Map<String, ?>> result = new LinkedHashMap<>();
        var state = gateway.ledger.summary();
        result.put(
                "ai_outbound_voice_gateway_",
                Map.of(
                        "ready",
                        gateway.ready() ? 1 : 0,
                        "draining",
                        gateway.draining ? 1 : 0,
                        "active_calls",
                        state.get("active_attempts"),
                        "pending_callbacks",
                        state.get("pending_callbacks"),
                        "calls",
                        gateway.bindings.size()));
        result.put("ai_outbound_voice_security_", state);
        result.put("ai_outbound_voice_esl_", gateway.eventMetrics());
        result.put(
                "ai_outbound_voice_callback_",
                Map.of(
                        "http_inflight",
                        gateway.callbacks.deliveries(),
                        "events_inflight",
                        gateway.callbacks.inflight()));
        result.put("ai_outbound_voice_ledger_", Map.of("commits_total", gateway.ledger.commits()));
        result.put(
                "ai_outbound_pipecat_",
                Map.of(
                        "sessions",
                        gateway.media == null ? 0 : gateway.media.sessions().size(),
                        "session_capacity",
                        settings.number("PIPECAT_MAX_ACTIVE_SESSIONS", 100, 1, 10000)));
        if (gateway.media != null) result.put("ai_outbound_voice_", gateway.media.metrics());
        return result;
    }

    @GetMapping("/v1/recordings/{filename}")
    ResponseEntity<StreamingResponseBody> recording(
            @PathVariable
            String filename,
            @RequestParam
            long expires,
            @RequestParam
            String signature)
            throws Exception {
        var path = gateway.recordings.authorized(filename, expires, signature);
        if (!downloads.tryAcquire()) throw new Support.Failure(503, "recording download capacity");
        // Open before dispatch to bind the descriptor and release on opening failure.
        InputStream input;
        try {
            input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS);
        } catch (Exception error) {
            downloads.release();
            throw error;
        }
        StreamingResponseBody stream =
                output -> {
                    try (input) {
                        input.transferTo(output);
                    } finally {
                        downloads.release();
                    }
                };
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("audio/wav")).body(stream);
    }

    @PostMapping("/v1/recording-cleanup/{filename}")
    Map<String, Object> cleanup(
            @PathVariable
            String filename,
            @RequestBody
            Map<String, Object> payload,
            @RequestHeader(value = "Authorization", required = false)
            String auth)
            throws Exception {
        if (settings.secret("VOICE_RECORDING_CLEANUP_TOKEN").length() < 32)
            throw new Support.Failure(503, "cleanup credential unavailable");
        Support.auth(settings, "VOICE_RECORDING_CLEANUP_TOKEN", auth, false);
        return gateway.recordings.reclaim(
                filename,
                Support.required(payload, "checksum_sha256", 64),
                Support.integer(payload, "size_bytes", 0));
    }

    @GetMapping("/v1/admin/voice-permits")
    Map<String, Object> permits(
            @RequestHeader(value = "Authorization", required = false)
            String auth) {
        admin(auth);
        if (!settings.flag("VOICE_QUOTA_ENABLED", false))
            throw new Support.Failure(503, "shared voice permits unavailable");
        return new VoiceQuota(settings, gateway.ledger).snapshot();
    }

    @PostMapping("/v1/internal/media-events")
    Map<String, Object> mediaEvent(
            @RequestBody
            Map<String, Object> event,
            @RequestHeader(value = "Authorization", required = false)
            String auth) {
        rpc(auth);
        if (!(gateway.media instanceof MediaCluster cluster))
            throw new Support.Failure(503, "remote media ownership unavailable");
        cluster.validate(event);
        gateway.callbacks.post(
                Support.required(event, "url", 8192), Support.map(event.get("payload")));
        return Map.of("accepted", true);
    }

    private void rpc(String auth) {
        if (settings.secret("MEDIA_RPC_TOKEN").length() < 32)
            throw new Support.Failure(503, "independent media RPC credential required");
        Support.auth(settings, "MEDIA_RPC_TOKEN", auth, false);
    }

    @PostMapping("/v1/internal/voice-permits")
    Map<String, Object> voicePermit(
            @RequestBody
            Map<String, Object> payload,
            @RequestHeader(value = "Authorization", required = false)
            String auth) {
        rpc(auth);
        if (!settings.flag("VOICE_QUOTA_ENABLED", false))
            throw new Support.Failure(503, "shared voice budgets unavailable");
        String action = Support.required(payload, "action", 10),
                call = Support.required(payload, "call_id", 128),
                id = Support.required(payload, "permit_id", 128),
                worker = Support.str(payload, "worker_id", ""),
                epoch = Support.str(payload, "epoch", ""),
                sessionId = Support.required(payload, "session_id", 128),
                kind = Support.required(payload, "kind", 4);
        long attempt = Support.integer(payload, "attempt", 0);
        if (!Set.of("acquire", "release").contains(action)
                || attempt < 1
                || !Set.of("asr", "tts").contains(kind))
            throw new Support.Failure(422, "invalid voice permit request");
        if (action.equals("acquire")) {
            var session = gateway.media == null ? null : gateway.media.session(call);
            if (session == null
                    || !session.id.equals(sessionId)
                    || Support.integer(session.metadata, "attempt", 0) != attempt)
                throw new Support.Failure(409, "stale media session");
            if (gateway.media instanceof MediaCluster cluster) {
                var owner = cluster.owners.get(call);
                if (owner == null
                        || !owner.spec().id().equals(worker)
                        || !owner.epoch().equals(epoch))
                    throw new Support.Failure(409, "stale media worker epoch");
            } else if (!worker.isEmpty() || !epoch.isEmpty())
                throw new Support.Failure(409, "unexpected remote media owner");
        }
        String owner =
                new String(Support.json(List.of(worker, epoch, sessionId)), StandardCharsets.UTF_8);
        var quota = new VoiceQuota(settings, gateway.ledger);
        gateway.ledger.transaction(
                db -> {
                    if (action.equals("acquire")) quota.acquire(db, id, kind, call, attempt, owner);
                    else
                        quota.release(db, id, owner, Boolean.TRUE.equals(payload.get("confirmed")));
                    return null;
                });
        return Map.of("accepted", true);
    }

    @PostMapping("/v1/admin/voice-permits/reconcile")
    Map<String, Object> reconcile(
            @RequestBody
            Map<String, Object> payload,
            @RequestHeader(value = "Authorization", required = false)
            String auth) {
        admin(auth);
        String id = Support.required(payload, "permit_id", 128),
                evidence = Support.required(payload, "provider_close_evidence", 1000);
        if (evidence.length() < 10)
            throw new Support.Failure(422, "provider close evidence required");
        gateway.ledger.transaction(
                db -> {
                    var rows =
                            LocalLedger.query(db, "SELECT kind FROM voice_permits WHERE id=?", id);
                    if (rows.isEmpty() || rows.get(0).get("kind").equals("reserve"))
                        throw new Support.Failure(409, "physical voice permit required");
                    LocalLedger.execute(
                            db, "UPDATE voice_permits SET state='closed' WHERE id=?", id);
                    LocalLedger.execute(
                            db,
                            "INSERT INTO audit(at,action,detail) VALUES"
                                    + " (?,'voice-permit-provider-confirmed',?)",
                            LocalLedger.now(),
                            id + ": " + evidence);
                    return null;
                });
        return Map.of("reconciled", true);
    }
}
