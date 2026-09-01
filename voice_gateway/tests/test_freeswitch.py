import asyncio
from types import SimpleNamespace
from typing import AsyncIterator

import pytest

from app.config import Settings
from app.esl import EslClient, EslError, read_frame
from app.freeswitch import FreeswitchEslDriver, _fs_argument


class FakeEslClient:
    def __init__(self):
        self.api_commands: list[str] = []
        self.bgapi_commands: list[str] = []

    async def api(self, command: str) -> str:
        self.api_commands.append(command)
        return "+OK"

    async def bgapi(self, command: str) -> str:
        self.bgapi_commands.append(command)
        return "job-1"

    async def events(self, _names: tuple[str, ...]) -> AsyncIterator[dict[str, object]]:
        if False:
            yield {}


class FakePipecatManager:
    def __init__(self):
        self.created: list[dict] = []
        self.spoken: list[tuple[str, str]] = []
        self.interrupted: list[str] = []
        self.closed: list[str] = []
        self.media: list[tuple[str, str]] = []

    def ready(self) -> bool:
        return True

    async def create_session(self, **kwargs):
        self.created.append(kwargs)
        return SimpleNamespace(token="pipecat-token-1", session_id="pipecat-session-1")

    def media_ws_url(self, session) -> str:
        return f"ws://voice-gateway:8002/v1/pipecat/media/{session.token}"

    async def speak(self, call_id: str, text: str) -> str:
        self.spoken.append((call_id, text))
        return "pipecat-playback-1"

    async def interrupt(self, call_id: str) -> None:
        self.interrupted.append(call_id)

    async def close(self, call_id: str, **_kwargs) -> None:
        self.closed.append(call_id)

    async def post_media(self, session, state: str, **_kwargs) -> None:
        self.media.append((session.token, state))


def freeswitch_settings(**overrides) -> Settings:
    values = {
        "voice_gateway_driver": "freeswitch_esl",
        "freeswitch_esl_host": "fs.internal",
        "freeswitch_esl_password": "test-password",
        "freeswitch_gateway": "carrier",
        "freeswitch_caller_id": "02155550000",
        "freeswitch_tts_engine": "flite",
        "freeswitch_tts_voice": "slt",
        "freeswitch_recording_public_base_url": "https://recordings.example.test/calls",
        "webhook_token": "callback-token",
    }
    values.update(overrides)
    return Settings(**values)


def test_read_frame_parses_headers_and_body():
    async def scenario():
        reader = asyncio.StreamReader()
        reader.feed_data(b"Content-Type: api/response\nContent-Length: 6\n\n+OK up")
        reader.feed_eof()
        frame = await read_frame(reader)
        assert frame.content_type == "api/response"
        assert frame.body == b"+OK up"

    asyncio.run(scenario())


def test_freeswitch_argument_quotes_text_with_spaces():
    assert _fs_argument("speak:engine|voice|hello world") == "'speak:engine|voice|hello world'"


def test_esl_client_authenticates_and_runs_api():
    async def read_command(reader: asyncio.StreamReader) -> str:
        lines: list[bytes] = []
        while True:
            line = await reader.readline()
            if not line or line in {b"\n", b"\r\n"}:
                return b"".join(lines).decode().strip()
            lines.append(line)

    async def scenario():
        async def handle(reader: asyncio.StreamReader, writer: asyncio.StreamWriter):
            writer.write(b"Content-Type: auth/request\n\n")
            await writer.drain()
            assert await read_command(reader) == "auth secret"
            writer.write(b"Content-Type: command/reply\nReply-Text: +OK accepted\n\n")
            await writer.drain()
            assert await read_command(reader) == "api status"
            writer.write(b"Content-Type: api/response\nContent-Length: 9\n\nUP 1 year")
            await writer.drain()
            writer.close()
            await writer.wait_closed()

        server = await asyncio.start_server(handle, "127.0.0.1", 0)
        port = server.sockets[0].getsockname()[1]
        async with server:
            result = await EslClient("127.0.0.1", port, "secret").api("status")
        assert result == "UP 1 year"

    asyncio.run(scenario())


