from datetime import datetime
from typing import Literal
from uuid import UUID

from pydantic import BaseModel, Field, field_validator

from app.services import ovpn


class AdminLoginRequest(BaseModel):
    username: str = Field(min_length=1, max_length=64)
    password: str = Field(min_length=1, max_length=256)


class AdminLoginResponse(BaseModel):
    access_token: str
    token_type: str = "bearer"
    expires_in: int


class AdminMeResponse(BaseModel):
    username: str


class QuotaAdminOut(BaseModel):
    used_seconds: int
    remaining_seconds: int
    resets_at: datetime | None


class DeviceAdminOut(BaseModel):
    id: UUID
    user_code: str
    is_vip: bool
    vip_expires_at: datetime | None
    vip_lifetime: bool
    is_blocked: bool
    block_reason: str | None
    blocked_at: datetime | None
    created_at: datetime
    last_seen_at: datetime | None
    app_version_code: int
    locale: str
    total_used_seconds: int
    quota: QuotaAdminOut
    has_active_session: bool


class SessionRowOut(BaseModel):
    id: UUID
    server_name: str
    started_at: datetime
    ended_at: datetime | None
    counted_seconds: int
    end_reason: str | None
    was_vip: bool


class VipEventOut(BaseModel):
    action: str
    days: int | None
    lifetime: bool
    expires_at_after: datetime | None
    admin_username: str
    created_at: datetime


class DeviceDetailOut(DeviceAdminOut):
    sessions: list[SessionRowOut]
    vip_events: list[VipEventOut]


class DeviceListOut(BaseModel):
    items: list[DeviceAdminOut]
    total: int
    limit: int
    offset: int


class VipActionIn(BaseModel):
    action: Literal["activate", "extend", "cancel"]
    days: int | None = Field(default=None, ge=1, le=3650)
    lifetime: bool = False


class BlockIn(BaseModel):
    blocked: bool
    reason: str | None = Field(default=None, max_length=255)


class MikrotikIn(BaseModel):
    """RouterOS 7 REST access. The password is write-only and stored encrypted."""

    api_url: str = Field(pattern=r"^https://[^\s/]+(:\d{1,5})?$", max_length=255)
    username: str = Field(min_length=1, max_length=64)
    password: str | None = Field(default=None, max_length=256)  # omit on update to keep the stored one
    ppp_profile: str = Field(min_length=1, max_length=64)
    ca_pem: str | None = Field(default=None, max_length=16384)  # pinned self-signed certificate (public)


class MikrotikOut(BaseModel):
    api_url: str
    username: str
    ppp_profile: str | None
    has_ca: bool


class XuiIn(BaseModel):
    """3x-ui panel access for Xray servers. The password is write-only and stored encrypted."""

    url: str = Field(pattern=r"^https://[^\s/]+(:\d{1,5})?(/[^\s]*)?$", max_length=255)  # incl. web base path
    username: str = Field(min_length=1, max_length=64)
    password: str | None = Field(default=None, max_length=256)  # omit on update to keep the stored one
    inbound_id: int = Field(ge=1)


class XuiOut(BaseModel):
    url: str
    username: str
    inbound_id: int | None


class ServerIn(BaseModel):
    kind: Literal["openvpn", "xray"] = "openvpn"
    # Xray servers: ovpn_config is a vless:// link template containing {uuid}.
    xui: XuiIn | None = None
    name: str = Field(min_length=1, max_length=64)
    country_code: str = Field(pattern=r"^[A-Za-z]{2}$")
    country_name_en: str = Field(min_length=1, max_length=64)
    country_name_fa: str = Field(min_length=1, max_length=64)
    # host/port/proto are read from the profile's 'remote' and 'proto' lines when omitted.
    host: str | None = Field(default=None, min_length=1, max_length=255)
    port: int | None = Field(default=None, ge=1, le=65535)
    proto: Literal["udp", "tcp"] | None = None
    ovpn_config: str = Field(min_length=10, max_length=65536)
    # Credentials are write-only: they are never returned by the API.
    auth_username: str | None = Field(default=None, max_length=128)
    auth_password: str | None = Field(default=None, max_length=256)
    clear_credentials: bool = False
    # Per-session router accounts. null = leave unchanged; use clear_mikrotik to turn it off.
    mikrotik: MikrotikIn | None = None
    clear_mikrotik: bool = False
    is_active: bool = True
    sort_order: int = 0

    @field_validator("country_code")
    @classmethod
    def _upper(cls, value: str) -> str:
        return value.upper()

    @field_validator("ovpn_config")
    @classmethod
    def _check_profile(cls, value: str) -> str:
        ovpn.check_profile(value)
        return value


class ServerAdminOut(BaseModel):
    id: int
    kind: str
    xui: XuiOut | None = None
    name: str
    country_code: str
    country_name_en: str
    country_name_fa: str
    host: str
    port: int
    proto: str
    ovpn_config: str
    auth_username: str | None
    has_credentials: bool
    mikrotik: MikrotikOut | None
    is_active: bool
    sort_order: int
    created_at: datetime
    active_connections: int


class StatsOut(BaseModel):
    total_devices: int
    vip_devices: int
    blocked_devices: int
    new_devices_24h: int
    active_sessions: int
    sessions_started_24h: int
    usage_seconds_24h: int


class ActiveSessionOut(BaseModel):
    session_id: UUID
    user_code: str
    server_id: int
    server_name: str
    started_at: datetime
    last_heartbeat_at: datetime
    duration_seconds: int
    counted_seconds: int
    was_vip: bool
