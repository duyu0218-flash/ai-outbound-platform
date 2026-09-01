from functools import lru_cache
import logging
from pathlib import Path
from typing import List

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    app_name: str = "AI-Outbound-Platform"
    env: str = "dev"
    api_version: str = "v1"
    debug: bool = False
    log_level: str = "INFO"

    secret_key: str = "change-me"
    api_key: str = "dev-api-key"
    ui_api_key: str | None = None
    jwt_secret: str = "jwt-change-me"
    jwt_algorithm: str = "HS256"
    jwt_ttl_seconds: int = 12 * 60 * 60
    auth_max_failed_attempts: int = 5
    auth_lockout_seconds: int = 15 * 60
    demo_users_enabled: bool = True
    demo_admin_username: str = "admin"
    demo_admin_password: str = "12345678"
    demo_agent_username: str = "1001@test"
    demo_agent_password: str = "12345678"
    demo_tenant_id: int = 1

    default_tenant_id: int = 1
    cors_allow_origins: str = "*"

    database_url: str = "sqlite:///./ai_outbound.db"
    database_pool_size: int = 10
    database_max_overflow: int = 20
    database_pool_timeout_sec: int = 30
    database_pool_recycle_sec: int = 1800
    redis_url: str = "redis://localhost:6379/0"

    telephony_provider: str = "mock"
    telephony_provider_endpoint: str = ""
    telephony_webhook_base: str = "http://localhost:8000"
    sip_provider_endpoint: str = "http://localhost:8080"
    telephony_webhook_token: str = ""
    telephony_service_token: str = ""
    ai_agent_url: str = "http://localhost:8001"
    ai_agent_service_token: str = ""
    llm_provider: str = "rule"
    openai_model: str = "gpt-4o-mini"
    ai_callback_timeout_sec: int = 10
    tts_playback_timeout_sec: int = 30
    telephony_timeout_sec: int = 8
    telephony_retry_times: int = 2
    telephony_retry_backoff_sec: float = 1.0
    scheduler_enabled: bool = True
    scheduler_poll_interval_sec: float = 1.0
    scheduler_batch_size: int = 200
    scheduler_lock_ttl_sec: int = 15
    agent_presence_timeout_sec: int = 90
    webrtc_enabled: bool = False
    webrtc_wss_url: str = ""
    webrtc_sip_domain: str = ""
    webrtc_extension_template: str = "agent_{agent_id}"
    webrtc_sip_credential_ttl_sec: int = 900
    webrtc_media_status_ttl_sec: int = 90
    webrtc_event_stream_interval_sec: float = 1.5
    turn_urls: str = ""
    turn_shared_secret: str = ""
    turn_credential_ttl_sec: int = 3600
    freeswitch_directory_token: str = ""
    ai_turn_lock_ttl_sec: int = 45
    ai_turn_lock_wait_sec: float = 15.0
    recording_retention_days: int = 90
    partial_transcript_retention_hours: int = 24
    retention_scan_interval_sec: int = 3600
    recording_delete_endpoint: str = ""
    recording_delete_service_token: str = ""
    recording_delete_timeout_sec: int = 15
    recording_ingest_endpoint: str = ""
    recording_ingest_service_token: str = ""
    recording_ingest_timeout_sec: int = 60
    contact_import_max_bytes: int = 20 * 1024 * 1024
    contact_import_max_rows: int = 200_000
    contact_import_max_errors: int = 1_000
    contact_export_batch_size: int = 1_000

    # Production hardening
    request_timeout_ms: int = 15000
    request_id_header: str = "X-Request-ID"
    trusted_hosts: str = ""
    rate_limit_enabled: bool = True
    rate_limit_default_rpm: int = 600
    rate_limit_auth_rpm: int = 60
    rate_limit_window_sec: int = 60
    metrics_token: str = ""
    metrics_token_file: str = ""

    sms_provider: str = "mock"
    sms_provider_endpoint: str = ""
    sms_api_key: str = ""
    sms_sender_id: str = ""
    sms_callback_url: str = ""
    sms_webhook_token: str = ""

    call_recording_event_url: str = "/api/v1/webhooks/telephony/recording"
    transcript_event_url: str = "/api/v1/webhooks/telephony/transcript"
    speech_event_url: str = "/api/v1/webhooks/telephony/speech"
    media_event_url: str = "/api/v1/webhooks/telephony/media"
    max_concurrent_calls: int = 20
    default_call_timeout_sec: int = 120
    no_answer_codes: List[str] = ["NOANSWER", "NO_ANSWER"]
    busy_codes: List[str] = ["BUSY"]
    voicemail_codes: List[str] = ["VOICEMAIL"]

    # Optional per-tenant server API keys. JSON object format:
    # {"1":"tenant-1-key","2":"tenant-2-key"}. The legacy API_KEY is
    # always restricted to DEFAULT_TENANT_ID.
    tenant_api_keys_json: str = ""

    def resolved_metrics_token(self) -> str:
        """Return the metrics token from a mounted secret file or the legacy env value."""

        if self.metrics_token_file.strip():
            try:
                return Path(self.metrics_token_file.strip()).read_text(encoding="utf-8").strip()
            except OSError as exc:
                raise RuntimeError(f"unable to read METRICS_TOKEN_FILE: {exc}") from exc
        return self.metrics_token.strip()


def setup_logging(level: str) -> None:
    logging.basicConfig(
        level=getattr(logging, level.upper(), logging.INFO),
        format="[%(asctime)s] [%(levelname)s] %(name)s: %(message)s",
    )


@lru_cache
def get_settings() -> Settings:
    return Settings()