def test_freeswitch_driver_dial_and_call_controls():
    async def scenario():
        fake = FakeEslClient()
        driver = FreeswitchEslDriver(freeswitch_settings(), client=fake)
        callbacks: list[tuple[str, dict]] = []

        async def capture(url: str, payload: dict) -> None:
            callbacks.append((url, payload))

        driver._post_json = capture
        result = await driver.post("dial", {
            "call_id": "platform-call-1",
            "phone": "+86 138-0013-8000",
            "webhook_url": "http://control-api:8000/api/v1/webhooks/telephony/status",
            "metadata": {
                "attempt": 1,
                "recording_enabled": True,
                "recording_webhook_url": "http://control-api:8000/api/v1/webhooks/telephony/recording",
                "speech_webhook_url": "http://control-api:8000/api/v1/webhooks/telephony/speech",
                "media_webhook_url": "http://control-api:8000/api/v1/webhooks/telephony/media",
            },
        })
        assert result["result"] == "accepted"
        assert result["job_uuid"] == "job-1"
        originate = fake.bgapi_commands[0]
        assert "sofia/gateway/carrier/+8613800138000" in originate
        assert "origination_caller_id_number=02155550000" in originate
        assert "platform_status_webhook_b64=" in originate

        binding = driver.calls_by_id["platform-call-1"]
        spoken = await driver.post("speak", {
            "call_id": "platform-call-1",
            "text": "您好",
            "provider": "flite",
            "voice": "slt",
        })
        assert spoken["result"] == "playing"
        await driver.post("stop-speaking", {"call_id": "platform-call-1"})
        transferred = await driver.post("transfer", {
            "call_id": "platform-call-1",
            "target_group": "agent:23",
        })
        assert transferred["destination"] == "agent_23"
        await driver.post("hangup", {"call_id": "platform-call-1", "reason": "acceptance"})
        assert any(command.startswith(f"uuid_broadcast {binding.fs_uuid} speak:flite|slt|您好") for command in fake.api_commands)
        assert f"uuid_break {binding.fs_uuid} all" in fake.api_commands
        assert f"uuid_transfer {binding.fs_uuid} agent_23 XML default" in fake.api_commands
        assert f"uuid_kill {binding.fs_uuid} NORMAL_CLEARING" in fake.api_commands
        assert any(payload.get("state") == "speaking" for _, payload in callbacks)

    asyncio.run(scenario())


def test_freeswitch_events_emit_status_media_and_recording_callbacks():
    async def scenario():
        fake = FakeEslClient()
        driver = FreeswitchEslDriver(freeswitch_settings(), client=fake)
        captured: list[tuple[str, dict]] = []

        async def capture(url: str, payload: dict):
            captured.append((url, payload))

        driver._post_json = capture  # type: ignore[method-assign]
        dial = await driver.post("dial", {
            "call_id": "platform-call-2",
            "phone": "13800138001",
            "webhook_url": "http://control-api:8000/api/v1/webhooks/telephony/status",
            "metadata": {
                "attempt": 2,
                "recording_enabled": True,
                "recording_webhook_url": "http://control-api:8000/api/v1/webhooks/telephony/recording",
                "media_webhook_url": "http://control-api:8000/api/v1/webhooks/telephony/media",
            },
        })
        fs_uuid = dial["provider_call_id"]
        await driver._handle_event({
            "Event-Name": "CHANNEL_ANSWER",
            "Unique-ID": fs_uuid,
            "Event-Date-Timestamp": "100",
        })
        await driver._handle_event({
            "Event-Name": "CHANNEL_HANGUP_COMPLETE",
            "Unique-ID": fs_uuid,
            "Event-Date-Timestamp": "200",
            "Hangup-Cause": "NORMAL_CLEARING",
        })
        status_payloads = [payload for url, payload in captured if url.endswith("/status")]
        assert [item["payload"]["status"] for item in status_payloads] == ["answered", "ended"]
        media_states = [payload["state"] for url, payload in captured if url.endswith("/media")]
        assert media_states == ["listening", "closed"]
        recording = next(payload for url, payload in captured if url.endswith("/recording"))
        assert recording["payload"]["url"].startswith("https://recordings.example.test/calls/")
        assert recording["payload"]["attempt"] == 2
        assert any(command.startswith(f"uuid_record {fs_uuid} start ") for command in fake.api_commands)
        assert "platform-call-2" not in driver.calls_by_id

    asyncio.run(scenario())


