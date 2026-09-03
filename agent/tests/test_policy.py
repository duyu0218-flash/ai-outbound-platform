from app.policy import ai_reply, get_default_keywords, resolve_action
from fastapi.testclient import TestClient

from app.config import settings
from app.main import app
from app.llm import _validated_llm_endpoint, redact_sensitive_text


def test_english_reply_and_handoff_are_localized():
    assert ai_reply("ai_only", "", "I need help", "en-US").startswith("I heard:")
    handoff, sms, tts, priority = resolve_action(
        "ai_with_sms",
        "",
        "This is urgent, transfer me to a human agent",
        "en-US",
    )
    assert handoff is True
    assert sms is None
    assert tts == "I will transfer you to a human agent now."
    assert priority == 2
    assert "human" in get_default_keywords("en-US")


def test_chinese_policy_remains_supported():
    handoff, sms, tts, priority = resolve_action("ai_with_sms", "", "请转人工，这很重要", "zh-CN")
    assert handoff is True
    assert sms is None
    assert "转接人工" in tts
    assert priority == 1


def test_ai_only_does_not_handoff_and_human_first_does():
    handoff, _, _, _ = resolve_action("ai_only", "", "请转人工", "zh-CN")
    assert handoff is False
    handoff, _, _, _ = resolve_action("mixed_human_first", "", "", "zh-CN")
    assert handoff is True


def test_agent_service_token_protects_turn_endpoint(monkeypatch):
    monkeypatch.setattr(settings, "service_token", "test-agent-token")
    payload = {"call_id": "00000000-0000-0000-0000-000000000001", "phone": "13800138000", "mode": "ai_only", "transcript": "hello"}
    with TestClient(app) as client:
        assert client.post("/agent/turn", json=payload).status_code == 401
        response = client.post(
            "/agent/turn",
            json=payload,
            headers={"Authorization": "Bearer test-agent-token"},
        )
    assert response.status_code == 200


def test_external_llm_requires_tenant_approval(monkeypatch):
    monkeypatch.setattr(settings, "service_token", "test-agent-token")
    payload = {
        "call_id": "00000000-0000-0000-0000-000000000002",
        "phone": "13800138000",
        "mode": "ai_only",
        "transcript": "hello",
        "context": {"llm_provider": "openai-compatible", "external_llm_enabled": False},
    }
    with TestClient(app) as client:
        response = client.post(
            "/agent/turn",
            json=payload,
            headers={"Authorization": "Bearer test-agent-token"},
        )
    assert response.status_code == 409


def test_llm_redaction_and_host_allowlist(monkeypatch):
    value = "mail me at customer@example.com or call +86 138-0013-8000 with ID 11010519491231002X"
    redacted = redact_sensitive_text(value)
    assert "customer@example.com" not in redacted
    assert "138-0013-8000" not in redacted
    assert "11010519491231002X" not in redacted

    monkeypatch.setattr(settings, "openai_base_url", "https://approved.example.com/v1")
    monkeypatch.setattr(settings, "llm_allowed_hosts", "approved.example.com")
    assert _validated_llm_endpoint() == "https://approved.example.com/v1"
    monkeypatch.setattr(settings, "openai_base_url", "https://unapproved.example.com/v1")
    try:
        _validated_llm_endpoint()
    except RuntimeError as exc:
        assert "not allowlisted" in str(exc)
    else:
        raise AssertionError("unapproved LLM host must be rejected")
