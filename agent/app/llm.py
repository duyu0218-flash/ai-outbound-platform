from __future__ import annotations

import httpx
import re
from urllib.parse import urlparse

from .config import settings


EMAIL_PATTERN = re.compile(r"(?i)\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b")
PHONE_PATTERN = re.compile(r"(?<!\w)\+?\d[\d\s().-]{5,}\d(?!\w)")
IDENTITY_PATTERN = re.compile(r"(?<!\w)\d{17}[\dXx](?!\w)")


def redact_sensitive_text(value: str) -> str:
    redacted = EMAIL_PATTERN.sub("[REDACTED_EMAIL]", value)
    redacted = IDENTITY_PATTERN.sub("[REDACTED_ID]", redacted)
    return PHONE_PATTERN.sub("[REDACTED_PHONE]", redacted)


def _validated_llm_endpoint() -> str:
    base_url = settings.openai_base_url.rstrip("/")
    parsed = urlparse(base_url)
    if parsed.scheme not in {"http", "https"} or not parsed.hostname or parsed.username or parsed.password:
        raise RuntimeError("OPENAI_BASE_URL must be an absolute URL without embedded credentials")
    if settings.env.lower() in {"prod", "production"} and settings.llm_require_https and parsed.scheme != "https":
        raise RuntimeError("external LLM endpoint must use HTTPS in production")
    allowed_hosts = {
        item.strip().lower().rstrip(".")
        for item in settings.llm_allowed_hosts.split(",")
        if item.strip()
    }
    hostname = parsed.hostname.lower().rstrip(".")
    if allowed_hosts and hostname not in allowed_hosts:
        raise RuntimeError("external LLM endpoint host is not allowlisted")
    if settings.env.lower() in {"prod", "production"} and not allowed_hosts:
        raise RuntimeError("LLM_ALLOWED_HOSTS is required in production")
    return base_url


async def generate_reply(
    *,
    script: str,
    transcript: str,
    language: str,
    model: str = "",
    knowledge: list[dict[str, str]] | None = None,
    conversation: list[dict[str, str]] | None = None,
) -> str:
    if not settings.openai_base_url or not settings.openai_api_key:
        raise RuntimeError("OpenAI-compatible LLM requires OPENAI_BASE_URL and OPENAI_API_KEY")
    base_url = _validated_llm_endpoint()

    system_prompt = (
        "You are an outbound-call voice agent. Reply briefly, naturally, and only with words that can be spoken. "
        "Follow the supplied script and do not invent commitments."
    )
    if language.lower().startswith("zh"):
        system_prompt += " Reply in Chinese."
    messages = [
        {"role": "system", "content": system_prompt},
        {"role": "system", "content": f"Approved call script:\n{script}" if script else "No approved script was supplied."},
    ]
    if knowledge:
        approved_knowledge = "\n\n".join(
            f"[{item.get('title', 'Knowledge')}]\n{item.get('content', '')}"
            for item in knowledge[:3]
        )
        messages.append(
            {
                "role": "system",
                "content": (
                    "Approved knowledge for this turn follows. Use only relevant facts and never invent missing prices, "
                    f"commitments, or policies:\n{approved_knowledge}"
                ),
            }
        )
    history = conversation or []
    history = history[-max(1, settings.conversation_history_turns) :]
    remaining_chars = max(0, settings.conversation_history_max_chars)
    history_messages: list[dict[str, str]] = []
    for item in reversed(history):
        content = str(item.get("content") or "").strip()
        if not content or remaining_chars <= 0:
            continue
        content = content[-remaining_chars:]
        remaining_chars -= len(content)
        role = "assistant" if str(item.get("role")).lower() in {"assistant", "ai", "agent"} else "user"
        history_messages.append({"role": role, "content": content})
    history_messages.reverse()
    if history_messages and history_messages[-1]["role"] == "user" and history_messages[-1]["content"] == transcript:
        history_messages.pop()
    messages.extend(history_messages)
    messages.append({"role": "user", "content": transcript or "Begin the call with a short greeting."})
    if not settings.llm_send_pii:
        messages = [
            {**message, "content": redact_sensitive_text(str(message.get("content") or ""))}
            for message in messages
        ]
    headers = {"Authorization": f"Bearer {settings.openai_api_key}"}
    payload = {
        "model": model or settings.openai_model,
        "messages": messages,
        "max_tokens": settings.max_output_tokens,
        "temperature": 0.3,
    }
    async with httpx.AsyncClient(timeout=settings.openai_timeout_sec) as client:
        response = await client.post(
            f"{base_url}/chat/completions",
            headers=headers,
            json=payload,
        )
        response.raise_for_status()
        data = response.json()
    try:
        content = data["choices"][0]["message"]["content"]
    except (KeyError, IndexError, TypeError) as exc:
        raise RuntimeError("LLM response did not contain a message") from exc
    if not isinstance(content, str) or not content.strip():
        raise RuntimeError("LLM returned an empty message")
    return content.strip()
