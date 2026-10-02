from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    database_url: str
    jwt_secret: str
    jwt_algorithm: str = "HS256"
    access_token_expire_minutes: int = 60
    refresh_token_expire_days: int = 30
    google_client_id: str | None = None
    public_api_url: str | None = None

    # Assistente financeiro com IA (somente backend)
    openai_api_key: str | None = None
    openai_model: str = "gpt-5.6-luna"

    openfinance_provider: str = "mock"
    redis_url: str = "redis://redis:6379/0"

    # E-mail / SMTP
    smtp_host: str | None = None
    smtp_port: int = 587
    smtp_username: str | None = None
    smtp_password: str | None = None
    smtp_from: str | None = None
    smtp_use_tls: bool = True

    # Belvo / Open Finance Brasil
    belvo_secret_id: str | None = None
    belvo_secret_password: str | None = None
    belvo_base_url: str = "https://sandbox.belvo.com"
    belvo_widget_url: str = "https://widget.belvo.io"
    # Opcional: token Bearer configurado também no dashboard da Belvo para proteger o webhook.
    belvo_webhook_token: str | None = None

    model_config = SettingsConfigDict(env_file=".env", extra="ignore")


settings = Settings()
