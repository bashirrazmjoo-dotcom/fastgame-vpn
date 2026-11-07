"""Endpoints for the Telegram bot (activate / extend / cancel VIP by user code).

Disabled unless ``FG_BOT_API_TOKEN`` (>= 32 characters) is set. The bot runs on the same server and
calls the backend directly on 127.0.0.1; the token is the only credential, so it must stay secret.
"""

import hmac
from datetime import datetime

from fastapi import APIRouter, Depends, Header, Request
from pydantic import BaseModel
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.api.deps import client_ip, get_db
from app.core import clock, security
from app.core.config import get_settings
from app.core.errors import ApiError
from app.core.ratelimit import limiter
from app.models.device import Device
from app.schemas.admin import VipActionIn
from app.services import quota
from app.services import vip as vip_service

router = APIRouter(prefix="/bot", tags=["bot"], include_in_schema=False)

MIN_TOKEN_LENGTH = 32
BOT_ADMIN_NAME = "telegram-bot"


class BotDeviceOut(BaseModel):
    user_code: str
    is_vip: bool
    vip_lifetime: bool
    vip_expires_at: datetime | None
    is_blocked: bool


def require_bot(request: Request, x_bot_token: str | None = Header(default=None)) -> None:
    expected = get_settings().bot_api_token
    if not expected or len(expected) < MIN_TOKEN_LENGTH:
        raise ApiError(404, "not_found", "Not found.")
    if not limiter.allow(f"bot-api:{client_ip(request)}", 120, 60):
        raise ApiError(429, "rate_limited", "Too many requests.")
    if not x_bot_token or not hmac.compare_digest(x_bot_token.encode(), expected.encode()):
        raise ApiError(401, "unauthorized", "Invalid bot token.")


def _find(db: Session, code: str) -> Device:
    stmt = select(Device).where(Device.user_code == security.format_user_code(code))
    device = db.execute(stmt).scalar_one_or_none()
    if device is None:
        raise ApiError(404, "device_not_found", "No user with this code.")
    return device


def _out(device: Device) -> BotDeviceOut:
    return BotDeviceOut(
        user_code=device.user_code,
        is_vip=quota.is_vip(device, clock.utcnow()),
        vip_lifetime=device.vip_lifetime,
        vip_expires_at=device.vip_expires_at,
        is_blocked=device.is_blocked,
    )


@router.get("/devices/{code}", response_model=BotDeviceOut, dependencies=[Depends(require_bot)])
def get_device(code: str, db: Session = Depends(get_db)) -> BotDeviceOut:
    return _out(_find(db, code))


@router.post("/devices/{code}/vip", response_model=BotDeviceOut, dependencies=[Depends(require_bot)])
def change_vip(code: str, body: VipActionIn, db: Session = Depends(get_db)) -> BotDeviceOut:
    device = _find(db, code)
    updated = vip_service.apply_action(
        db,
        device.id,
        action=body.action,
        days=body.days,
        lifetime=body.lifetime,
        admin_username=BOT_ADMIN_NAME,
    )
    return _out(updated)
