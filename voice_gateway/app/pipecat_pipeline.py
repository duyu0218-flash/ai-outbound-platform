from __future__ import annotations

import asyncio
import json
import logging
import secrets
from dataclasses import dataclass, field
from datetime import datetime, timezone
from importlib.metadata import version
from typing import Any
from uuid import uuid4

import httpx
from fastapi import WebSocket
from pipecat.frames.frames import (
    EndFrame,
    Frame,
    InputAudioRawFrame,
    InterimTranscriptionFrame,
    OutputAudioRawFrame,
    OutputTransportMessageFrame,
    InterruptionFrame,
    TranscriptionFrame,
    TTSStartedFrame,
    TTSStoppedFrame,
    TTSSpeakFrame,
    UserStartedSpeakingFrame,
)
from pipecat.pipeline.pipeline import Pipeline
from pipecat.pipeline.worker import PipelineParams, PipelineWorker, ProcessorUnusablePolicy
from pipecat.processors.frame_processor import FrameDirection, FrameProcessor
from pipecat.serializers.base_serializer import FrameSerializer
from pipecat.services.openai.stt import OpenAIRealtimeSTTService
from pipecat.services.openai.tts import OpenAITTSService
from pipecat.transports.websocket.fastapi import FastAPIWebsocketParams, FastAPIWebsocketTransport
from pipecat.transcriptions.language import Language
from pipecat.workers.runner import WorkerRunner

from .aliyun_nls_stt import AliyunNLSSTTService
from .config import Settings


logger = logging.getLogger(__name__)


class MediaPlaybackBusyError(RuntimeError):
    """A new utterance cannot yet be correlated to module playout events."""


class RawPcmSerializer(FrameSerializer):
    """Serialize the FreeSWITCH media WebSocket as headerless PCM16 frames."""

    def __init__(self, sample_rate: int, channels: int = 1, protocol: str = "raw_pcm"):
        super().__init__(
            params=FrameSerializer.InputParams(
                ignore_rtvi_messages=True,
                resampler_clear_after_secs=None,
            )
        )
        self.sample_rate = sample_rate
        self.channels = channels
        self.protocol = protocol

    async def serialize(self, frame: Frame) -> bytes | str | None:
        if isinstance(frame, OutputAudioRawFrame):
            return frame.audio
        if self.protocol == "voismart":
            if isinstance(frame, InterruptionFrame):
                # Pipecat's transport clears its own queue BEFORE serializing this.
                # FreeSWITCH has a second queue which must also be cleared.
                return json.dumps({"type": "input_audio_buffer.speech_started"})
            if isinstance(frame, OutputTransportMessageFrame):
                if frame.message == {"type": "response.output_audio.done"}:
                    return json.dumps(frame.message)
        return None

    async def deserialize(self, data: str | bytes) -> Frame | None:
        if isinstance(data, str):
            return None
        return InputAudioRawFrame(
            audio=bytes(data),
            sample_rate=self.sample_rate,
            num_channels=self.channels,
        )


@dataclass(slots=True)
class PipecatCallSession:
    call_id: str
    session_id: str
    token: str
    speech_webhook_url: str
    media_webhook_url: str
    metadata: dict[str, Any]
    created_at: datetime = field(default_factory=lambda: datetime.now(timezone.utc))
    worker: PipelineWorker | None = None
    runner: WorkerRunner | None = None
    pending_speech: list[TTSSpeakFrame] = field(default_factory=list)
    playback_id: str | None = None
    connected: bool = False
    closed_notified: bool = False
    asr_error_code: str | None = None
    module_speaking: bool = False
    interrupting: bool = False
    playout_stopped: asyncio.Event = field(default_factory=asyncio.Event)
    last_module_event_us: int = 0
    startup_complete: asyncio.Event = field(default_factory=asyncio.Event)
    terminated: asyncio.Event = field(default_factory=asyncio.Event)
    media_error_code: str | None = None
    closing: bool = False
    websocket_task: asyncio.Task | None = None


