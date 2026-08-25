from functools import lru_cache
import logging
from typing import List

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    app_name: str = "AI-Outbound-Platform"
    env: str = "prod"
    api_version: str = "v1"
    debug: bool = False
    log_level: str = "INFO"

    secret_key: str = "change-me"
    api_key: str = "dev-api-key"
    ui_api_key: str | None = None
    jwt_secret: str = "jwt-change-me"
    jwt_algorithm: str = "HS256"
    jwt_ttl_seconds: int = 12 * 60 * 60
    demo_admin_username: str = "admin"
    demo_admin_password: str = "12345678"
    demo_agent_username: str = "1001@test"
    demo_agent_password: str = "12345678"
    demo_tenant_id: int = 1

    default_tenant_id: int = 1
    cors_allow_origins: str = "*"

    database_url: str = "sqlite:///./ai_outbound.db"
    redis_url: str = "redis://localhost:6379/0"

    minio_endpoint: str = ""
    minio_access_key: str = ""
    minio_secret_key: str = ""
    minio_bucket: str = "call-recordings"
    minio_secure: bool = False
    minio_region: str = ""

    telephony_provider: str = "mock"
    telephony_provider_endpoint: str = ""
    telephony_webhook_base: str = "http://localhost:8000"
    sip_provider_endpoint: str = "http://localhost:8080"
    telephony_webhook_token: str = ""
    ai_agent_url: str = "http://localhost:8001"
    ai_callback_timeout_sec: int = 10
    telephony_timeout_sec: int = 8
    telephony_retry_times: int = 2
    telephony_retry_backoff_sec: float = 1.0

    # Production hardening
    request_timeout_ms: int = 15000
    request_id_header: str = "X-Request-ID"
    trusted_hosts: str = ""
    rate_limit_enabled: bool = True
    rate_limit_default_rpm: int = 600
    rate_limit_auth_rpm: int = 60
    rate_limit_window_sec: int = 60

    sms_provider: str = "mock"
    sms_provider_endpoint: str = ""
    sms_api_key: str = ""
    sms_sender_id: str = ""
    sms_callback_url: str = ""

    call_recording_event_url: str = "/api/v1/webhooks/telephony/recording"
    transcript_event_url: str = "/api/v1/webhooks/telephony/transcript"
    max_call_retry: int = 2
    max_concurrent_calls: int = 20
    default_call_timeout_sec: int = 120
    no_answer_codes: List[str] = ["NOANSWER", "NO_ANSWER"]
    busy_codes: List[str] = ["BUSY"]
    voicemail_codes: List[str] = ["VOICEMAIL"]

    ai_mode_default: str = "ai_handoff"
    ai_handoff_keywords: List[str] = [
        "转人工",
        "人工",
        "客服",
        "坐席",
        "投诉",
    ]
    ai_hangup_sms_text: str = "感谢您来电，我们未能继续为您服务，可回复“1”联系人工。"


def setup_logging(level: str) -> None:
    logging.basicConfig(
        level=getattr(logging, level.upper(), logging.INFO),
        format="[%(asctime)s] [%(levelname)s] %(name)s: %(message)s",
    )


@lru_cache
def get_settings() -> Settings:
    return Settings()
