from scripts.real_voice_acceptance import validate_scenario


def _call() -> dict:
    return {"status": "completed", "recording_url": "https://recording.example/test.wav"}


def _events() -> list[dict]:
    return [
        {"event_type": "status", "payload": '{"status":"answered"}'},
        {"event_type": "ai_decision", "payload": "{}"},
    ]


def _turn(index: int) -> dict:
    return {
        "is_final": True,
        "normalized_transcript": f"第{index}句",
        "confidence": 0.9,
        "start_ms": index * 100,
        "end_ms": index * 100 + 80,
        "asr_provider": "pipecat:aliyun-nls",
    }


def test_ai_only_requires_three_final_turns():
    failures = validate_scenario(
        "ai_only",
        _call(),
        _events(),
        [_turn(1)],
        [{"stage": "asr.final", "success": True, "duration_ms": 90}],
        expected_asr_provider="pipecat:aliyun-nls",
    )
    assert "only 1 non-empty ASR final turn(s), expected at least 3" in failures


def test_ai_only_accepts_final_metadata_and_provider():
    failures = validate_scenario(
        "ai_only",
        _call(),
        _events(),
        [_turn(1), _turn(2), _turn(3)],
        [{"stage": "asr.final", "success": True, "duration_ms": 90}],
        expected_asr_provider="pipecat:aliyun-nls",
    )
    assert failures == []


def test_expected_provider_and_failed_metric_are_enforced():
    failures = validate_scenario(
        "ai_handoff",
        {**_call(), "handoff_reason": "customer_request"},
        _events(),
        [{**_turn(1), "asr_provider": "pipecat:openai-realtime"}],
        [{"stage": "asr.final", "success": False, "error_code": "EMPTY_FINAL_TRANSCRIPT"}],
        expected_asr_provider="pipecat:aliyun-nls",
    )
    assert "an asr.final metric reports failure" in failures
    assert any("ASR provider mismatch" in failure for failure in failures)
