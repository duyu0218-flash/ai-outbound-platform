from __future__ import annotations

import asyncio
import base64
import json
import logging
import re
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
from urllib.parse import unquote
from uuid import uuid4

import httpx

from .config import Settings
from .esl import EslClient, EslError
from .models import DialRequest, SpeakRequest
from .pipecat_pipeline import PipecatPipelineManager


logger = logging.getLogger(__name__)

EVENT_NAMES = (
    "CHANNEL_CREATE",
    "CHANNEL_PROGRESS",
    "CHANNEL_PROGRESS_MEDIA",
    "CHANNEL_ANSWER",
    "CHANNEL_BRIDGE",
    "CHANNEL_UNBRIDGE",
    "CHANNEL_EXECUTE_COMPLETE",
    "CHANNEL_HANGUP_COMPLETE",
    "BACKGROUND_JOB",
)

BUSY_CAUSES = {"USER_BUSY", "CALL_REJECTED"}
NO_ANSWER_CAUSES = {"NO_ANSWER", "NO_USER_RESPONSE", "SUBSCRIBER_ABSENT", "ALLOTTED_TIMEOUT"}
NORMAL_CAUSES = {"NORMAL_CLEARING", "ORIGINATOR_CANCEL"}


def _b64_encode(value: str) -> str:
    return base64.urlsafe_b64encode(value.encode("utf-8")).decode("ascii").rstrip("=")


def _b64_decode(value: str) -> str:
    if not value:
        return ""
    padded = value + "=" * (-len(value) % 4)
    return base64.urlsafe_b64decode(padded.encode("ascii")).decode("utf-8")


def _one_line(value: object, *, name: str) -> str:
    result = str(value or "").strip()
    if "\r" in result or "\n" in result:
        raise ValueError(f"{name} contains a newline")
    return result


def _phone(value: object) -> str:
    result = re.sub(r"[ ()-]", "", _one_line(value, name="phone"))
    if not re.fullmatch(r"\+?[0-9]{6,24}", result):
        raise ValueError("phone must contain 6-24 digits with an optional leading plus")
    return result


def _safe_name(value: object, *, name: str) -> str:
    result = _one_line(value, name=name)
    if not re.fullmatch(r"[A-Za-z0-9_.@+-]+", result):
        raise ValueError(f"{name} contains unsupported characters")
    return result


def _fs_argument(value: str) -> str:
    """Quote one FreeSWITCH API argument when its value contains separators."""
    if not re.search(r"[\s'\"\\]", value):
        return value
    escaped = value.replace("\\", "\\\\").replace("'", "\\'")
    return f"'{escaped}'"


def _event_value(event: dict[str, object], *names: str) -> str:
    for name in names:
        value = event.get(name)
        if value is not None and str(value):
            return unquote(str(value))
    return ""


@dataclass(slots=True)
class CallBinding:
    call_id: str
    fs_uuid: str
    status_webhook_url: str
    recording_webhook_url: str = ""
    speech_webhook_url: str = ""
    media_webhook_url: str = ""
    metadata: dict[str, Any] = field(default_factory=dict)
    answered_at: datetime | None = None
    recording_path: str = ""
    human_connected: bool = False
    voice_ai_pipeline: str = "legacy"
    pipeline_session_id: str = ""


