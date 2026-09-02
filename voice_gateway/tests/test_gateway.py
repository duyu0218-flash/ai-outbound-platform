import struct

from fastapi.testclient import TestClient

from app.main import app
from app import main as gateway_main
from app.main import settings
from app.pipecat_pipeline import RawPcmSerializer
from app.rtp import RtpPacket, pcma_to_pcm16, pcm16_to_pcma, pcm16_to_pcmu, pcmu_to_pcm16


def test_health_and_call_contract():
    with TestClient(app) as client:
        assert client.get("/readyz").status_code == 200
        result = client.post("/v1/call/speak", json={"call_id": "c-1", "text": "hello"})
        assert result.status_code == 200
        assert result.json()["action"] == "speak"


def test_rtp_packet_round_trip():
    packet = RtpPacket(payload_type=0, sequence=65537, timestamp=160, ssrc=42, payload=b"abc", marker=True)
    decoded = RtpPacket.decode(packet.encode())
    assert decoded.sequence == 1
    assert decoded.payload == b"abc"
    assert decoded.marker is True


def test_pcmu_codec_shape_and_sign():
    pcm = struct.pack("<hhh", -1000, 0, 1000)
    encoded = pcm16_to_pcmu(pcm)
    decoded = struct.unpack("<hhh", pcmu_to_pcm16(encoded))
    assert len(encoded) == 3
    assert decoded[0] < 0 < decoded[2]


def test_pcma_codec_shape_and_sign():
    pcm = struct.pack("<hhh", -1000, 0, 1000)
    encoded = pcm16_to_pcma(pcm)
    decoded = struct.unpack("<hhh", pcma_to_pcm16(encoded))
    assert len(encoded) == 3
    assert decoded[0] < 0 < decoded[2]


def test_pipecat_raw_pcm_serializer_round_trip():
    async def scenario():
        serializer = RawPcmSerializer(sample_rate=8000)
        decoded = await serializer.deserialize(b"\x01\x02\x03\x04")
        assert decoded.audio == b"\x01\x02\x03\x04"
        assert decoded.sample_rate == 8000
        assert decoded.num_channels == 1
        assert await serializer.serialize(decoded) is None

    import asyncio

    asyncio.run(scenario())


def test_voice_gateway_service_token_protects_operations(monkeypatch):
    monkeypatch.setattr(settings, "service_token", "test-voice-token")
    with TestClient(app) as client:
        assert client.get("/health").status_code == 200
        assert client.post("/v1/call/speak", json={"call_id": "c-2", "text": "hello"}).status_code == 401
        accepted = client.post(
            "/v1/call/speak",
            json={"call_id": "c-2", "text": "hello"},
            headers={"Authorization": "Bearer test-voice-token"},
        )
    assert accepted.status_code == 200


def test_voice_gateway_metrics_require_token(monkeypatch):
    monkeypatch.setattr(settings, "metrics_token", "voice-metrics-token-for-tests")
    monkeypatch.setattr(settings, "metrics_token_file", "")
    with TestClient(app) as client:
        assert client.get("/metrics").status_code == 401
        response = client.get(
            "/metrics",
            headers={"Authorization": "Bearer voice-metrics-token-for-tests"},
        )
    assert response.status_code == 200
    assert "ai_outbound_voice_gateway_ready 1" in response.text
    assert "ai_outbound_pipecat_session_capacity" in response.text


def test_voice_gateway_metrics_accept_mounted_token_file(monkeypatch, tmp_path):
    token_file = tmp_path / "metrics-token"
    token_file.write_text("mounted-voice-metrics-token\n", encoding="utf-8")
    monkeypatch.setattr(settings, "metrics_token", "")
    monkeypatch.setattr(settings, "metrics_token_file", str(token_file))

    with TestClient(app) as client:
        response = client.get(
            "/metrics",
            headers={"Authorization": "Bearer mounted-voice-metrics-token"},
        )

    assert response.status_code == 200


def test_voice_gateway_drain_rejects_new_calls_but_can_be_disabled(monkeypatch):
    monkeypatch.setattr(settings, "service_token", "test-voice-token")
    gateway_main.draining = False
    headers = {"Authorization": "Bearer test-voice-token"}
    with TestClient(app) as client:
        enabled = client.post("/v1/admin/drain?enabled=true", headers=headers)
        assert enabled.status_code == 200
        assert enabled.json()["draining"] is True
        assert client.get("/health").json()["draining"] is True
        assert client.get("/readyz").status_code == 503
        assert client.post(
            "/v1/call/dial",
            json={
                "call_id": "drain-1",
                "phone": "+10000000000",
                "webhook_url": "https://control.example/webhook",
            },
            headers=headers,
        ).status_code == 503
        disabled = client.post("/v1/admin/drain?enabled=false", headers=headers)
        assert disabled.status_code == 200
        assert client.get("/readyz").status_code == 200
    gateway_main.draining = False
