from app.policy import ai_reply, get_default_keywords, resolve_action


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
