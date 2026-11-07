"""Application settings, read from environment variables (prefix ``FG_``)."""

from functools import lru_cache

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_prefix="FG_", extra="ignore")

    env: str = "production"
    database_url: str = "postgresql+psycopg://fastgame:fastgame@localhost:5432/fastgame"

    # Master secret. JWT signing, token/fingerprint hashing and the credential
    # vault key are all derived from it (see app.core.security).
    secret_key: str = Field(min_length=32)

    # Free tier: ``free_quota_seconds`` of usage per ``cycle_seconds`` window.
    free_quota_seconds: int = 120 * 60
    cycle_seconds: int = 24 * 60 * 60

    # Session accounting. The client sends a heartbeat every
    # ``heartbeat_interval_seconds``; a gap longer than ``heartbeat_grace_seconds``
    # is credited as at most the grace value, and a session without heartbeats
    # for ``session_timeout_seconds`` is closed by the reaper.
    heartbeat_interval_seconds: int = 30
    heartbeat_grace_seconds: int = 45
    session_timeout_seconds: int = 120
    reaper_interval_seconds: int = 30

    # Public information returned to the app.
    support_telegram_username: str = "fastgamevpn_bot"
    min_supported_version_code: int = 1
    privacy_policy_url: str = ""
    terms_url: str = ""

    # Shared secret for the Telegram bot (same server, calls 127.0.0.1). Leave empty to disable
    # the /bot endpoints. Use a long random value (at least 32 characters).
    bot_api_token: str | None = None

    # Admin panel.
    admin_token_minutes: int = 12 * 60
    admin_bootstrap_username: str | None = None
    admin_bootstrap_password: str | None = None
    cors_origins: str = ""  # comma separated list of allowed origins

    # Abuse limits (per client IP).
    register_limit_per_hour: int = 60
    admin_login_limit_per_minute: int = 10

    @property
    def is_dev(self) -> bool:
        return self.env == "development"

    @property
    def cors_origin_list(self) -> list[str]:
        return [o.strip() for o in self.cors_origins.split(",") if o.strip()]


@lru_cache
def get_settings() -> Settings:
    return Settings()
