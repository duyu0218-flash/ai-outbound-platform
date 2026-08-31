import struct

from fastapi.testclient import TestClient

from app.main import app
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
