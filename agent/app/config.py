from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    app_name: str = "AI-Outbound-Agent"
    llm_provider: str = "rule"
    openai_api_key: str = ""
    openai_base_url: str = ""
    openai_model: str = "gpt-4o-mini"
    default_handoff_keywords: str = "人工,转人工,坐席,客服"
    default_hangup_sms: str = "感谢来电，如有需要请回复我们"
    max_output_tokens: int = 800


settings = Settings()