class TranscriptWebhookProcessor(FrameProcessor):
    def __init__(self, manager: "PipecatPipelineManager", session: PipecatCallSession):
        super().__init__(name=f"transcript-webhook-{session.call_id}")
        self.manager = manager
        self.session = session
        self.sequence = 0
        self.user_is_speaking = False

    async def process_frame(self, frame: Frame, direction: FrameDirection):
        await super().process_frame(frame, direction)
        if isinstance(frame, (TranscriptionFrame, InterimTranscriptionFrame)):
            self.sequence += 1
            is_final = isinstance(frame, TranscriptionFrame)
            metadata = _transcript_metadata(frame.result)
            await self.manager.post_speech(
                self.session,
                transcript=frame.text,
                is_final=is_final,
                event_id=metadata.get("provider_event_id")
                or f"pipecat:{self.session.session_id}:{self.sequence}",
                barge_in=self.user_is_speaking,
                confidence=metadata.get("confidence"),
                start_ms=metadata.get("start_ms"),
                end_ms=metadata.get("end_ms"),
                latency_ms=metadata.get("latency_ms"),
            )
            if is_final:
                self.user_is_speaking = False
            return
        if isinstance(frame, UserStartedSpeakingFrame):
            self.user_is_speaking = True
            if self.manager.settings.pipecat_media_protocol == "voismart":
                # ASR/VAD speech-start alone is not a transport interruption.
                await self.push_frame(InterruptionFrame(), FrameDirection.DOWNSTREAM)
            else:
                await self.manager.post_media(self.session, "interrupted")
        await self.push_frame(frame, direction)


class MediaStateWebhookProcessor(FrameProcessor):
    def __init__(self, manager: "PipecatPipelineManager", session: PipecatCallSession):
        super().__init__(name=f"media-state-webhook-{session.call_id}")
        self.manager = manager
        self.session = session

    async def process_frame(self, frame: Frame, direction: FrameDirection):
        await super().process_frame(frame, direction)
        if self.manager.settings.pipecat_media_protocol == "voismart":
            if isinstance(frame, InterruptionFrame):
                self.session.playback_id = None
                # A slow control-plane webhook must not delay clearing audio.
                await self.push_frame(frame, direction)
                await self.manager.post_media(self.session, "interrupted")
                return
            await self.push_frame(frame, direction)
            if isinstance(frame, TTSStoppedFrame) and direction == FrameDirection.DOWNSTREAM:
                # Non-urgent: follows the transport's flushed trailing PCM, not
                # the faster TTS generation clock. Only ESL reports actual drain.
                await self.push_frame(OutputTransportMessageFrame(
                    message={"type": "response.output_audio.done"},
                ), direction)
            return
        if isinstance(frame, TTSStartedFrame):
            await self.manager.post_media(
                self.session,
                "speaking",
                playback_id=self.session.playback_id,
            )
        elif isinstance(frame, TTSStoppedFrame):
            self.session.playback_id = None
            await self.manager.post_media(self.session, "listening")
        await self.push_frame(frame, direction)


