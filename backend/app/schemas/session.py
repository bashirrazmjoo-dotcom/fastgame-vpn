from datetime import datetime
from typing import Literal
from uuid import UUID

from pydantic import BaseModel


class StartSessionRequest(BaseModel):
    server_id: int


class StartSessionResponse(BaseModel):
    session_id: UUID
    server_id: int
    heartbeat_interval_seconds: int
    is_vip: bool
    remaining_seconds: int | None  # None = unlimited (VIP)
    resets_at: datetime | None
    kind: Literal["openvpn", "xray"] = "openvpn"
    ovpn_config: str  # empty for xray servers
    xray_link: str | None = None  # vless:// link of this session's client (xray servers)
    auth_username: str | None
    auth_password: str | None


class SessionStateResponse(BaseModel):
    status: Literal["active", "quota_exhausted", "closed"]
    is_vip: bool
    remaining_seconds: int | None  # None = unlimited (VIP)
    resets_at: datetime | None
