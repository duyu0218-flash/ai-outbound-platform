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

    def validate_runtime(self) -> None:
        driver = self.voice_gateway_driver.strip().lower()
        if driver not in {"mock", "pbx_http"}:
            raise RuntimeError("VOICE_GATEWAY_DRIVER must be mock or pbx_http")
        if self.env.lower() in {"prod", "production"} and driver == "mock":
            raise RuntimeError("production voice gateway cannot use mock driver")
        if driver == "pbx_http" and not self.pbx_base_url.strip():
            raise RuntimeError("PBX_BASE_URL is required for pbx_http driver")
        if not (1024 <= self.rtp_port_start <= self.rtp_port_end <= 65535):
            raise RuntimeError("invalid RTP port range")


@lru_cache
def get_settings() -> Settings:
    return Settings()