def test_recording_notice_finishes_before_recording_starts():
    async def scenario():
        fake = FakeEslClient()
        driver = FreeswitchEslDriver(
            freeswitch_settings(freeswitch_playback_timeout_sec=2),
            client=fake,
        )
        captured: list[tuple[str, dict]] = []

        async def capture(url: str, payload: dict):
            captured.append((url, payload))

        driver._post_json = capture  # type: ignore[method-assign]
        dial = await driver.post("dial", {
            "call_id": "notice-call-1",
            "phone": "13800138011",
            "webhook_url": "http://control/status",
            "metadata": {
                "attempt": 3,
                "recording_enabled": True,
                "recording_notice": True,
                "recording_notice_text": "本次通话将被录音。",
                "recording_webhook_url": "http://control/recording",
                "media_webhook_url": "http://control/media",
            },
        })
        fs_uuid = dial["provider_call_id"]
        await driver._handle_event({
            "Event-Name": "CHANNEL_ANSWER",
            "Unique-ID": fs_uuid,
            "Event-Date-Timestamp": "notice-answer",
        })
        await asyncio.sleep(0)
        assert any(command.startswith(f"uuid_broadcast {fs_uuid} ") for command in fake.api_commands)
        assert not any(command.startswith(f"uuid_record {fs_uuid} start ") for command in fake.api_commands)

        await driver._handle_event({
            "Event-Name": "CHANNEL_EXECUTE_COMPLETE",
            "Unique-ID": fs_uuid,
            "Application": "playback",
            "Event-Date-Timestamp": "notice-complete",
        })
        for _ in range(10):
            if any(command.startswith(f"uuid_record {fs_uuid} start ") for command in fake.api_commands):
                break
            await asyncio.sleep(0)
        broadcast_index = next(i for i, command in enumerate(fake.api_commands) if command.startswith("uuid_broadcast"))
        record_index = next(i for i, command in enumerate(fake.api_commands) if command.startswith("uuid_record"))
        assert broadcast_index < record_index
        media_payloads = [payload for url, payload in captured if url.endswith("/media")]
        assert media_payloads
        assert all(payload["attempt"] == 3 for payload in media_payloads)

    asyncio.run(scenario())


def test_human_only_call_rings_browser_then_confirms_media_bridge():
    async def scenario():
        fake = FakeEslClient()
        driver = FreeswitchEslDriver(freeswitch_settings(), client=fake)
        captured: list[tuple[str, dict]] = []

        async def capture(url: str, payload: dict):
            captured.append((url, payload))

        driver._post_json = capture  # type: ignore[method-assign]
        dial = await driver.post("dial", {
            "call_id": "human-call-1",
            "phone": "13800138008",
            "webhook_url": "http://control-api:8000/api/v1/webhooks/telephony/status",
            "metadata": {
                "tenant_id": 1,
                "mode": "human_only",
                "human_agent_id": 23,
                "recording_enabled": True,
                "recording_webhook_url": "http://control-api:8000/api/v1/webhooks/telephony/recording",
                "media_webhook_url": "http://control-api:8000/api/v1/webhooks/telephony/media",
            },
        })
        originate = fake.bgapi_commands[-1]
        assert "user/agent_23" in originate
        assert "&bridge(sofia/gateway/carrier/13800138008)" in originate
        fs_uuid = dial["provider_call_id"]
        await driver._handle_event({"Event-Name": "CHANNEL_ANSWER", "Unique-ID": fs_uuid, "Event-Date-Timestamp": "400"})
        await driver._handle_event({"Event-Name": "CHANNEL_BRIDGE", "Unique-ID": fs_uuid, "Event-Date-Timestamp": "500"})
        statuses = [payload["payload"]["status"] for url, payload in captured if url.endswith("/status")]
        assert statuses == ["agent_answered", "human_connected"]
        assert any(command.startswith(f"uuid_record {fs_uuid} start ") for command in fake.api_commands)

    asyncio.run(scenario())


