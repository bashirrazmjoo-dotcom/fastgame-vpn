"""Endpoints used by the Android app: config, registration, account state, servers."""

from fastapi import APIRouter, Depends, Request
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.api.deps import client_ip, get_current_device, get_db
from app.core import clock, security
from app.core.config import get_settings
from app.core.errors import ApiError
from app.core.ratelimit import limiter
from app.models.device import Device
from app.models.server import VpnServer
from app.models.session import VpnSession
from app.schemas.device import (
    ConfigResponse,
    MeResponse,
    QuotaOut,
    RegisterRequest,
    RegisterResponse,
    ServerOut,
)
from app.services import devices, quota, updates

router = APIRouter(prefix="/v1", tags=["app"])


def _telegram_url(user_code: str | None = None) -> str:
    base = f"https://t.me/{get_settings().support_telegram_username}"
    if user_code:
        return f"{base}?start=fg_{security.strip_user_code(user_code)}"
    return base


def _vip_purchase_url(user_code: str) -> str:
    """Web page where the user logs in (Telegram or SMS) and buys VIP for this user code."""
    base = get_settings().vip_purchase_base_url
    return f"{base}?page=vip&code={security.strip_user_code(user_code)}"


@router.get("/config", response_model=ConfigResponse)
def get_config() -> ConfigResponse:
    s = get_settings()
    return ConfigResponse(
        free_quota_seconds=s.free_quota_seconds,
        cycle_seconds=s.cycle_seconds,
        heartbeat_interval_seconds=s.heartbeat_interval_seconds,
        support_telegram_username=s.support_telegram_username,
        support_url=_telegram_url(),
        min_supported_version_code=s.min_supported_version_code,
        latest_version_code=updates.latest_version_code(),
        update_url=s.update_download_url,
        privacy_policy_url=s.privacy_policy_url,
        terms_url=s.terms_url,
    )


@router.post("/devices/register", response_model=RegisterResponse)
def register(body: RegisterRequest, request: Request, db: Session = Depends(get_db)) -> RegisterResponse:
    s = get_settings()
    if not limiter.allow(f"register:{client_ip(request)}", s.register_limit_per_hour, 3600):
        raise ApiError(429, "rate_limited", "Too many requests. Please try again later.")
    device, token, created = devices.register_device(
        db,
        hardware_id=body.hardware_id,
        app_version_code=body.app_version_code,
        locale=body.locale,
    )
    return RegisterResponse(user_code=device.user_code, device_token=token, is_new=created)


@router.get("/me", response_model=MeResponse)
def me(device: Device = Depends(get_current_device), db: Session = Depends(get_db)) -> MeResponse:
    now = clock.utcnow()
    snap = quota.snapshot(device, now)
    active_id = db.scalar(
        select(VpnSession.id).where(VpnSession.device_id == device.id, VpnSession.ended_at.is_(None))
    )
    return MeResponse(
        user_code=device.user_code,
        is_vip=quota.is_vip(device, now),
        vip_expires_at=device.vip_expires_at,
        vip_lifetime=device.vip_lifetime,
        is_blocked=device.is_blocked,
        quota=QuotaOut(
            total_seconds=snap.total_seconds,
            used_seconds=snap.used_seconds,
            remaining_seconds=snap.remaining_seconds,
            cycle_started_at=snap.cycle_started_at,
            resets_at=snap.resets_at,
        ),
        active_session_id=active_id,
        vip_purchase_url=_vip_purchase_url(device.user_code),
        server_time=now,
    )


@router.get("/servers", response_model=list[ServerOut])
def list_servers(_: Device = Depends(get_current_device), db: Session = Depends(get_db)) -> list[ServerOut]:
    stmt = (
        select(VpnServer).where(VpnServer.is_active.is_(True)).order_by(VpnServer.sort_order, VpnServer.id)
    )
    return [
        ServerOut(
            id=s.id,
            name=s.name,
            country_code=s.country_code,
            country_name_en=s.country_name_en,
            country_name_fa=s.country_name_fa,
        )
        for s in db.execute(stmt).scalars()
    ]