class PipecatPipelineManager:
    """Own one Pipecat media worker for each active FreeSWITCH call."""

    def __init__(self, settings: Settings):
        self.settings = settings
        self.sessions_by_call: dict[str, PipecatCallSession] = {}
        self.sessions_by_token: dict[str, PipecatCallSession] = {}
        self._lock = asyncio.Lock()

    def ready(self) -> bool:
        return version("pipecat-ai") == self.settings.pipecat_version

    async def create_session(
        self,
        *,
        call_id: str,
        speech_webhook_url: str,
        media_webhook_url: str,
        metadata: dict[str, Any],
    ) -> PipecatCallSession:
        async with self._lock:
            existing = self.sessions_by_call.get(call_id)
            if existing is not None:
                return existing
            if len(self.sessions_by_call) >= self.settings.pipecat_max_active_sessions:
                raise RuntimeError("Pipecat active session capacity reached")
            session = PipecatCallSession(
                call_id=call_id,
                session_id=str(uuid4()),
                token=secrets.token_urlsafe(32),
                speech_webhook_url=speech_webhook_url,
                media_webhook_url=media_webhook_url,
                metadata=dict(metadata),
            )
            self.sessions_by_call[call_id] = session
            self.sessions_by_token[session.token] = session
            return session

    def media_ws_url(self, session: PipecatCallSession) -> str:
        return f"{self.settings.pipecat_media_ws_base.rstrip('/')}/{session.token}"

    async def run_websocket(self, websocket: WebSocket, token: str) -> None:
        async with self._lock:
            session = self.sessions_by_token.get(token)
            if session is None:
                await websocket.close(code=4404, reason="unknown Pipecat media session")
                return
            age = (datetime.now(timezone.utc) - session.created_at).total_seconds()
            if age > self.settings.pipecat_session_timeout_sec:
                self.sessions_by_token.pop(token, None)
                self.sessions_by_call.pop(session.call_id, None)
                session.media_error_code = "MEDIA_CONNECT_TIMEOUT"
                session.terminated.set()
                session.startup_complete.set()
                await websocket.close(code=4408, reason="expired Pipecat media session")
                return
            if session.connected:
                await websocket.close(code=4409, reason="Pipecat media session is already connected")
                return
            # Reserve under the lock: two sockets must never claim one token.
            session.connected = True
            session.websocket_task = asyncio.current_task()

        try:
            # Pipecat's FastAPI transport expects an accepted socket. Accept
            # only after authenticating and atomically reserving the token.
            await websocket.accept()
            await self._run_session(websocket, session)
        except asyncio.CancelledError:
            if not session.closing:
                raise
        except Exception:
            session.media_error_code = session.media_error_code or "MEDIA_PIPELINE_ERROR"
            raise
        finally:
            session.terminated.set()
            session.startup_complete.set()
            session.connected = False
            session.worker = None
            session.runner = None
            session.websocket_task = None
            if self.sessions_by_call.get(session.call_id) is session:
                self.sessions_by_call.pop(session.call_id, None)
            if self.sessions_by_token.get(session.token) is session:
                self.sessions_by_token.pop(session.token, None)
            await self._post_closed_once(session, error_code=session.media_error_code or session.asr_error_code)

    async def _run_session(self, websocket: WebSocket, session: PipecatCallSession) -> None:
        serializer = RawPcmSerializer(
            sample_rate=self.settings.pipecat_sample_rate,
            channels=self.settings.pipecat_channels,
            protocol=self.settings.pipecat_media_protocol,
        )
        transport = FastAPIWebsocketTransport(
            websocket,
            FastAPIWebsocketParams(
                audio_in_enabled=True,
                audio_out_enabled=True,
                audio_in_sample_rate=self.settings.pipecat_sample_rate,
                audio_out_sample_rate=self.settings.pipecat_sample_rate,
                audio_in_channels=self.settings.pipecat_channels,
                audio_out_channels=self.settings.pipecat_channels,
                add_wav_header=False,
                serializer=serializer,
                session_timeout=self.settings.pipecat_session_timeout_sec,
                allowed_origins=[],
            ),
        )
        stt, tts = self.make_services(session)

        @stt.event_handler("on_connected")
        async def on_stt_connected(_service):
            session.asr_error_code = None

        @stt.event_handler("on_connection_error")
        async def on_stt_connection_error(_service, _error: str):
            session.asr_error_code = "ASR_CONNECTION_ERROR"
            try:
                await self.post_media(session, "listening", error_code=session.asr_error_code)
            except Exception:
                logger.exception("failed to persist ASR connection error for call %s", session.call_id)

        transcript = TranscriptWebhookProcessor(self, session)
        media_state = MediaStateWebhookProcessor(self, session)
        pipeline = Pipeline([transport.input(), stt, transcript, tts, media_state, transport.output()])
        worker = PipelineWorker(
            pipeline,
            name=f"pipecat-call-{session.call_id}",
            params=PipelineParams(
                audio_in_sample_rate=self.settings.pipecat_sample_rate,
                audio_out_sample_rate=self.settings.pipecat_sample_rate,
                enable_metrics=True,
                enable_usage_metrics=True,
            ),
            idle_timeout_secs=self.settings.pipecat_session_timeout_sec,
            processor_unusable_policy=ProcessorUnusablePolicy.END,
        )
        runner = WorkerRunner(handle_sigint=False)
        session.connected = True
        try:
            await runner.add_workers(worker)
        except Exception:
            session.connected = False
            raise
        session.worker = worker
        session.runner = runner

        @transport.event_handler("on_client_connected")
        async def on_client_connected(_transport, _client):
            session.startup_complete.set()
            await self.post_media(session, "listening")
            pending = list(session.pending_speech)
            session.pending_speech.clear()
            if pending:
                await worker.queue_frames(pending)

        @transport.event_handler("on_client_disconnected")
        async def on_client_disconnected(_transport, _client):
            # Socket closure alone cannot distinguish a caller hangup from a
            # transport failure. The ESL lifecycle checks channel existence.
            session.terminated.set()
            session.startup_complete.set()
            session.connected = False
            session.worker = None
            async with self._lock:
                if self.sessions_by_call.get(session.call_id) is session:
                    self.sessions_by_call.pop(session.call_id, None)
                if self.sessions_by_token.get(session.token) is session:
                    self.sessions_by_token.pop(session.token, None)
            await self._post_closed_once(session, error_code=session.media_error_code or session.asr_error_code)
            await runner.cancel()

        try:
            await runner.run()
        finally:
            session.connected = False
            session.worker = None
            session.runner = None

    def make_services(self, session: PipecatCallSession):
        """Provider construction seam used by the isolated, synthetic media test."""
        return _make_stt_service(self.settings, session), OpenAITTSService(
            api_key=self.settings.pipecat_openai_api_key,
            base_url=self.settings.pipecat_openai_base_url or None,
            voice=self.settings.pipecat_tts_voice,
            model=self.settings.pipecat_tts_model,
            sample_rate=self.settings.pipecat_sample_rate,
        )

    async def handle_module_event(self, call_id: str, kind: str, timestamp_us: int = 0) -> None:
        session = self.sessions_by_call.get(call_id)
        if session is None or self.settings.pipecat_media_protocol != "voismart":
            return
        if timestamp_us and timestamp_us <= session.last_module_event_us:
            return
        session.last_module_event_us = timestamp_us or session.last_module_event_us
        if kind == "openai_speech_start":
            session.module_speaking = True
            session.playout_stopped.clear()
            await self.post_media(session, "speaking", playback_id=session.playback_id)
        elif kind == "openai_speech_stop":
            session.module_speaking = False
            session.playback_id = None
            session.playout_stopped.set()
            await self.post_media(session, "listening")
        elif kind in {"error", "disconnect"}:
            if kind == "error":
                session.media_error_code = "MEDIA_MODULE_ERROR"
            await self.close(call_id)

    async def speak(self, call_id: str, text: str) -> str:
        session = self._session(call_id)
        if self.settings.pipecat_media_protocol == "voismart" and (session.playback_id or session.interrupting or session.module_speaking):
            # Upstream raw-mode events have no per-response ID. Serialize turns
            # rather than attributing a prior drain event to a newer utterance.
            raise MediaPlaybackBusyError("media playback is busy; wait for drain or stop-speaking first")
        frame = TTSSpeakFrame(text=text, append_to_context=False)
        playback_id = str(uuid4())
        session.playback_id = playback_id
        if session.worker is None:
            session.pending_speech.append(frame)
        else:
            await session.worker.queue_frames([frame])
        return playback_id

    async def interrupt(self, call_id: str) -> None:
        session = self._session(call_id)
        session.pending_speech.clear()
        session.interrupting = True
        try:
            if session.worker is not None:
                await session.worker.queue_frames([InterruptionFrame()])
                if self.settings.pipecat_media_protocol == "voismart" and session.module_speaking:
                    await asyncio.wait_for(session.playout_stopped.wait(), timeout=3.0)
            if self.settings.pipecat_media_protocol != "voismart" or session.worker is None:
                await self.post_media(session, "interrupted")
            session.playback_id = None
        finally:
            session.interrupting = False

    async def close(self, call_id: str, *, notify: bool = True) -> None:
        session = self.sessions_by_call.pop(call_id, None)
        if session is None:
            return
        self.sessions_by_token.pop(session.token, None)
        session.closing = True
        session.terminated.set()
        session.startup_complete.set()
        session.pending_speech.clear()
        session.playout_stopped.set()
        if session.runner is not None:
            # Hangup/transfer must discard queued speech, not drain it via EndFrame.
            await session.runner.cancel()
        elif session.worker is not None:
            await session.worker.queue_frames([EndFrame()])
        elif session.websocket_task is not None and session.websocket_task is not asyncio.current_task():
            # An accepted socket can still be constructing its worker when
            # hangup/timeout arrives. Do not let setup resurrect a closed call.
            session.websocket_task.cancel()
            await session.websocket_task
        if notify:
            await self._post_closed_once(session, error_code=session.media_error_code or session.asr_error_code)

    async def _post_closed_once(
        self,
        session: PipecatCallSession,
        *,
        error_code: str | None = None,
    ) -> None:
        if session.closed_notified:
            return
        session.closed_notified = True
        await self.post_media(session, "closed", error_code=error_code)

    def _session(self, call_id: str) -> PipecatCallSession:
        session = self.sessions_by_call.get(call_id)
        if session is None or session.closing or session.terminated.is_set():
            raise KeyError(f"Pipecat call session is not registered: {call_id}")
        return session

    async def post_speech(
        self,
        session: PipecatCallSession,
        *,
        transcript: str,
        is_final: bool,
        event_id: str,
        barge_in: bool = False,
        confidence: float | None = None,
        start_ms: int | None = None,
        end_ms: int | None = None,
        latency_ms: int | None = None,
    ) -> None:
        if not session.speech_webhook_url:
            return
        await self._post_json(
            session.speech_webhook_url,
            {
                "call_id": session.call_id,
                "event_id": event_id,
                "transcript": transcript,
                "is_final": is_final,
                "speaker_role": "customer",
                "channel_id": "inbound",
                "asr_provider": f"pipecat:{self.settings.pipecat_stt_provider}",
                "barge_in": barge_in,
                "attempt": session.metadata.get("attempt"),
                "confidence": confidence,
                "start_ms": start_ms,
                "end_ms": end_ms,
                "latency_ms": latency_ms,
            },
        )

    async def post_media(
        self,
        session: PipecatCallSession,
        state: str,
        *,
        playback_id: str | None = None,
        error_code: str | None = None,
    ) -> None:
        if not session.media_webhook_url:
            return
        await self._post_json(
            session.media_webhook_url,
            {
                "call_id": session.call_id,
                "event_id": f"pipecat:{session.session_id}:media:{state}:{uuid4()}",
                "state": state,
                "provider_session_id": session.session_id,
                "playback_id": playback_id,
                "codec": "pcm_s16le",
                "sample_rate": self.settings.pipecat_sample_rate,
                "channel_count": self.settings.pipecat_channels,
                "provider": f"pipecat:{self.settings.pipecat_version}",
                "attempt": session.metadata.get("attempt"),
                "error_code": error_code,
            },
        )

    async def _post_json(self, url: str, payload: dict[str, Any]) -> None:
        from .security import CallbackSender

        await CallbackSender(self.settings).post(url, payload)


