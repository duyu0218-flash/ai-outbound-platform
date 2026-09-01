from __future__ import annotations

import asyncio
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

from .config import Settings


logger = logging.getLogger(__name__)


class RawPcmSerializer(FrameSerializer):
    """Serialize the FreeSWITCH media WebSocket as headerless PCM16 frames."""

    def __init__(self, sample_rate: int, channels: int = 1):
        super().__init__(
            params=FrameSerializer.InputParams(
                ignore_rtvi_messages=True,
                resampler_clear_after_secs=None,
            )
        )
        self.sample_rate = sample_rate
        self.channels = channels

    async def serialize(self, frame: Frame) -> bytes | None:
        if isinstance(frame, OutputAudioRawFrame):
            return frame.audio
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
            await self.manager.post_speech(
                self.session,
                transcript=frame.text,
                is_final=is_final,
                event_id=f"pipecat:{self.session.session_id}:{self.sequence}",
                barge_in=self.user_is_speaking,
            )
            if is_final:
                self.user_is_speaking = False
            return
        if isinstance(frame, UserStartedSpeakingFrame):
            self.user_is_speaking = True
            await self.manager.post_media(self.session, "interrupted")
        await self.push_frame(frame, direction)


class MediaStateWebhookProcessor(FrameProcessor):
    def __init__(self, manager: "PipecatPipelineManager", session: PipecatCallSession):
        super().__init__(name=f"media-state-webhook-{session.call_id}")
        self.manager = manager
        self.session = session

    async def process_frame(self, frame: Frame, direction: FrameDirection):
        await super().process_frame(frame, direction)
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
                await websocket.close(code=4408, reason="expired Pipecat media session")
                return
            if session.connected:
                await websocket.close(code=4409, reason="Pipecat media session is already connected")
                return

        serializer = RawPcmSerializer(
            sample_rate=self.settings.pipecat_sample_rate,
            channels=self.settings.pipecat_channels,
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
        stt = OpenAIRealtimeSTTService(
            api_key=self.settings.pipecat_openai_api_key,
            base_url=self.settings.pipecat_openai_realtime_base_url,
            language=_language(session.metadata.get("language")),
        )
        tts = OpenAITTSService(
            api_key=self.settings.pipecat_openai_api_key,
            base_url=self.settings.pipecat_openai_base_url or None,
            voice=self.settings.pipecat_tts_voice,
            model=self.settings.pipecat_tts_model,
            sample_rate=self.settings.pipecat_sample_rate,
        )
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
            await self.post_media(session, "listening")
            pending = list(session.pending_speech)
            session.pending_speech.clear()
            if pending:
                await worker.queue_frames(pending)

        @transport.event_handler("on_client_disconnected")
        async def on_client_disconnected(_transport, _client):
            session.connected = False
            session.worker = None
            async with self._lock:
                if self.sessions_by_call.get(session.call_id) is session:
                    self.sessions_by_call.pop(session.call_id, None)
                if self.sessions_by_token.get(session.token) is session:
                    self.sessions_by_token.pop(session.token, None)
            await self._post_closed_once(session)
            await runner.cancel()

        try:
            await runner.run()
        finally:
            session.connected = False
            session.worker = None
            session.runner = None

    async def speak(self, call_id: str, text: str) -> str:
        session = self._session(call_id)
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
        if session.worker is not None:
            await session.worker.queue_frames([InterruptionFrame()])
        session.playback_id = None
        await self.post_media(session, "interrupted")

    async def close(self, call_id: str, *, notify: bool = True) -> None:
        session = self.sessions_by_call.pop(call_id, None)
        if session is None:
            return
        self.sessions_by_token.pop(session.token, None)
        if session.worker is not None:
            await session.worker.queue_frames([EndFrame()])
        if notify:
            await self._post_closed_once(session)

    async def _post_closed_once(self, session: PipecatCallSession) -> None:
        if session.closed_notified:
            return
        session.closed_notified = True
        await self.post_media(session, "closed")

    def _session(self, call_id: str) -> PipecatCallSession:
        session = self.sessions_by_call.get(call_id)
        if session is None:
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
            },
        )

    async def post_media(
        self,
        session: PipecatCallSession,
        state: str,
        *,
        playback_id: str | None = None,
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
            },
        )

    async def _post_json(self, url: str, payload: dict[str, Any]) -> None:
        headers = {"x-webhook-token": self.settings.webhook_token} if self.settings.webhook_token else {}
        for attempt in range(3):
            try:
                async with httpx.AsyncClient(
                    timeout=self.settings.request_timeout_sec,
                    headers=headers,
                ) as client:
                    response = await client.post(url, json=payload)
                response.raise_for_status()
                return
            except httpx.HTTPError:
                if attempt == 2:
                    raise
                await asyncio.sleep(0.2 * (2**attempt))


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
