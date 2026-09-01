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
    openai_timeout_sec: float = 8.0
    conversation_history_turns: int = 12
    conversation_history_max_chars: int = 12000

    def validate_runtime(self) -> None:
        if self.env.lower() in {"prod", "production"} and not self.service_token.strip():
            raise RuntimeError("SERVICE_TOKEN is required in production")
        if self.env.lower() in {"prod", "production"} and self.llm_provider == "openai-compatible":
            if not self.llm_allowed_hosts.strip():
                raise RuntimeError("LLM_ALLOWED_HOSTS is required for an external LLM in production")


settings = Settings()