def test_failed_browser_handoff_notifies_control_plane_for_requeue():
    async def scenario():
        fake = FakeEslClient()
        driver = FreeswitchEslDriver(freeswitch_settings(), client=fake)
        captured: list[tuple[str, dict]] = []

        async def capture(url: str, payload: dict):
            captured.append((url, payload))

        driver._post_json = capture  # type: ignore[method-assign]
        dial = await driver.post("dial", {
            "call_id": "handoff-call-1",
            "phone": "13800138009",
            "webhook_url": "http://control-api:8000/api/v1/webhooks/telephony/status",
            "metadata": {"tenant_id": 1},
        })
        await driver.post("transfer", {"call_id": "handoff-call-1", "target_group": "agent:23"})
        await driver._handle_event({
            "Event-Name": "CHANNEL_EXECUTE_COMPLETE",
            "Unique-ID": dial["provider_call_id"],
            "Application": "bridge",
            "Application-Response": "NO_ANSWER",
            "Event-Date-Timestamp": "600",
        })
        statuses = [payload["payload"] for url, payload in captured if url.endswith("/status")]
        assert statuses[-1]["status"] == "human_unavailable"
        assert statuses[-1]["hangup_reason"] == "NO_ANSWER"

    asyncio.run(scenario())


def test_freeswitch_hangup_causes_are_mapped():
    async def scenario():
        fake = FakeEslClient()
        driver = FreeswitchEslDriver(freeswitch_settings(), client=fake)
        captured: list[dict] = []

        async def capture(_url: str, payload: dict):
            captured.append(payload)

        driver._post_json = capture  # type: ignore[method-assign]
        dial = await driver.post("dial", {
            "call_id": "platform-call-3",
            "phone": "13800138002",
            "webhook_url": "http://control-api:8000/api/v1/webhooks/telephony/status",
        })
        await driver._handle_event({
            "Event-Name": "CHANNEL_HANGUP_COMPLETE",
            "Unique-ID": dial["provider_call_id"],
            "Hangup-Cause": "USER_BUSY",
            "Event-Date-Timestamp": "300",
        })
        assert captured[-1]["payload"]["status"] == "busy"

    asyncio.run(scenario())


def test_freeswitch_runtime_validation_rejects_unsafe_production_defaults():
    with pytest.raises(RuntimeError, match="default ESL password"):
        Settings(
            env="production",
            service_token="service-token",
            metrics_token="metrics-token-for-production-tests",
            webhook_token="webhook-token",
            voice_gateway_driver="freeswitch_esl",
            freeswitch_esl_password="ClueCon",
            freeswitch_gateway="carrier",
            freeswitch_tts_engine="flite",
            freeswitch_tts_voice="slt",
        ).validate_runtime()


def test_freeswitch_runtime_validation_rejects_invalid_command_template():
    settings = freeswitch_settings(freeswitch_media_start_command_template="uuid_audio {unknown}")
    with pytest.raises(RuntimeError, match="invalid FreeSWITCH command template"):
        settings.validate_runtime()