class FreeswitchEslDriver:
    def __init__(
        self,
        settings: Settings,
        client: EslClient | None = None,
        pipecat_manager: PipecatPipelineManager | None = None,
    ):
        self.settings = settings
        self.client = client or EslClient(
            settings.freeswitch_esl_host,
            settings.freeswitch_esl_port,
            settings.freeswitch_esl_password,
            settings.freeswitch_esl_timeout_sec,
        )
        self.calls_by_uuid: dict[str, CallBinding] = {}
        self.calls_by_id: dict[str, CallBinding] = {}
        self.jobs: dict[str, CallBinding] = {}
        self.listener_task: asyncio.Task[None] | None = None
        self.pipecat_manager = pipecat_manager or (
            PipecatPipelineManager(settings)
            if settings.voice_ai_pipeline.strip().lower() == "pipecat"
            else None
        )

    async def start(self) -> None:
        if self.listener_task is None or self.listener_task.done():
            self.listener_task = asyncio.create_task(self._listen_forever(), name="freeswitch-esl-events")

    async def stop(self) -> None:
        if self.pipecat_manager is not None:
            for call_id in list(self.pipecat_manager.sessions_by_call):
                await self.pipecat_manager.close(call_id)
        if self.listener_task is None:
            return
        self.listener_task.cancel()
        try:
            await self.listener_task
        except asyncio.CancelledError:
            pass
        self.listener_task = None

    async def ready(self) -> bool:
        try:
            response = await self.client.api("status")
            esl_ready = bool(response) and "-ERR" not in response
            pipeline_ready = self.pipecat_manager is None or self.pipecat_manager.ready()
            return esl_ready and pipeline_ready
        except (EslError, OSError, asyncio.TimeoutError):
            return False

    async def post(self, action: str, payload: dict[str, Any]) -> dict[str, Any]:
        handlers = {
            "dial": self._dial,
            "speak": self._speak,
            "stop-speaking": self._stop_speaking,
            "transfer": self._transfer,
            "hangup": self._hangup,
        }
        handler = handlers.get(action)
        if handler is None:
            raise ValueError(f"unsupported FreeSWITCH action: {action}")
        return await handler(payload)

    async def _dial(self, payload: dict[str, Any]) -> dict[str, Any]:
        request = DialRequest.model_validate(payload)
        gateway = _safe_name(
            request.metadata.get("freeswitch_gateway") or self.settings.freeswitch_gateway,
            name="freeswitch_gateway",
        )
        phone = _phone(request.phone)
        caller_id = _phone(
            request.metadata.get("caller_id") or self.settings.freeswitch_caller_id
        ) if (request.metadata.get("caller_id") or self.settings.freeswitch_caller_id) else ""
        fs_uuid = str(uuid4())
        metadata_json = json.dumps(request.metadata, ensure_ascii=False, separators=(",", ":"))
        variables = {
            "origination_uuid": fs_uuid,
            "originate_timeout": str(max(5, self.settings.freeswitch_originate_timeout_sec)),
            "ignore_early_media": "true",
            "platform_call_id_b64": _b64_encode(request.call_id),
            "platform_status_webhook_b64": _b64_encode(str(request.webhook_url)),
            "platform_metadata_b64": _b64_encode(metadata_json),
        }
        if caller_id:
            variables["origination_caller_id_number"] = caller_id
            variables["origination_caller_id_name"] = caller_id
        for metadata_key, variable_key in (
            ("recording_webhook_url", "platform_recording_webhook_b64"),
            ("speech_webhook_url", "platform_speech_webhook_b64"),
            ("media_webhook_url", "platform_media_webhook_b64"),
        ):
            if request.metadata.get(metadata_key):
                variables[variable_key] = _b64_encode(str(request.metadata[metadata_key]))
        variable_block = "{" + ",".join(f"{key}={value}" for key, value in variables.items()) + "}"
        destination = f"sofia/gateway/{gateway}/{phone}"
        human_agent_id = request.metadata.get("human_agent_id")
        if request.metadata.get("mode") == "human_only" and human_agent_id:
            agent_extension = self.settings.freeswitch_agent_extension_template.format(
                agent_id=int(human_agent_id),
                tenant_id=int(request.metadata.get("tenant_id") or 0),
            )
            agent_destination = f"user/{_safe_name(agent_extension, name='agent_extension')}"
            command = f"originate {variable_block}{agent_destination} &bridge({_fs_argument(destination)})"
        else:
            command = f"originate {variable_block}{destination} &park()"
        binding = CallBinding(
            call_id=request.call_id,
            fs_uuid=fs_uuid,
            status_webhook_url=str(request.webhook_url),
            recording_webhook_url=str(request.metadata.get("recording_webhook_url") or ""),
            speech_webhook_url=str(request.metadata.get("speech_webhook_url") or ""),
            media_webhook_url=str(request.metadata.get("media_webhook_url") or ""),
            metadata=dict(request.metadata),
            voice_ai_pipeline=self.settings.voice_ai_pipeline.strip().lower(),
        )
        self.calls_by_uuid[fs_uuid] = binding
        self.calls_by_id[binding.call_id] = binding
        try:
            job_uuid = await self.client.bgapi(command)
        except Exception:
            self.calls_by_uuid.pop(fs_uuid, None)
            self.calls_by_id.pop(binding.call_id, None)
            raise
        if job_uuid:
            self.jobs[job_uuid] = binding
        return {
            "result": "accepted",
            "provider_call_id": fs_uuid,
            "job_uuid": job_uuid,
        }

    def _binding(self, call_id: object) -> CallBinding:
        value = _one_line(call_id, name="call_id")
        binding = self.calls_by_id.get(value) or self.calls_by_uuid.get(value)
        if binding is None:
            raise KeyError(f"FreeSWITCH call is not registered: {value}")
        return binding

    async def _speak(self, payload: dict[str, Any]) -> dict[str, Any]:
        request = SpeakRequest.model_validate(payload)
        binding = self._binding(request.call_id)
        if binding.voice_ai_pipeline == "pipecat":
            if self.pipecat_manager is None:
                raise RuntimeError("Pipecat pipeline manager is unavailable")
            playback_id = await self.pipecat_manager.speak(binding.call_id, request.text)
            return {
                "result": "queued",
                "provider_call_id": binding.fs_uuid,
                "playback_id": playback_id,
                "pipeline": "pipecat",
            }
        media_uri = await self._tts_media_uri(request)
        await self.client.api(f"uuid_broadcast {binding.fs_uuid} {_fs_argument(media_uri)} aleg")
        playback_id = str(uuid4())
        await self._post_media(binding, "speaking", playback_id=playback_id)
        return {
            "result": "playing",
            "provider_call_id": binding.fs_uuid,
            "playback_id": playback_id,
        }

    async def _tts_media_uri(self, request: SpeakRequest) -> str:
        if self.settings.freeswitch_tts_http_endpoint:
            headers = {}
            if self.settings.freeswitch_tts_http_token:
                headers["Authorization"] = f"Bearer {self.settings.freeswitch_tts_http_token}"
            async with httpx.AsyncClient(timeout=self.settings.request_timeout_sec, headers=headers) as client:
                response = await client.post(
                    self.settings.freeswitch_tts_http_endpoint,
                    json=request.model_dump(),
                )
            response.raise_for_status()
            body = response.json()
            media_uri = _one_line(body.get("media_uri") or body.get("url"), name="media_uri")
            if not media_uri:
                raise RuntimeError("TTS service did not return media_uri or url")
            return media_uri
        engine = _safe_name(request.provider or self.settings.freeswitch_tts_engine, name="tts_engine")
        voice = _safe_name(request.voice or self.settings.freeswitch_tts_voice, name="tts_voice")
        text = _one_line(request.text, name="tts_text").replace("|", " ")
        return self.settings.freeswitch_tts_uri_template.format(
            engine=engine,
            voice=voice,
            text=text,
            language=_one_line(request.language, name="language"),
        )

    async def _stop_speaking(self, payload: dict[str, Any]) -> dict[str, Any]:
        binding = self._binding(payload.get("call_id"))
        if binding.voice_ai_pipeline == "pipecat":
            if self.pipecat_manager is None:
                raise RuntimeError("Pipecat pipeline manager is unavailable")
            await self.pipecat_manager.interrupt(binding.call_id)
            return {
                "result": "stopped",
                "provider_call_id": binding.fs_uuid,
                "pipeline": "pipecat",
            }
        await self.client.api(f"uuid_break {binding.fs_uuid} all")
        await self._post_media(binding, "interrupted")
        return {"result": "stopped", "provider_call_id": binding.fs_uuid}

    async def _transfer(self, payload: dict[str, Any]) -> dict[str, Any]:
        binding = self._binding(payload.get("call_id"))
        target_group = _one_line(payload.get("target_group"), name="target_group")
        match = re.fullmatch(r"agent:([0-9]+)", target_group)
        if match:
            destination = self.settings.freeswitch_agent_extension_template.format(
                agent_id=match.group(1),
                tenant_id=int(binding.metadata.get("tenant_id") or 0),
            )
        elif target_group in {"", "default"}:
            destination = self.settings.freeswitch_default_handoff_extension
        else:
            destination = target_group
        destination = _safe_name(destination, name="transfer_destination")
        context = _safe_name(self.settings.freeswitch_dialplan_context, name="dialplan_context")
        await self.client.api(f"uuid_break {binding.fs_uuid} all")
        await self._stop_ai_media(binding)
        binding.metadata["human_target"] = target_group
        binding.human_connected = False
        await self.client.api(f"uuid_transfer {binding.fs_uuid} {destination} XML {context}")
        return {
            "result": "transferred",
            "provider_call_id": binding.fs_uuid,
            "target_group": target_group or "default",
            "destination": destination,
        }

    async def _hangup(self, payload: dict[str, Any]) -> dict[str, Any]:
        binding = self._binding(payload.get("call_id"))
        await self._stop_ai_media(binding)
        await self.client.api(f"uuid_kill {binding.fs_uuid} NORMAL_CLEARING")
        return {
            "result": "hungup",
            "provider_call_id": binding.fs_uuid,
            "reason": _one_line(payload.get("reason"), name="reason"),
        }

    async def _listen_forever(self) -> None:
        while True:
            try:
                async for event in self.client.events(EVENT_NAMES):
                    await self._handle_event(event)
            except asyncio.CancelledError:
                raise
            except Exception as exc:
                logger.warning("FreeSWITCH ESL listener disconnected: %s", exc)
                await asyncio.sleep(max(0.25, self.settings.freeswitch_esl_reconnect_sec))

    async def _handle_event(self, event: dict[str, object]) -> None:
        name = _event_value(event, "Event-Name")
        if name == "BACKGROUND_JOB":
            job_uuid = _event_value(event, "Job-UUID")
            binding = self.jobs.pop(job_uuid, None)
            body = _event_value(event, "_body", "Body")
            if binding is not None and body.startswith("-ERR"):
                await self._post_status(binding, "failed", event, hangup_reason=body[:500])
            return
        binding = self._binding_from_event(event)
        if binding is None:
            return
        if name in {"CHANNEL_CREATE", "CHANNEL_PROGRESS", "CHANNEL_PROGRESS_MEDIA"}:
            await self._post_status(binding, "dialing", event)
        elif name == "CHANNEL_ANSWER":
            binding.answered_at = datetime.now(timezone.utc)
            if binding.metadata.get("mode") == "human_only" and binding.metadata.get("human_agent_id"):
                await self._post_status(binding, "agent_answered", event)
            else:
                await self._post_status(binding, "answered", event)
                await self._start_recording_and_media(binding)
        elif name == "CHANNEL_BRIDGE":
            if binding.metadata.get("human_target") or binding.metadata.get("human_agent_id"):
                binding.human_connected = True
                await self._post_status(binding, "human_connected", event)
                await self._start_recording_and_media(binding, start_ai_media=False)
        elif name == "CHANNEL_UNBRIDGE":
            if binding.metadata.get("human_target") or binding.metadata.get("human_agent_id"):
                await self._post_status(binding, "human_disconnected", event)
        elif name == "CHANNEL_EXECUTE_COMPLETE":
            application = _event_value(event, "Application", "variable_current_application").lower()
            if application == "bridge" and binding.metadata.get("human_target") and not binding.human_connected:
                response = _event_value(event, "Application-Response", "variable_originate_disposition")
                await self._post_status(
                    binding,
                    "human_unavailable",
                    event,
                    hangup_reason=response or "agent did not answer",
                )
        elif name == "CHANNEL_HANGUP_COMPLETE":
            cause = _event_value(event, "Hangup-Cause", "variable_hangup_cause") or "UNKNOWN"
            if cause in BUSY_CAUSES:
                status = "busy"
            elif cause in NO_ANSWER_CAUSES:
                status = "no_answer"
            elif cause in NORMAL_CAUSES:
                status = "ended"
            else:
                status = "failed"
            await self._post_status(binding, status, event, hangup_reason=cause)
            if binding.voice_ai_pipeline == "pipecat" and self.pipecat_manager is not None:
                await self.pipecat_manager.close(binding.call_id)
            else:
                await self._post_media(binding, "closed", event=event)
            await self._post_recording(binding)
            self.calls_by_uuid.pop(binding.fs_uuid, None)
            self.calls_by_id.pop(binding.call_id, None)

    def _binding_from_event(self, event: dict[str, object]) -> CallBinding | None:
        fs_uuid = _event_value(event, "Unique-ID", "Channel-Call-UUID", "variable_origination_uuid")
        binding = self.calls_by_uuid.get(fs_uuid)
        if binding is not None:
            return binding
        try:
            call_id = _b64_decode(_event_value(event, "variable_platform_call_id_b64"))
            status_url = _b64_decode(_event_value(event, "variable_platform_status_webhook_b64"))
            metadata_raw = _b64_decode(_event_value(event, "variable_platform_metadata_b64"))
            if not call_id or not status_url:
                return None
            metadata = json.loads(metadata_raw) if metadata_raw else {}
            binding = CallBinding(
                call_id=call_id,
                fs_uuid=fs_uuid,
                status_webhook_url=status_url,
                recording_webhook_url=_b64_decode(_event_value(event, "variable_platform_recording_webhook_b64")),
                speech_webhook_url=_b64_decode(_event_value(event, "variable_platform_speech_webhook_b64")),
                media_webhook_url=_b64_decode(_event_value(event, "variable_platform_media_webhook_b64")),
                metadata=metadata if isinstance(metadata, dict) else {},
                voice_ai_pipeline=self.settings.voice_ai_pipeline.strip().lower(),
            )
        except (ValueError, TypeError, json.JSONDecodeError):
            return None
        self.calls_by_uuid[fs_uuid] = binding
        self.calls_by_id[call_id] = binding
        return binding

    async def _start_recording_and_media(self, binding: CallBinding, *, start_ai_media: bool = True) -> None:
        if bool(binding.metadata.get("recording_enabled", True)) and not binding.recording_path:
            safe_call_id = re.sub(r"[^A-Za-z0-9_.-]", "_", binding.call_id)
            filename = f"{safe_call_id}-{binding.fs_uuid}.wav"
            recording_path = str(Path(self.settings.freeswitch_recording_dir) / filename)
            await self.client.api(f"uuid_record {binding.fs_uuid} start {recording_path}")
            binding.recording_path = recording_path
        if not start_ai_media:
            await self._post_media(binding, "listening")
            return
        if binding.voice_ai_pipeline == "pipecat":
            await self._start_pipecat_media(binding)
            return
        await self._start_legacy_media(binding)

    async def _start_legacy_media(self, binding: CallBinding) -> None:
        template = self.settings.freeswitch_media_start_command_template.strip()
        if template:
            command = template.format(
                uuid=binding.fs_uuid,
                call_id=binding.call_id,
                speech_webhook_url=binding.speech_webhook_url,
                media_webhook_url=binding.media_webhook_url,
                asr_provider=str(binding.metadata.get("asr_provider") or ""),
                language=str(binding.metadata.get("language") or "zh-CN"),
            )
            await self.client.api(_one_line(command, name="media_start_command"))
        await self._post_media(binding, "listening")

    async def _start_pipecat_media(self, binding: CallBinding) -> None:
        if self.pipecat_manager is None:
            raise RuntimeError("Pipecat pipeline manager is unavailable")
        session = await self.pipecat_manager.create_session(
            call_id=binding.call_id,
            speech_webhook_url=binding.speech_webhook_url,
            media_webhook_url=binding.media_webhook_url,
            metadata=binding.metadata,
        )
        binding.pipeline_session_id = session.session_id
        command = self.settings.freeswitch_pipecat_start_command_template.format(
            uuid=binding.fs_uuid,
            call_id=binding.call_id,
            session_id=session.session_id,
            media_ws_url=self.pipecat_manager.media_ws_url(session),
            sample_rate=self.settings.pipecat_sample_rate,
            channels=self.settings.pipecat_channels,
            codec="pcm_s16le",
        )
        try:
            await self.client.api(_one_line(command, name="pipecat_media_start_command"))
        except Exception:
            await self.pipecat_manager.close(binding.call_id, notify=False)
            if not self.settings.pipecat_fallback_to_legacy:
                raise
            logger.exception("Pipecat media start failed; explicitly falling back to legacy")
            binding.voice_ai_pipeline = "legacy"
            binding.pipeline_session_id = ""
            await self._start_legacy_media(binding)

    async def _stop_ai_media(self, binding: CallBinding) -> None:
        if binding.voice_ai_pipeline == "pipecat" and self.pipecat_manager is not None:
            await self.pipecat_manager.close(binding.call_id)
        stop_template = self.settings.freeswitch_media_stop_command_template.strip()
        if stop_template:
            stop_command = stop_template.format(uuid=binding.fs_uuid, call_id=binding.call_id)
            await self.client.api(_one_line(stop_command, name="media_stop_command"))

    async def _post_status(
        self,
        binding: CallBinding,
        status: str,
        event: dict[str, object],
        *,
        hangup_reason: str = "",
    ) -> None:
        event_stamp = _event_value(event, "Event-Date-Timestamp", "Event-Sequence") or str(uuid4())
        provider_event_id = f"fs:{binding.fs_uuid}:{status}:{event_stamp}"
        data = dict(binding.metadata)
        data.update({
            "status": status,
            "telephony_call_id": binding.fs_uuid,
            "event_id": provider_event_id,
            "voice_ai_pipeline": binding.voice_ai_pipeline,
        })
        if hangup_reason:
            data["hangup_reason"] = hangup_reason
        await self._post_json(
            binding.status_webhook_url,
            {"call_id": binding.call_id, "kind": "status", "payload": data},
        )

    async def _post_media(
        self,
        binding: CallBinding,
        state: str,
        *,
        playback_id: str | None = None,
        event: dict[str, object] | None = None,
    ) -> None:
        if not binding.media_webhook_url:
            return
        stamp = _event_value(event or {}, "Event-Date-Timestamp", "Event-Sequence") or str(uuid4())
        duration_ms = None
        if state == "closed" and binding.answered_at is not None:
            duration_ms = max(0, int((datetime.now(timezone.utc) - binding.answered_at).total_seconds() * 1000))
        await self._post_json(
            binding.media_webhook_url,
            {
                "call_id": binding.call_id,
                "event_id": f"fs:{binding.fs_uuid}:media:{state}:{stamp}",
                "state": state,
                "provider_session_id": binding.fs_uuid,
                "playback_id": playback_id,
                "codec": "PCMA",
                "sample_rate": 8000,
                "channel_count": 1,
                "duration_ms": duration_ms,
                "provider": "freeswitch",
            },
        )

    async def _post_recording(self, binding: CallBinding) -> None:
        if not binding.recording_webhook_url or not binding.recording_path:
            return
        public_base = self.settings.freeswitch_recording_public_base_url.rstrip("/")
        if not public_base:
            logger.warning("recording exists locally but FREESWITCH_RECORDING_PUBLIC_BASE_URL is empty")
            return
        duration_sec = None
        if binding.answered_at is not None:
            duration_sec = max(0, int((datetime.now(timezone.utc) - binding.answered_at).total_seconds()))
        filename = Path(binding.recording_path).name
        await self._post_json(
            binding.recording_webhook_url,
            {
                "call_id": binding.call_id,
                "kind": "recording",
                "payload": {
                    "url": f"{public_base}/{filename}",
                    "recording_id": binding.fs_uuid,
                    "format": "wav",
                    "channel_count": 2 if self.settings.freeswitch_recording_stereo else 1,
                    "duration_sec": duration_sec,
                    "attempt": binding.metadata.get("attempt"),
                    "state": "available",
                },
            },
        )

    async def _post_json(self, url: str, payload: dict[str, Any]) -> None:
        headers = {"x-webhook-token": self.settings.webhook_token} if self.settings.webhook_token else {}
        for attempt in range(3):
            try:
                async with httpx.AsyncClient(timeout=self.settings.request_timeout_sec, headers=headers) as client:
                    response = await client.post(url, json=payload)
                response.raise_for_status()
                return
            except httpx.HTTPError as exc:
                if attempt == 2:
                    logger.error("FreeSWITCH callback delivery failed after retries: %s", exc)
                    return
                await asyncio.sleep(0.2 * (2**attempt))
