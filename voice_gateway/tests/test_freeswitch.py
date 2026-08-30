import asyncio
from typing import AsyncIterator

import pytest

from app.config import Settings
from app.esl import EslClient, read_frame
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
