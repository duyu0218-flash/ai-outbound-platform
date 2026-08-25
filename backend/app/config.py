from functools import lru_cache
from typing import List

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    app_name: str = "AI-Outbound-Platform"
    env: str = "dev"

    secret_key: str = "change-me"
    api_key: str = "dev-api-key"

    database_url: str = "sqlite:///./ai_outbound.db"
    redis_url: str = "redis://localhost:6379/0"

    minio_endpoint: str = "localhost:9000"
    minio_access_key: str = "minio_admin"
    minio_secret_key: str = "miniopass123"
    minio_bucket: str = "call-recordings"

    telephony_provider: str = "mock"
    telephony_webhook_base: str = "http://localhost:8000"
    sip_provider_endpoint: str = "http://localhost:8080"
    ai_agent_url: str = "http://localhost:8001"

    sms_provider: str = "mock"
    sms_api_key: str = ""

    call_recording_event_url: str = "/api/v1/webhooks/telephony/recording"

    ai_mode_default: str = "ai_handoff"

    class Config:
        env_prefix = ""
        env_file = ".env"


@lru_cache
def get_settings() -> Settings:
    return Settings()