def _language(value: object) -> Language:
    requested = str(value or "zh-CN").strip()
    try:
        return Language(requested)
    except ValueError:
        base = requested.split("-", 1)[0].lower()
        try:
            return Language(base)
        except ValueError:
            logger.warning("unsupported Pipecat language %s; falling back to zh-CN", requested)
            return Language.ZH_CN


def _make_stt_service(settings: Settings, session: PipecatCallSession):
    provider = settings.pipecat_stt_provider.strip().lower()
    if provider == "aliyun-nls":
        return AliyunNLSSTTService(
            appkey=settings.aliyun_nls_appkey.strip(),
            token_getter=settings.resolved_aliyun_nls_token,
            gateway_url=settings.aliyun_nls_gateway_url.strip(),
            sample_rate=settings.pipecat_sample_rate,
            vocabulary_id=settings.aliyun_nls_vocabulary_id.strip(),
            customization_id=settings.aliyun_nls_customization_id.strip(),
            max_sentence_silence_ms=settings.aliyun_nls_max_sentence_silence_ms,
            enable_punctuation_prediction=settings.aliyun_nls_enable_punctuation_prediction,
            enable_inverse_text_normalization=settings.aliyun_nls_enable_inverse_text_normalization,
            enable_words=settings.aliyun_nls_enable_words,
            enable_semantic_sentence_detection=settings.aliyun_nls_enable_semantic_sentence_detection,
            enable_ignore_sentence_timeout=settings.aliyun_nls_enable_ignore_sentence_timeout,
            disfluency=settings.aliyun_nls_disfluency,
            connect_timeout_sec=settings.aliyun_nls_connect_timeout_sec,
            stop_timeout_sec=settings.aliyun_nls_stop_timeout_sec,
        )
    return OpenAIRealtimeSTTService(
        api_key=settings.pipecat_openai_api_key,
        base_url=settings.pipecat_openai_realtime_base_url,
        language=_language(session.metadata.get("language")),
    )


def _transcript_metadata(result: object) -> dict[str, Any]:
    if not isinstance(result, dict):
        return {}
    confidence = result.get("confidence")
    if not isinstance(confidence, (int, float)) or isinstance(confidence, bool):
        confidence = None
    start_ms = result.get("start_ms")
    if not isinstance(start_ms, int) or isinstance(start_ms, bool) or start_ms < 0:
        start_ms = None
    end_ms = result.get("end_ms")
    if not isinstance(end_ms, int) or isinstance(end_ms, bool) or end_ms < 0:
        end_ms = None
    latency_ms = result.get("latency_ms")
    if not isinstance(latency_ms, int) or isinstance(latency_ms, bool) or latency_ms < 0:
        latency_ms = None
    provider_event_id = result.get("provider_event_id")
    if not isinstance(provider_event_id, str) or not provider_event_id.strip():
        provider_event_id = None
    return {
        "provider_event_id": provider_event_id,
        "confidence": float(confidence) if confidence is not None else None,
        "start_ms": start_ms,
        "end_ms": end_ms,
        "latency_ms": latency_ms,
    }
