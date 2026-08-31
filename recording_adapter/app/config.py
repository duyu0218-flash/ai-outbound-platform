from __future__ import annotations

from functools import lru_cache
from pathlib import Path
import re
from urllib.parse import urlparse

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    env: str = "development"
    service_token: str = ""
    service_token_file: str = ""
    metrics_token: str = ""
    metrics_token_file: str = ""

    s3_endpoint_url: str = "http://seaweedfs:8333"
    s3_region: str = "us-east-1"
    s3_access_key_id: str = ""
    s3_secret_access_key: str = ""
    s3_secret_access_key_file: str = ""
    s3_bucket: str = "ai-outbound-recordings"
    s3_key_prefix: str = "recordings"
    s3_auto_create_bucket: bool = True

    recording_source_allowed_hosts: str = ""
    recording_source_require_https: bool = False
    recording_download_timeout_sec: int = 60
    recording_max_bytes: int = 512 * 1024 * 1024

    @staticmethod
    def _secret(value: str, file_path: str, label: str) -> str:
        if file_path.strip():
            try:
                return Path(file_path.strip()).read_text(encoding="utf-8").strip()
            except OSError as exc:
                raise RuntimeError(f"unable to read {label}: {exc}") from exc
        return value.strip()

    def resolved_service_token(self) -> str:
        return self._secret(self.service_token, self.service_token_file, "SERVICE_TOKEN_FILE")

    def resolved_metrics_token(self) -> str:
        return self._secret(self.metrics_token, self.metrics_token_file, "METRICS_TOKEN_FILE")

    def resolved_s3_secret_access_key(self) -> str:
        return self._secret(
            self.s3_secret_access_key,
            self.s3_secret_access_key_file,
            "S3_SECRET_ACCESS_KEY_FILE",
        )

    def allowed_source_hosts(self) -> tuple[str, ...]:
        return tuple(
            host.strip().lower()
            for host in self.recording_source_allowed_hosts.split(",")
            if host.strip()
        )

    def validate_runtime(self) -> None:
        endpoint = urlparse(self.s3_endpoint_url)
        if endpoint.scheme not in {"http", "https"} or not endpoint.hostname:
            raise RuntimeError("S3_ENDPOINT_URL must be an http(s) URL")
        if not self.s3_access_key_id.strip() or not self.resolved_s3_secret_access_key():
            raise RuntimeError("S3 access credentials are required")
        if not self.s3_bucket.strip() or "/" in self.s3_bucket:
            raise RuntimeError("S3_BUCKET must be a bucket name")
        prefix = self.s3_key_prefix.strip("/")
        if (
            not prefix
            or len(prefix) > 256
            or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._/-]*", prefix)
            or any(segment in {"", ".", ".."} for segment in prefix.split("/"))
        ):
            raise RuntimeError("S3_KEY_PREFIX must contain only safe, non-relative path segments")
        if self.recording_max_bytes < 1024:
            raise RuntimeError("RECORDING_MAX_BYTES must be at least 1024")
        if not self.allowed_source_hosts():
            raise RuntimeError("RECORDING_SOURCE_ALLOWED_HOSTS must not be empty")
        if self.env.lower() in {"prod", "production"}:
            if len(self.resolved_service_token()) < 24:
                raise RuntimeError("SERVICE_TOKEN must contain at least 24 characters in production")
            if len(self.resolved_metrics_token()) < 24:
                raise RuntimeError("METRICS_TOKEN must contain at least 24 characters in production")


@lru_cache
def get_settings() -> Settings:
    return Settings()
