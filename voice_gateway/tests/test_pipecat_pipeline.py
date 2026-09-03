import asyncio
from datetime import timedelta

from app.config import Settings
from app.pipecat_pipeline import PipecatPipelineManager, TranscriptWebhookProcessor, _language
from pipecat.frames.frames import InterimTranscriptionFrame, TranscriptionFrame
from pipecat.processors.frame_processor import FrameDirection


def pipecat_settings() -> Settings:
    return Settings(
        voice_gateway_driver="freeswitch_esl",
        voice_ai_pipeline="pipecat",
        freeswitch_esl_password="test-password",
        freeswitch_gateway="carrier",
        freeswitch_tts_engine="flite",
        freeswitch_tts_voice="slt",
        freeswitch_pipecat_start_command_template="stream {uuid} {media_ws_url}",
        pipecat_version="1.8.1",
        pipecat_media_ws_base="ws://voice-gateway:8002/v1/pipecat/media",
        pipecat_openai_api_key="test-key",
    )


def test_pipecat_session_token_is_not_exposed_as_provider_session_id():
    async def scenario():
        manager = PipecatPipelineManager(pipecat_settings())
        media_states: list[str] = []

        async def capture_media(_session, state: str, **_kwargs):
            media_states.append(state)

        manager.post_media = capture_media  # type: ignore[method-assign]
        session = await manager.create_session(
            call_id="call-1",
            speech_webhook_url="http://control/speech",
            media_webhook_url="http://control/media",
            metadata={"language": "zh-CN"},
        )
        assert session.token != session.session_id
        assert manager.media_ws_url(session).endswith(f"/{session.token}")
        playback_id = await manager.speak("call-1", "您好")
        assert playback_id
        assert len(session.pending_speech) == 1
        await manager.interrupt("call-1")
        assert session.pending_speech == []
        await manager.close("call-1")
        await manager.close("call-1")
        assert media_states == ["interrupted", "closed"]
        assert "call-1" not in manager.sessions_by_call
        assert session.token not in manager.sessions_by_token

    asyncio.run(scenario())


def test_pipecat_language_uses_exact_or_base_language_then_safe_default():
    assert _language("zh-CN").value == "zh-CN"
    assert _language("fil-PH").value == "fil-PH"
    assert _language("fil-UNKNOWN").value == "fil"
    assert _language("unsupported").value == "zh-CN"


def test_pipecat_rejects_expired_media_token_before_pipeline_start():
    class FakeWebSocket:
        close_code: int | None = None

        async def close(self, code: int, reason: str):
            self.close_code = code
            assert "expired" in reason

    async def scenario():
        manager = PipecatPipelineManager(pipecat_settings())
        session = await manager.create_session(
            call_id="call-expired",
            speech_webhook_url="",
            media_webhook_url="",
            metadata={},
        )
        session.created_at -= timedelta(seconds=301)
        websocket = FakeWebSocket()
        await manager.run_websocket(websocket, session.token)  # type: ignore[arg-type]
        assert websocket.close_code == 4408
        assert "call-expired" not in manager.sessions_by_call

    asyncio.run(scenario())


def test_pipecat_interim_transcript_does_not_trigger_final_turn():
    async def scenario():
        manager = PipecatPipelineManager(pipecat_settings())
        session = await manager.create_session(
            call_id="call-transcript",
            speech_webhook_url="http://control/speech",
            media_webhook_url="",
            metadata={},
        )
        captured: list[dict] = []

        async def capture_speech(_session, **payload):
            captured.append(payload)

        manager.post_speech = capture_speech  # type: ignore[method-assign]
        processor = TranscriptWebhookProcessor(manager, session)
        processor.user_is_speaking = True
        await processor.process_frame(
            InterimTranscriptionFrame(text="你", user_id="caller", timestamp="1"),
            FrameDirection.DOWNSTREAM,
        )
        await processor.process_frame(
            TranscriptionFrame(
                text="你好",
                user_id="caller",
                timestamp="2",
                result={
                    "provider_event_id": "aliyun-final-1",
                    "confidence": 0.92,
                    "start_ms": 100,
                    "end_ms": 900,
                    "latency_ms": 75,
                },
            ),
            FrameDirection.DOWNSTREAM,
        )
        assert [item["is_final"] for item in captured] == [False, True]
        assert [item["barge_in"] for item in captured] == [True, True]
        assert captured[1]["event_id"] == "aliyun-final-1"
        assert captured[1]["confidence"] == 0.92
        assert (captured[1]["start_ms"], captured[1]["end_ms"]) == (100, 900)
        assert captured[1]["latency_ms"] == 75
        assert processor.user_is_speaking is False

    asyncio.run(scenario())


def test_pipecat_session_capacity_is_enforced():
    async def scenario():
        settings = pipecat_settings()
        settings.pipecat_max_active_sessions = 1
        manager = PipecatPipelineManager(settings)
        await manager.create_session(
            call_id="call-capacity-1",
            speech_webhook_url="",
            media_webhook_url="",
            metadata={},
        )
        try:
            await manager.create_session(
                call_id="call-capacity-2",
                speech_webhook_url="",
                media_webhook_url="",
                metadata={},
            )
        except RuntimeError as exc:
            assert "capacity" in str(exc)
        else:
            raise AssertionError("expected Pipecat capacity rejection")

    asyncio.run(scenario())
