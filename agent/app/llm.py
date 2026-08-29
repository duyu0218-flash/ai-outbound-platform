from __future__ import annotations

import httpx

from .config import settings


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
    headers = {"Authorization": f"Bearer {settings.openai_api_key}"}
    payload = {
        "model": model or settings.openai_model,
        "messages": messages,
        "max_tokens": settings.max_output_tokens,
        "temperature": 0.3,
    }
    async with httpx.AsyncClient(timeout=settings.openai_timeout_sec) as client:
        response = await client.post(
            f"{settings.openai_base_url.rstrip('/')}/chat/completions",
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
