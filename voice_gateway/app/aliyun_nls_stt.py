from __future__ import annotations

import asyncio
import json
from collections.abc import AsyncGenerator, Callable
from dataclasses import dataclass
from time import perf_counter
from typing import Any
from urllib.parse import parse_qsl, urlencode, urlsplit, urlunsplit
from uuid import uuid4

from loguru import logger
from pipecat.frames.frames import (
    CancelFrame,
    EndFrame,
    Frame,
    InterimTranscriptionFrame,
    TranscriptionFrame,
    UserStartedSpeakingFrame,
    UserStoppedSpeakingFrame,
)
from pipecat.processors.frame_processor import FrameProcessorSetup
from pipecat.services.stt_service import WebsocketSTTService
from pipecat.utils.time import time_now_iso8601


ALIYUN_NLS_SUCCESS_STATUS = 20_000_000


@dataclass(frozen=True, slots=True)
class AliyunTranscript:
    text: str
    is_final: bool
    event_id: str
    sentence_index: int | None = None
    confidence: float | None = None
    start_ms: int | None = None
    end_ms: int | None = None
    raw_event: dict[str, Any] | None = None

    def frame_result(self, *, latency_ms: int | None = None) -> dict[str, Any]:
        return {
            "provider": "aliyun-nls",
            "provider_event_id": self.event_id,
            "sentence_index": self.sentence_index,
            "confidence": self.confidence,
            "start_ms": self.start_ms,
            "end_ms": self.end_ms,
            "latency_ms": latency_ms,
            "raw_event": self.raw_event,
        }


def aliyun_nls_url(gateway_url: str, token: str) -> str:
    """Add the short-lived NLS token without duplicating query parameters."""
    parsed = urlsplit(gateway_url.strip())
    query = [(key, value) for key, value in parse_qsl(parsed.query) if key.lower() != "token"]
    query.append(("token", token))
    return urlunsplit((parsed.scheme, parsed.netloc, parsed.path, urlencode(query), parsed.fragment))


def parse_aliyun_transcript(event: dict[str, Any]) -> AliyunTranscript | None:
    header = event.get("header") if isinstance(event.get("header"), dict) else {}
    payload = event.get("payload") if isinstance(event.get("payload"), dict) else {}
    name = str(header.get("name") or "")
    if name not in {"TranscriptionResultChanged", "SentenceEnd"}:
        return None
    text = str(payload.get("result") or "").strip()
    if not text:
        return None

    def optional_int(value: object) -> int | None:
        if isinstance(value, bool):
            return None
        try:
            return int(value) if value is not None else None
        except (TypeError, ValueError):
            return None

    confidence_value = payload.get("confidence")
    try:
        confidence = float(confidence_value) if confidence_value is not None else None
    except (TypeError, ValueError):
        confidence = None
    if confidence is not None and not 0 <= confidence <= 1:
        confidence = None

    return AliyunTranscript(
        text=text,
        is_final=name == "SentenceEnd",
        event_id=str(header.get("message_id") or uuid4().hex),
        sentence_index=optional_int(payload.get("index")),
        confidence=confidence,
        start_ms=optional_int(payload.get("begin_time")),
        end_ms=optional_int(payload.get("time")),
        raw_event=event,
    )


