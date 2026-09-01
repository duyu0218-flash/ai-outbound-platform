import re
from functools import lru_cache
from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")
    env: str = "development"
    voice_gateway_driver: str = "mock"
    voice_ai_pipeline: str = "legacy"
    pbx_base_url: str = ""
    pbx_bearer_token: str = ""
    request_timeout_sec: float = 10.0
    rtp_port_start: int = 20000
    rtp_port_end: int = 30000
    webhook_token: str = ""
    service_token: str = ""
    metrics_token: str = ""
    metrics_token_file: str = ""
    freeswitch_esl_host: str = "freeswitch"
    freeswitch_esl_port: int = 8021
    freeswitch_esl_password: str = "ClueCon"
    freeswitch_esl_timeout_sec: float = 5.0
    freeswitch_esl_reconnect_sec: float = 2.0
    freeswitch_gateway: str = ""
    freeswitch_caller_id: str = ""
    freeswitch_originate_timeout_sec: int = 45
    freeswitch_playback_timeout_sec: float = 30.0
    freeswitch_dialplan_context: str = "default"
    freeswitch_agent_extension_template: str = "agent_{agent_id}"
    freeswitch_default_handoff_extension: str = "handoff_default"
    freeswitch_tts_engine: str = ""
    freeswitch_tts_voice: str = ""
    freeswitch_tts_uri_template: str = "speak:{engine}|{voice}|{text}"
    freeswitch_tts_http_endpoint: str = ""
    freeswitch_tts_http_token: str = ""
    freeswitch_media_start_command_template: str = ""
    freeswitch_media_stop_command_template: str = ""
    freeswitch_pipecat_start_command_template: str = ""
    freeswitch_recording_dir: str = "/recordings"
    freeswitch_recording_public_base_url: str = ""
    freeswitch_recording_stereo: bool = True
    pipecat_version: str = ""
    pipecat_media_ws_base: str = ""
    pipecat_sample_rate: int = 8000
    pipecat_channels: int = 1
    pipecat_session_timeout_sec: int = 300
    pipecat_stt_provider: str = "openai-realtime"
    pipecat_tts_provider: str = "openai"
    pipecat_openai_api_key: str = ""
    pipecat_openai_realtime_base_url: str = "wss://api.openai.com/v1/realtime"
    pipecat_openai_base_url: str = ""
    pipecat_tts_model: str = "gpt-4o-mini-tts"
    pipecat_tts_voice: str = "alloy"
    pipecat_fallback_to_legacy: bool = False

    def resolved_metrics_token(self) -> str:
        if self.metrics_token_file.strip():
            try:
                return Path(self.metrics_token_file.strip()).read_text(encoding="utf-8").strip()
            except OSError as exc:
                raise RuntimeError(f"unable to read METRICS_TOKEN_FILE: {exc}") from exc
        return self.metrics_token.strip()

    def validate_runtime(self) -> None:
        driver = self.voice_gateway_driver.strip().lower()
        pipeline = self.voice_ai_pipeline.strip().lower()
        if driver not in {"mock", "pbx_http", "freeswitch_esl"}:
            raise RuntimeError("VOICE_GATEWAY_DRIVER must be mock, pbx_http or freeswitch_esl")
        if pipeline not in {"legacy", "pipecat", "hybrid"}:
            raise RuntimeError("VOICE_AI_PIPELINE must be legacy, pipecat or hybrid")
        pipecat_enabled = pipeline in {"pipecat", "hybrid"}
        if pipecat_enabled and driver != "freeswitch_esl":
            raise RuntimeError("VOICE_AI_PIPELINE=pipecat or hybrid requires VOICE_GATEWAY_DRIVER=freeswitch_esl")
        if self.env.lower() in {"prod", "production"} and driver == "mock":
            raise RuntimeError("production voice gateway cannot use mock driver")
        if self.env.lower() in {"prod", "production"} and not self.service_token.strip():
            raise RuntimeError("SERVICE_TOKEN is required in production")
        try:
            metrics_token = self.resolved_metrics_token()
        except RuntimeError:
            metrics_token = ""
        if self.env.lower() in {"prod", "production"} and len(metrics_token) < 24:
            raise RuntimeError("METRICS_TOKEN with at least 24 characters is required in production")
        if driver == "pbx_http" and not self.pbx_base_url.strip():
            raise RuntimeError("PBX_BASE_URL is required for pbx_http driver")
        if driver == "freeswitch_esl":
            if not self.freeswitch_esl_host.strip():
                raise RuntimeError("FREESWITCH_ESL_HOST is required for freeswitch_esl driver")
            if not (1 <= self.freeswitch_esl_port <= 65535):
                raise RuntimeError("invalid FREESWITCH_ESL_PORT")
            if not self.freeswitch_esl_password.strip():
                raise RuntimeError("FREESWITCH_ESL_PASSWORD is required for freeswitch_esl driver")
            if not self.freeswitch_gateway.strip():
                raise RuntimeError("FREESWITCH_GATEWAY is required for freeswitch_esl driver")
            # The compliance notice is played by FreeSWITCH before recording
            # and before either media pipeline starts, including pure Pipecat.
            if not self.freeswitch_tts_http_endpoint.strip() and not (
                self.freeswitch_tts_engine.strip() and self.freeswitch_tts_voice.strip()
            ):
                raise RuntimeError(
                    "freeswitch_esl driver requires FREESWITCH_TTS_HTTP_ENDPOINT or native TTS engine and voice "
                    "for the recording notice and legacy speech"
                )
            try:
                self.freeswitch_agent_extension_template.format(agent_id="1", tenant_id=1)
                self.freeswitch_tts_uri_template.format(
                    engine="engine", voice="voice", text="text", language="zh-CN"
                )
                if self.freeswitch_media_start_command_template.strip():
                    self.freeswitch_media_start_command_template.format(
                        uuid="uuid",
                        call_id="call-id",
                        speech_webhook_url="http://control/speech",
                        media_webhook_url="http://control/media",
                        asr_provider="asr",
                        language="zh-CN",
                    )
                if self.freeswitch_media_stop_command_template.strip():
                    self.freeswitch_media_stop_command_template.format(
                        uuid="uuid",
                        call_id="call-id",
                    )
                if self.freeswitch_pipecat_start_command_template.strip():
                    self.freeswitch_pipecat_start_command_template.format(
                        uuid="uuid",
                        call_id="call-id",
                        session_id="session-id",
                        media_ws_url="ws://voice-gateway/pipecat/session",
                        sample_rate=8000,
                        channels=1,
                        codec="pcm_s16le",
                    )
            except (KeyError, ValueError) as exc:
                raise RuntimeError(f"invalid FreeSWITCH command template: {exc}") from exc
        if pipecat_enabled:
            if not re.fullmatch(r"\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?", self.pipecat_version):
                raise RuntimeError("PIPECAT_VERSION must be an exact version for the Pipecat pipeline")
            if not self.pipecat_media_ws_base.startswith(("ws://", "wss://")):
                raise RuntimeError("PIPECAT_MEDIA_WS_BASE must use ws:// or wss://")
            if not self.freeswitch_pipecat_start_command_template.strip():
                raise RuntimeError("FREESWITCH_PIPECAT_START_COMMAND_TEMPLATE is required")
            if self.pipecat_stt_provider != "openai-realtime":
                raise RuntimeError("the current Pipecat integration supports PIPECAT_STT_PROVIDER=openai-realtime")
            if self.pipecat_tts_provider != "openai":
                raise RuntimeError("the current Pipecat integration supports PIPECAT_TTS_PROVIDER=openai")
            if not self.pipecat_openai_api_key.strip():
                raise RuntimeError("PIPECAT_OPENAI_API_KEY is required for the Pipecat pipeline")
            if not self.pipecat_openai_realtime_base_url.startswith(("ws://", "wss://")):
                raise RuntimeError("PIPECAT_OPENAI_REALTIME_BASE_URL must use ws:// or wss://")
            if self.pipecat_sample_rate not in {8000, 16000, 24000, 48000}:
                raise RuntimeError("PIPECAT_SAMPLE_RATE must be 8000, 16000, 24000 or 48000")
            if self.pipecat_channels != 1:
                raise RuntimeError("the telephony Pipecat pipeline currently requires mono audio")
            if self.pipecat_session_timeout_sec < 30:
                raise RuntimeError("PIPECAT_SESSION_TIMEOUT_SEC must be at least 30")
            if self.pipecat_fallback_to_legacy and not self.freeswitch_media_start_command_template.strip():
                raise RuntimeError(
                    "PIPECAT_FALLBACK_TO_LEGACY=true requires FREESWITCH_MEDIA_START_COMMAND_TEMPLATE"
                )
        if self.env.lower() in {"prod", "production"} and driver == "freeswitch_esl":
            if self.freeswitch_esl_password == "ClueCon":
                raise RuntimeError("production FreeSWITCH cannot use the default ESL password")
            if not self.webhook_token.strip():
                raise RuntimeError("WEBHOOK_TOKEN is required for production FreeSWITCH callbacks")
        if not (1024 <= self.rtp_port_start <= self.rtp_port_end <= 65535):
            raise RuntimeError("invalid RTP port range")


@lru_cache
def get_settings() -> Settings:
    return Settings()
