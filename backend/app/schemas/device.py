from datetime import datetime
from uuid import UUID

from pydantic import BaseModel, Field


class RegisterRequest(BaseModel):
    # Stable per-device value (Settings.Secure.ANDROID_ID on Android). It survives
    # reinstalls, which is what lets the server recognise a returning device.
    hardware_id: str = Field(min_length=8, max_length=128)
    app_version_code: int = Field(ge=1, le=10_000_000)
    locale: str = Field(default="en", max_length=8)


class RegisterResponse(BaseModel):
    user_code: str
    device_token: str
    is_new: bool


class ConfigResponse(BaseModel):
    free_quota_seconds: int
    cycle_seconds: int
    heartbeat_interval_seconds: int
    support_telegram_username: str
    support_url: str
    min_supported_version_code: int
    latest_version_code: int
    update_url: str
    privacy_policy_url: str
    terms_url: str


class QuotaOut(BaseModel):
    total_seconds: int
    used_seconds: int
    remaining_seconds: int
    cycle_started_at: datetime | None
    resets_at: datetime | None


class MeResponse(BaseModel):
    user_code: str
    is_vip: bool
    vip_expires_at: datetime | None
    vip_lifetime: bool
    is_blocked: bool
    quota: QuotaOut
    active_session_id: UUID | None
    vip_purchase_url: str
    server_time: datetime  # informational only; the app never sends time back


class ServerOut(BaseModel):
    id: int
    name: str
    country_code: str
    country_name_en: str
    country_name_fa: str
