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

    secret_key: str = "dev-only-secret-change-me-before-production"
    api_key: str = "dev-api-key"
    ui_api_key: str | None = None
    jwt_secret: str = "dev-only-jwt-secret-change-me-before-production"
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
    database_url_api: str | None = None
    database_url_bootstrap: str | None = None
    database_bootstrap_advisory_lock: bool = True
    database_bootstrap_lock_name: str = "ai-outbound-bootstrap-ddl"
    database_bootstrap_data_lock_name: str = "ai-outbound-bootstrap-data"
    database_migration_lock_name: str = "ai-outbound-schema-migrations"
    database_pool_size: int = 5
    database_max_overflow: int = 5
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
    business_callback_allowed_origins: str = ""
    business_callback_private_origins: str = ""
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
    retention_scan_interval_sec: int = 60
    retention_batch_size: int = 1000
    retention_run_budget_sec: int = 20
    task_lease_sec: int = 30
    task_timeout_sec: int = 120
    task_poll_interval_sec: float = 0.1
    task_worker_role: str = "all"
    ai_worker_health_path: str = "/tmp/ai-worker-health.json"
    ai_db_threads: int = 2
    outbound_require_agent_ready: bool = False
    ai_action_threads: int = 2
    task_ai_concurrency: int = 4
    task_callback_concurrency: int = 4
    task_recording_concurrency: int = 2
    task_queue_lanes: str = ""
    task_queue_lane_aliases: str = "recording_ingest:recording,recording_delete:recording"
    task_inline_execution_enabled: bool = False
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
    contact_import_batch_size: int = 1_000

    # Outbound abuse controls. Production must explicitly constrain the
    # destinations that a compromised tenant credential is allowed to dial.
    outbound_allowed_phone_prefixes: str = ""
    outbound_daily_call_limit: int = 10_000
    outbound_platform_max_concurrent: int = 20
    voice_gateway_nodes_json: str = "[]"
    voice_gateway_nodes_file: str = ""
    voice_gateway_health_ttl_sec: int = 20
    voice_gateway_health_poll_sec: float = 5.0
    terminal_analysis_async: bool = False
    voice_command_secret: str = ""
    outbound_security_approval_token: str = ""
    tenant_api_scopes_json: str = "{}"

    # Derived text and call PII have their own retention clocks in addition to
    # the media-object retention policy.
    final_transcript_retention_days: int = 90
    call_sensitive_data_retention_days: int = 180

    # Production hardening
    request_timeout_ms: int = 15000
    request_timeout_exempt_paths: str = "/api/v1/contacts/import,/api/v1/contacts/export"
    request_id_header: str = "X-Request-ID"
    request_admission_enabled: bool = True
    request_admission_total_inflight: int = 0
    request_admission_max_waiters: int = 0
    request_admission_default_inflight: int = 0
    request_admission_webhook_inflight: int = 0
    request_admission_timeout_sec: float = 0.25
    request_admission_retry_after_sec: int = 1
    request_admission_metrics_inflight: int = 1
    request_admission_stream_inflight: int = 64
    request_admission_static_inflight: int = 32
    agent_snapshot_concurrency: int = 2
    trusted_hosts: str = ""
    trusted_proxy_ips: str = "127.0.0.1,::1"
    rate_limit_enabled: bool = True
    rate_limit_default_rpm: int = 600
    rate_limit_auth_rpm: int = 60
    rate_limit_window_sec: int = 60
    rate_limit_webhook_rpm: int = 12000
    rate_limit_webhook_control_rpm: int = 6000
    rate_limit_unverified_webhook_rpm: int = 600
    rate_limit_memory_max_keys: int = 10000
    metrics_token: str = ""
    metrics_token_file: str = ""

    sms_provider: str = "mock"
    sms_provider_endpoint: str = ""
    sms_api_key: str = ""
    sms_sender_id: str = ""
    sms_callback_url: str = ""
    sms_webhook_token: str = ""
    telephony_webhook_secret: str = ""
    sms_webhook_secret: str = ""
    webhook_signature_max_age_sec: int = 300

    # Schema mutation is a separate release step in production.
    auto_migrate: bool = True

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

    def database_url_for_api(self) -> str:
        return (self.database_url_api or self.database_url).strip() or self.database_url

    def database_url_for_bootstrap(self) -> str:
        return (
            (self.database_url_bootstrap or self.database_url_api or self.database_url).strip()
            or self.database_url
        )

    def resolved_task_queue_lanes(self) -> dict[str, int]:
        lanes: dict[str, int] = {
            "ai_turn": max(1, self.task_ai_concurrency),
            "business_callback": max(1, self.task_callback_concurrency),
            "recording": max(1, self.task_recording_concurrency),
            "call_analysis": 2,
            "after_playback": 4,
            "dial_call": 8,
        }
        raw = self.task_queue_lanes.strip()
        if raw:
            for chunk in raw.split(","):
                if not chunk.strip():
                    continue
                if ":" in chunk:
                    lane, value = chunk.split(":", 1)
                elif "=" in chunk:
                    lane, value = chunk.split("=", 1)
                else:
                    continue
                lane = lane.strip().lower()
                try:
                    limit = int(value.strip())
                except ValueError:
                    continue
                if not lane or limit < 1:
                    continue
                lanes[lane] = limit
        if self.task_worker_role not in {"all", "background", "ai"}:
            raise ValueError("TASK_WORKER_ROLE must be all, background or ai")
        if self.task_worker_role == "background":
            lanes.pop("ai_turn", None)
        if self.task_worker_role == "ai":
            return {"ai_turn": lanes["ai_turn"]}
        return lanes

    def resolved_task_queue_aliases(self) -> dict[str, str]:
        aliases: dict[str, str] = {}
        raw = self.task_queue_lane_aliases.strip()
        if raw:
            for chunk in raw.split(","):
                if not chunk.strip():
                    continue
                if ":" in chunk:
                    source, target = chunk.split(":", 1)
                elif "=" in chunk:
                    source, target = chunk.split("=", 1)
                else:
                    continue
                source = source.strip().lower()
                target = target.strip().lower()
                if source and target:
                    aliases[source] = target
        if self.task_worker_role != "all" and ("ai_turn" in aliases or "ai_turn" in aliases.values()):
            raise ValueError("dedicated AI workers cannot alias the ai_turn lane")
        return aliases


def setup_logging(level: str) -> None:
    logging.basicConfig(
        level=getattr(logging, level.upper(), logging.INFO),
        format="[%(asctime)s] [%(levelname)s] %(name)s: %(message)s",
    )


@lru_cache
def get_settings() -> Settings:
    return Settings()