def test_pipecat_runtime_validation_requires_explicit_media_configuration():
    with pytest.raises(RuntimeError, match="PIPECAT_VERSION"):
        freeswitch_settings(voice_ai_pipeline="pipecat").validate_runtime()

    settings = freeswitch_settings(
        voice_ai_pipeline="pipecat",
        pipecat_version="1.8.1",
        pipecat_media_ws_base="ws://voice-gateway:8002/v1/pipecat/media",
        pipecat_openai_api_key="test-key",
        freeswitch_pipecat_start_command_template="uuid_audio_stream {uuid} start {media_ws_url} mono 8k",
    )
    settings.validate_runtime()

    notice_tts_settings = Settings(
        voice_gateway_driver="freeswitch_esl",
        voice_ai_pipeline="pipecat",
        freeswitch_esl_password="test-password",
        freeswitch_gateway="carrier",
        pipecat_version="1.8.1",
        pipecat_media_ws_base="ws://voice-gateway:8002/v1/pipecat/media",
        pipecat_openai_api_key="test-key",
        freeswitch_pipecat_start_command_template="stream {uuid} {media_ws_url}",
    )
    with pytest.raises(RuntimeError, match="recording notice"):
        notice_tts_settings.validate_runtime()
    notice_tts_settings.freeswitch_tts_http_endpoint = "http://tts.internal/synthesize"
    notice_tts_settings.validate_runtime()


def test_pipecat_runtime_validation_rejects_unconfigured_legacy_fallback():
    settings = freeswitch_settings(
        voice_ai_pipeline="pipecat",
        pipecat_version="1.8.1",
        pipecat_media_ws_base="ws://voice-gateway:8002/v1/pipecat/media",
        pipecat_openai_api_key="test-key",
        pipecat_fallback_to_legacy=True,
        freeswitch_pipecat_start_command_template="stream {uuid} {media_ws_url}",
    )
    with pytest.raises(RuntimeError, match="FREESWITCH_MEDIA_START_COMMAND_TEMPLATE"):
        settings.validate_runtime()


def test_freeswitch_driver_routes_media_and_tts_through_pipecat():
    async def scenario():
        fake_esl = FakeEslClient()
        fake_pipecat = FakePipecatManager()
        settings = freeswitch_settings(
            voice_ai_pipeline="pipecat",
            pipecat_version="1.8.1",
            pipecat_media_ws_base="ws://voice-gateway:8002/v1/pipecat/media",
            pipecat_openai_api_key="test-key",
            freeswitch_pipecat_start_command_template=(
                "uuid_audio_stream {uuid} start {media_ws_url} mono 8k"
            ),
        )
        driver = FreeswitchEslDriver(settings, client=fake_esl, pipecat_manager=fake_pipecat)
        driver._post_json = lambda *_args, **_kwargs: asyncio.sleep(0)  # type: ignore[method-assign]
        dial = await driver.post(
            "dial",
            {
                "call_id": "pipecat-call-1",
                "phone": "13800138006",
                "webhook_url": "http://control-api:8000/api/v1/webhooks/telephony/status",
                "metadata": {
                    "attempt": 1,
                    "speech_webhook_url": "http://control-api:8000/api/v1/webhooks/telephony/speech",
                    "media_webhook_url": "http://control-api:8000/api/v1/webhooks/telephony/media",
                },
            },
        )
        await driver._handle_event(
            {
                "Event-Name": "CHANNEL_ANSWER",
                "Unique-ID": dial["provider_call_id"],
                "Event-Date-Timestamp": "700",
            }
        )
        assert fake_pipecat.created[0]["call_id"] == "pipecat-call-1"
        assert any(
            command.startswith(f"uuid_audio_stream {dial['provider_call_id']} start ws://voice-gateway:8002/")
            for command in fake_esl.api_commands
        )

        spoken = await driver.post("speak", {"call_id": "pipecat-call-1", "text": "您好"})
        assert spoken == {
            "result": "queued",
            "provider_call_id": dial["provider_call_id"],
            "playback_id": "pipecat-playback-1",
            "pipeline": "pipecat",
        }
        assert fake_pipecat.spoken == [("pipecat-call-1", "您好")]
        assert not any(command.startswith("uuid_broadcast") for command in fake_esl.api_commands)

        await driver.post("stop-speaking", {"call_id": "pipecat-call-1"})
        assert fake_pipecat.interrupted == ["pipecat-call-1"]
        await driver.post("hangup", {"call_id": "pipecat-call-1", "reason": "done"})
        assert fake_pipecat.closed == ["pipecat-call-1"]

    asyncio.run(scenario())


