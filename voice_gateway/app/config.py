from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")
    env: str = "development"
    voice_gateway_driver: str = "mock"
    pbx_base_url: str = ""
    pbx_bearer_token: str = ""
    request_timeout_sec: float = 10.0
    rtp_port_start: int = 20000
    rtp_port_end: int = 30000
    webhook_token: str = ""
    service_token: str = ""
    freeswitch_esl_host: str = "freeswitch"
    freeswitch_esl_port: int = 8021
    freeswitch_esl_password: str = "ClueCon"
    freeswitch_esl_timeout_sec: float = 5.0
    freeswitch_esl_reconnect_sec: float = 2.0
    freeswitch_gateway: str = ""
    freeswitch_caller_id: str = ""
    freeswitch_originate_timeout_sec: int = 45
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
    freeswitch_recording_dir: str = "/recordings"
    freeswitch_recording_public_base_url: str = ""
    freeswitch_recording_stereo: bool = True

    def validate_runtime(self) -> None:
        driver = self.voice_gateway_driver.strip().lower()
        if driver not in {"mock", "pbx_http", "freeswitch_esl"}:
            raise RuntimeError("VOICE_GATEWAY_DRIVER must be mock, pbx_http or freeswitch_esl")
        if self.env.lower() in {"prod", "production"} and driver == "mock":
            raise RuntimeError("production voice gateway cannot use mock driver")
        if self.env.lower() in {"prod", "production"} and not self.service_token.strip():
            raise RuntimeError("SERVICE_TOKEN is required in production")
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
            if not self.freeswitch_tts_http_endpoint.strip() and not (
                self.freeswitch_tts_engine.strip() and self.freeswitch_tts_voice.strip()
            ):
                raise RuntimeError(
                    "freeswitch_esl driver requires FREESWITCH_TTS_HTTP_ENDPOINT or native TTS engine and voice"
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
            except (KeyError, ValueError) as exc:
                raise RuntimeError(f"invalid FreeSWITCH command template: {exc}") from exc
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