class AliyunNLSSTTService(WebsocketSTTService):
    """Alibaba Cloud NLS realtime SpeechTranscriber for Pipecat.

    Input is raw signed PCM16 mono. Alibaba Cloud performs server-side sentence
    endpointing: ``TranscriptionResultChanged`` becomes an interim frame and
    ``SentenceEnd`` becomes the only final frame.
    """

    def __init__(
        self,
        *,
        appkey: str,
        token_getter: Callable[[], str],
        gateway_url: str,
        sample_rate: int,
        vocabulary_id: str = "",
        customization_id: str = "",
        max_sentence_silence_ms: int = 800,
        enable_punctuation_prediction: bool = True,
        enable_inverse_text_normalization: bool = True,
        enable_words: bool = True,
        enable_semantic_sentence_detection: bool = False,
        enable_ignore_sentence_timeout: bool = True,
        disfluency: bool = False,
        connect_timeout_sec: float = 8.0,
        stop_timeout_sec: float = 3.0,
        **kwargs: Any,
    ):
        super().__init__(
            sample_rate=sample_rate,
            reconnect_on_error=True,
            audio_passthrough=True,
            **kwargs,
        )
        self._appkey = appkey
        self._input_sample_rate = sample_rate
        self._token_getter = token_getter
        self._gateway_url = gateway_url
        self._vocabulary_id = vocabulary_id
        self._customization_id = customization_id
        self._max_sentence_silence_ms = max_sentence_silence_ms
        self._enable_punctuation_prediction = enable_punctuation_prediction
        self._enable_inverse_text_normalization = enable_inverse_text_normalization
        self._enable_words = enable_words
        self._enable_semantic_sentence_detection = enable_semantic_sentence_detection
        self._enable_ignore_sentence_timeout = enable_ignore_sentence_timeout
        self._disfluency = disfluency
        self._connect_timeout_sec = connect_timeout_sec
        self._stop_timeout_sec = stop_timeout_sec
        self._task_id = ""
        self._receive_task: asyncio.Task | None = None
        self._started = asyncio.Event()
        self._completed = asyncio.Event()
        self._handshake_error: str | None = None
        self._task_started_at: float | None = None

    def can_generate_metrics(self) -> bool:
        return True

    async def setup(self, setup: FrameProcessorSetup):
        await super().setup(setup)
        await self._connect()

    async def run_stt(self, audio: bytes) -> AsyncGenerator[Frame | None, None]:
        await self._wait_until_started()
        if self._websocket is None:
            raise ConnectionError("Alibaba Cloud NLS websocket is unavailable")
        try:
            await self._websocket.send(audio)
        except Exception as exc:
            logger.warning("Alibaba Cloud NLS audio send failed; reconnecting: {}", exc)
            await self._request_reconnect()
            await self._wait_until_started()
            if self._websocket is None:
                raise ConnectionError("Alibaba Cloud NLS reconnect failed") from exc
            await self._websocket.send(audio)
        yield None

    async def stop(self, frame: EndFrame):
        await self._send_stop()
        try:
            await asyncio.wait_for(self._completed.wait(), timeout=self._stop_timeout_sec)
        except TimeoutError:
            logger.warning("Alibaba Cloud NLS did not confirm StopTranscription before timeout")
        await super().stop(frame)

    async def cancel(self, frame: CancelFrame):
        await super().cancel(frame)

    async def _connect(self):
        await super()._connect()
        try:
            await self._connect_websocket()
            if self._websocket is not None and self._receive_task is None:
                self._receive_task = self.create_task(
                    self._receive_task_handler(self._report_error),
                    name="aliyun-nls-receive",
                )
            await self._wait_until_started()
        except Exception:
            await self._disconnect()
            raise

    async def _disconnect(self):
        await super()._disconnect()
        if self._receive_task:
            await self.cancel_task(self._receive_task, timeout=1.0)
            self._receive_task = None
        await self._disconnect_websocket()

    async def _connect_websocket(self):
        token = self._token_getter().strip()
        if not token:
            raise RuntimeError("Alibaba Cloud NLS token is empty or expired")
        self._started.clear()
        self._completed.clear()
        self._handshake_error = None
        self._task_id = uuid4().hex
        self._websocket = await self._websocket_connect(aliyun_nls_url(self._gateway_url, token))
        await self._websocket.send(json.dumps(self.start_command(), ensure_ascii=False))
        self._task_started_at = perf_counter()

    async def _disconnect_websocket(self):
        try:
            if self._websocket is not None:
                await self._websocket.close()
        finally:
            self._websocket = None
            self._started.clear()
            self._task_started_at = None
            await self._call_event_handler("on_disconnected")

    async def _wait_until_started(self):
        await asyncio.wait_for(self._started.wait(), timeout=self._connect_timeout_sec)
        if self._handshake_error:
            raise ConnectionError(self._handshake_error)

    def start_command(self) -> dict[str, Any]:
        payload: dict[str, Any] = {
            "format": "pcm",
            "sample_rate": self._input_sample_rate,
            "enable_intermediate_result": True,
            "enable_punctuation_prediction": self._enable_punctuation_prediction,
            "enable_inverse_text_normalization": self._enable_inverse_text_normalization,
            "max_sentence_silence": self._max_sentence_silence_ms,
            "enable_words": self._enable_words,
            "enable_ignore_sentence_timeout": self._enable_ignore_sentence_timeout,
            "enable_semantic_sentence_detection": self._enable_semantic_sentence_detection,
            "disfluency": self._disfluency,
        }
        if self._vocabulary_id:
            payload["vocabulary_id"] = self._vocabulary_id
        if self._customization_id:
            payload["customization_id"] = self._customization_id
        return {
            "header": self._command_header("StartTranscription"),
            "payload": payload,
        }

    def _command_header(self, name: str) -> dict[str, Any]:
        return {
            "appkey": self._appkey,
            "message_id": uuid4().hex,
            "task_id": self._task_id,
            "namespace": "SpeechTranscriber",
            "name": name,
        }

    async def _send_stop(self):
        if self._websocket is None or not self._started.is_set():
            return
        await self._websocket.send(
            json.dumps({"header": self._command_header("StopTranscription")}, ensure_ascii=False)
        )

    async def _receive_messages(self):
        assert self._websocket is not None
        async for message in self._websocket:
            if isinstance(message, bytes):
                logger.warning("Ignoring unexpected binary Alibaba Cloud NLS response")
                continue
            try:
                event = json.loads(message)
            except json.JSONDecodeError:
                logger.warning("Ignoring malformed Alibaba Cloud NLS response")
                continue
            if isinstance(event, dict):
                await self._handle_event(event)

    async def _handle_event(self, event: dict[str, Any]):
        header = event.get("header") if isinstance(event.get("header"), dict) else {}
        name = str(header.get("name") or "")
        status = header.get("status")
        status_message = str(header.get("status_message") or "")

        if status not in {None, ALIYUN_NLS_SUCCESS_STATUS}:
            message = f"Alibaba Cloud NLS {name or 'event'} failed: {status} {status_message}".strip()
            if not self._started.is_set():
                self._handshake_error = message
                self._started.set()
            await self._call_event_handler("on_connection_error", message)
            await self.push_error(message, fatal=name == "TaskFailed")
            return

        if name == "TranscriptionStarted":
            self._started.set()
            await self._call_event_handler("on_connected")
            return
        if name == "TranscriptionCompleted":
            self._completed.set()
            return
        if name == "SentenceBegin":
            await self.push_frame(UserStartedSpeakingFrame())
            return
        if name == "TaskFailed":
            message = f"Alibaba Cloud NLS task failed: {status_message or 'unknown error'}"
            self._handshake_error = message
            self._started.set()
            await self._call_event_handler("on_connection_error", message)
            await self.push_error(message, fatal=True)
            return

        transcript = parse_aliyun_transcript(event)
        if transcript is None:
            return
        latency_ms = self._observed_latency_ms(transcript.end_ms)
        if transcript.is_final:
            await self.emit_stt_usage_metrics()
            await self.push_frame(
                TranscriptionFrame(
                    transcript.text,
                    self._user_id,
                    time_now_iso8601(),
                    result=transcript.frame_result(latency_ms=latency_ms),
                    finalized=True,
                )
            )
            await self.push_frame(UserStoppedSpeakingFrame())
        else:
            await self.push_frame(
                InterimTranscriptionFrame(
                    transcript.text,
                    self._user_id,
                    time_now_iso8601(),
                    result=transcript.frame_result(latency_ms=latency_ms),
                )
            )

    def _observed_latency_ms(self, audio_end_ms: int | None) -> int | None:
        """Return gateway-observed lag after Alibaba's audio timeline position."""
        if self._task_started_at is None or audio_end_ms is None:
            return None
        elapsed_ms = int((perf_counter() - self._task_started_at) * 1000)
        return max(0, elapsed_ms - audio_end_ms)