def test_pipecat_falls_back_only_when_explicitly_configured():
    class FailingPipecatEslClient(FakeEslClient):
        async def api(self, command: str) -> str:
            self.api_commands.append(command)
            if command.startswith("pipecat_start"):
                raise EslError("media module rejected command")
            return "+OK"

    async def scenario():
        fake_esl = FailingPipecatEslClient()
        fake_pipecat = FakePipecatManager()
        settings = freeswitch_settings(
            voice_ai_pipeline="pipecat",
            pipecat_version="1.8.1",
            pipecat_media_ws_base="ws://voice-gateway:8002/v1/pipecat/media",
            pipecat_openai_api_key="test-key",
            pipecat_fallback_to_legacy=True,
            freeswitch_pipecat_start_command_template="pipecat_start {uuid} {media_ws_url}",
            freeswitch_media_start_command_template="legacy_start {uuid} {speech_webhook_url}",
        )
        driver = FreeswitchEslDriver(settings, client=fake_esl, pipecat_manager=fake_pipecat)
        driver._post_json = lambda *_args, **_kwargs: asyncio.sleep(0)  # type: ignore[method-assign]
        dial = await driver.post(
            "dial",
            {
                "call_id": "pipecat-fallback-1",
                "phone": "13800138007",
                "webhook_url": "http://control/status",
                "metadata": {
                    "speech_webhook_url": "http://control/speech",
                    "media_webhook_url": "http://control/media",
                },
            },
        )
        await driver._handle_event(
            {"Event-Name": "CHANNEL_ANSWER", "Unique-ID": dial["provider_call_id"]}
        )
        binding = driver.calls_by_id["pipecat-fallback-1"]
        assert binding.voice_ai_pipeline == "legacy"
        assert fake_pipecat.closed == ["pipecat-fallback-1"]
        assert any(command.startswith("legacy_start") for command in fake_esl.api_commands)

    asyncio.run(scenario())


def test_hybrid_gateway_routes_each_call_and_rejects_mismatched_fixed_mode():
    async def scenario():
        fake_esl = FakeEslClient()
        fake_pipecat = FakePipecatManager()
        settings = freeswitch_settings(
            voice_ai_pipeline="hybrid",
            pipecat_version="1.8.1",
            pipecat_media_ws_base="ws://voice-gateway:8002/v1/pipecat/media",
            pipecat_openai_api_key="test-key",
            freeswitch_pipecat_start_command_template="pipecat_start {uuid} {media_ws_url}",
        )
        settings.validate_runtime()
        driver = FreeswitchEslDriver(settings, client=fake_esl, pipecat_manager=fake_pipecat)
        pipecat = await driver.post("dial", {
            "call_id": "hybrid-pipecat",
            "phone": "13800138008",
            "webhook_url": "http://control/status",
            "metadata": {"voice_ai_pipeline": "pipecat"},
        })
        legacy = await driver.post("dial", {
            "call_id": "hybrid-legacy",
            "phone": "13800138009",
            "webhook_url": "http://control/status",
            "metadata": {"voice_ai_pipeline": "legacy"},
        })
        assert pipecat["voice_ai_pipeline"] == "pipecat"
        assert legacy["voice_ai_pipeline"] == "legacy"
        assert driver.calls_by_id["hybrid-pipecat"].voice_ai_pipeline == "pipecat"
        assert driver.calls_by_id["hybrid-legacy"].voice_ai_pipeline == "legacy"

        fixed = FreeswitchEslDriver(freeswitch_settings(voice_ai_pipeline="legacy"), client=FakeEslClient())
        with pytest.raises(RuntimeError, match="use VOICE_AI_PIPELINE=hybrid"):
            await fixed.post("dial", {
                "call_id": "mismatch",
                "phone": "13800138010",
                "webhook_url": "http://control/status",
                "metadata": {"voice_ai_pipeline": "pipecat"},
            })

    asyncio.run(scenario())
