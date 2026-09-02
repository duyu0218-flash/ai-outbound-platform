import asyncio
import json

import pytest
from pipecat.frames.frames import (
    InterimTranscriptionFrame,
    TranscriptionFrame,
    UserStartedSpeakingFrame,
    UserStoppedSpeakingFrame,
)

from app.aliyun_nls_stt import (
    ALIYUN_NLS_SUCCESS_STATUS,
    AliyunNLSSTTService,
    aliyun_nls_url,
    parse_aliyun_transcript,
)
from app.config import Settings


def aliyun_settings(**overrides) -> Settings:
    from security_fixtures import SECURITY_SETTINGS
    values = {
        **SECURITY_SETTINGS,
        "voice_gateway_driver": "freeswitch_esl",
        "voice_ai_pipeline": "pipecat",
        "freeswitch_esl_password": "synthetic-esl-" + "e" * 32,
        "freeswitch_gateway": "carrier",
        "freeswitch_tts_engine": "flite",
        "freeswitch_tts_voice": "slt",
        "freeswitch_pipecat_start_command_template": "stream {uuid} {media_ws_url}",
        "pipecat_version": "1.8.1",
        "pipecat_media_ws_base": "ws://voice-gateway:8002/v1/pipecat/media",
        "pipecat_stt_provider": "aliyun-nls",
        "pipecat_openai_api_key": "tts-key",
        "aliyun_nls_appkey": "nls-appkey",
        "aliyun_nls_token": "short-lived-token",
    }
    values.update(overrides)
    return Settings(**values)


def make_service(**overrides) -> AliyunNLSSTTService:
    values = {
        "appkey": "nls-appkey",
        "token_getter": lambda: "short-lived-token",
        "gateway_url": "wss://nls-gateway-cn-shanghai.aliyuncs.com/ws/v1",
        "sample_rate": 8000,
        "vocabulary_id": "medical-hotwords",
    }
    values.update(overrides)
    return AliyunNLSSTTService(**values)


def test_aliyun_url_replaces_token_and_preserves_other_query_parameters():
    url = aliyun_nls_url("wss://nls.example/ws/v1?trace=1&token=old", "new token")
    assert url == "wss://nls.example/ws/v1?trace=1&token=new+token"


def test_aliyun_transcript_maps_interim_and_final_metadata():
    interim = parse_aliyun_transcript(
        {
            "header": {"name": "TranscriptionResultChanged", "message_id": "partial-1"},
            "payload": {"index": 1, "time": 1200, "result": "您好"},
        }
    )
    final = parse_aliyun_transcript(
        {
            "header": {"name": "SentenceEnd", "message_id": "final-1"},
            "payload": {
                "index": 1,
                "begin_time": 320,
                "time": 1680,
                "result": "您好，请问是李女士吗？",
                "confidence": 0.93,
            },
        }
    )
    assert interim is not None and interim.is_final is False
    assert final is not None and final.is_final is True
    assert final.event_id == "final-1"
    assert (final.start_ms, final.end_ms, final.confidence) == (320, 1680, 0.93)


def test_aliyun_start_command_uses_8k_pcm_and_hotword_model():
    command = make_service().start_command()
    assert command["header"]["name"] == "StartTranscription"
    assert len(command["header"]["task_id"]) == 0
    assert command["payload"] == {
        "format": "pcm",
        "sample_rate": 8000,
        "enable_intermediate_result": True,
        "enable_punctuation_prediction": True,
        "enable_inverse_text_normalization": True,
        "max_sentence_silence": 800,
        "enable_words": True,
        "enable_ignore_sentence_timeout": True,
        "enable_semantic_sentence_detection": False,
        "disfluency": False,
        "vocabulary_id": "medical-hotwords",
    }


def test_aliyun_events_emit_barge_in_interim_and_final_frames():
    async def scenario():
        service = make_service()
        frames = []

        async def capture(frame, _direction=None):
            frames.append(frame)

        async def skip_metrics():
            return None

        service.push_frame = capture  # type: ignore[method-assign]
        service.emit_stt_usage_metrics = skip_metrics  # type: ignore[method-assign]
        service._task_started_at = None
        await service._handle_event(
            {
                "header": {
                    "name": "SentenceBegin",
                    "message_id": "begin-1",
                    "status": ALIYUN_NLS_SUCCESS_STATUS,
                },
                "payload": {"index": 1, "time": 200},
            }
        )
        await service._handle_event(
            {
                "header": {
                    "name": "TranscriptionResultChanged",
                    "message_id": "partial-1",
                    "status": ALIYUN_NLS_SUCCESS_STATUS,
                },
                "payload": {"index": 1, "time": 600, "result": "我想"},
            }
        )
        await service._handle_event(
            {
                "header": {
                    "name": "SentenceEnd",
                    "message_id": "final-1",
                    "status": ALIYUN_NLS_SUCCESS_STATUS,
                },
                "payload": {
                    "index": 1,
                    "begin_time": 200,
                    "time": 1000,
                    "result": "我想预约。",
                    "confidence": 0.9,
                },
            }
        )
        assert [type(frame) for frame in frames] == [
            UserStartedSpeakingFrame,
            InterimTranscriptionFrame,
            TranscriptionFrame,
            UserStoppedSpeakingFrame,
        ]
        assert frames[1].text == "我想"
        assert frames[2].text == "我想预约。"
        assert frames[2].result["provider_event_id"] == "final-1"
        assert frames[2].result["confidence"] == 0.9
        assert frames[2].result["latency_ms"] is None

        service._task_started_at = 10.0
        assert service._observed_latency_ms(500) is not None

    asyncio.run(scenario())


def test_aliyun_settings_accept_token_file_and_validate_8k(tmp_path):
    token_file = tmp_path / "aliyun-nls-token"
    token_file.write_text("rotated-token\n", encoding="utf-8")
    settings = aliyun_settings(aliyun_nls_token="", aliyun_nls_token_file=str(token_file))
    settings.validate_runtime()
    assert settings.resolved_aliyun_nls_token() == "rotated-token"


def test_aliyun_settings_reject_missing_credentials_and_unsupported_sample_rate():
    with pytest.raises(RuntimeError, match="ALIYUN_NLS_APPKEY"):
        aliyun_settings(aliyun_nls_appkey="").validate_runtime()
    with pytest.raises(RuntimeError, match="8000 or 16000"):
        aliyun_settings(pipecat_sample_rate=24000).validate_runtime()


def test_aliyun_start_and_stop_commands_never_include_token():
    service = make_service()
    service._task_id = "task-id"
    start_json = json.dumps(service.start_command())
    assert "short-lived-token" not in start_json
    assert service.start_command()["header"]["task_id"] == "task-id"
