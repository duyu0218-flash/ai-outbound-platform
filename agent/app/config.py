from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    app_name: str = "AI-Outbound-Agent"
    env: str = "dev"
    service_token: str = ""
    llm_provider: str = "rule"
    openai_api_key: str = ""
    openai_base_url: str = ""
    openai_model: str = "gpt-4o-mini"
    llm_allowed_hosts: str = ""
    llm_send_pii: bool = False
    llm_require_https: bool = True
    default_handoff_keywords: str = "人工,转人工,坐席,客服"
    default_handoff_keywords_en: str = "human,agent,representative,operator,customer service"
    default_hangup_sms: str = "感谢来电，如有需要请回复我们"
    default_hangup_sms_en: str = "Thank you for your time. Reply to this message if you need a human agent."
    max_output_tokens: int = 800
    llm_max_connections: int = 256
    llm_max_keepalive_connections: int = 128
    openai_timeout_sec: float = 8.0
    conversation_history_turns: int = 12
    conversation_history_max_chars: int = 12000
    llm_quota_db_path: str = ''
    llm_quota_scope: str = 'primary-account'
    llm_quota_rpm: int = 10000
    llm_quota_tpm: int = 13000000
    llm_quota_rps: int = 500

    def validate_runtime(self) -> None:
        if not 1 <= self.max_output_tokens <= 4096:
            raise RuntimeError('MAX_OUTPUT_TOKENS must be between 1 and 4096')
        if self.llm_quota_db_path:
            if self.llm_quota_db_path == ':memory:' or not self.llm_quota_scope.strip():
                raise RuntimeError('account quota requires a shared persistent local file and scope')
            if min(self.llm_quota_rpm, self.llm_quota_tpm, self.llm_quota_rps) < 1:
                raise RuntimeError('account quota limits must be positive')
        if not 1 <= self.llm_max_keepalive_connections <= self.llm_max_connections <= 1024:
            raise RuntimeError("invalid LLM connection pool limits")
        if self.env.lower() in {"prod", "production"} and not self.service_token.strip():
            raise RuntimeError("SERVICE_TOKEN is required in production")
        if self.env.lower() in {"prod", "production"} and self.llm_provider == "openai-compatible":
            if not self.openai_api_key.strip() or not self.openai_base_url.strip():
                raise RuntimeError('external LLM credentials and endpoint are required in production')
            if not self.llm_allowed_hosts.strip():
                raise RuntimeError("LLM_ALLOWED_HOSTS is required for an external LLM in production")


settings = Settings()
