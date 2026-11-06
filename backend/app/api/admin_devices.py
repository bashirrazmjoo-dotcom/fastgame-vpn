"""Admin: search users, see usage, manage VIP, block users."""

from datetime import datetime
from typing import Literal

from fastapi import APIRouter, Depends, Query
from sqlalchemy import func, select
from sqlalchemy.orm import Session

from app.api.deps import get_current_admin, get_db
from app.core import clock, security
from app.core.errors import ApiError
from app.models.admin import AdminUser
from app.models.device import Device
from app.models.server import VpnServer
from app.models.session import VpnSession
from app.models.vip import VipEvent
from app.schemas.admin import (
    BlockIn,
    DeviceAdminOut,
    DeviceDetailOut,
    DeviceListOut,
    QuotaAdminOut,
    SessionRowOut,
    VipActionIn,
    VipEventOut,
)
from app.services import devices as device_service
from app.services import quota
from app.services import vip as vip_service

router = APIRouter(prefix="/admin/devices", tags=["admin"], dependencies=[Depends(get_current_admin)])


def _to_out(device: Device, now: datetime, has_active: bool) -> DeviceAdminOut:
    snap = quota.snapshot(device, now)
    return DeviceAdminOut(
        id=device.id,
        user_code=device.user_code,
        is_vip=quota.is_vip(device, now),
        vip_expires_at=device.vip_expires_at,
        vip_lifetime=device.vip_lifetime,
        is_blocked=device.is_blocked,
        block_reason=device.block_reason,
        blocked_at=device.blocked_at,
        created_at=device.created_at,
        last_seen_at=device.last_seen_at,
        app_version_code=device.app_version_code,
        locale=device.locale,
        total_used_seconds=int(device.total_used_seconds),
        quota=QuotaAdminOut(
            used_seconds=snap.used_seconds,
            remaining_seconds=snap.remaining_seconds,
            resets_at=snap.resets_at,
        ),
        has_active_session=has_active,
    )


def _has_active(db: Session, device_id) -> bool:
    return (
        db.scalar(
            select(VpnSession.id).where(VpnSession.device_id == device_id, VpnSession.ended_at.is_(None))
        )
        is not None
    )


def _get_or_404(db: Session, code: str) -> Device:
    stmt = select(Device).where(Device.user_code == security.format_user_code(code))
    device = db.execute(stmt).scalar_one_or_none()
    if device is None:
        raise ApiError(404, "device_not_found", "No user with this code.")
    return device


@router.get("", response_model=DeviceListOut)
def list_devices(
    q: str | None = Query(default=None, max_length=64),
    status: Literal["all", "vip", "free", "blocked"] = "all",
    sort: Literal["created", "last_seen", "usage"] = "created",
    limit: int = Query(default=50, ge=1, le=200),
    offset: int = Query(default=0, ge=0),
    db: Session = Depends(get_db),
) -> DeviceListOut:
    now = clock.utcnow()
    vip_cond = quota.vip_sql_condition(now)

    stmt = select(Device)
    if q:
        term = security.strip_user_code(q)
        if term:
            stmt = stmt.where(func.replace(Device.user_code, "-", "").like(f"%{term}%"))
    if status == "vip":
        stmt = stmt.where(vip_cond)
    elif status == "blocked":
        stmt = stmt.where(Device.is_blocked.is_(True))
    elif status == "free":
        stmt = stmt.where(~vip_cond, Device.is_blocked.is_(False))

    total = db.scalar(select(func.count()).select_from(stmt.subquery())) or 0
    order = {
        "created": Device.created_at.desc(),
        "last_seen": Device.last_seen_at.desc().nulls_last(),
        "usage": Device.total_used_seconds.desc(),
    }[sort]
    rows = list(db.execute(stmt.order_by(order, Device.id).limit(limit).offset(offset)).scalars())

    active_ids = set()
    if rows:
        active_ids = set(
            db.scalars(
                select(VpnSession.device_id).where(
                    VpnSession.ended_at.is_(None), VpnSession.device_id.in_([d.id for d in rows])
                )
            )
        )
    items = [_to_out(d, now, d.id in active_ids) for d in rows]
    return DeviceListOut(items=items, total=total, limit=limit, offset=offset)


@router.get("/{code}", response_model=DeviceDetailOut)
def device_detail(code: str, db: Session = Depends(get_db)) -> DeviceDetailOut:
    now = clock.utcnow()
    device = _get_or_404(db, code)
    base = _to_out(device, now, _has_active(db, device.id))

    session_rows = db.execute(
        select(VpnSession, VpnServer.name)
        .join(VpnServer, VpnServer.id == VpnSession.server_id)
        .where(VpnSession.device_id == device.id)
        .order_by(VpnSession.started_at.desc())
        .limit(30)
    ).all()
    events = db.scalars(
        select(VipEvent).where(VipEvent.device_id == device.id).order_by(VipEvent.created_at.desc()).limit(30)
    )
    return DeviceDetailOut(
        **base.model_dump(),
        sessions=[
            SessionRowOut(
                id=s.id,
                server_name=name,
                started_at=s.started_at,
                ended_at=s.ended_at,
                counted_seconds=int(s.counted_seconds),
                end_reason=s.end_reason,
                was_vip=s.was_vip,
            )
            for s, name in session_rows
        ],
        vip_events=[
            VipEventOut(
                action=e.action,
                days=e.days,
                lifetime=e.lifetime,
                expires_at_after=e.expires_at_after,
                admin_username=e.admin_username,
                created_at=e.created_at,
            )
            for e in events
        ],
    )


@router.post("/{code}/vip", response_model=DeviceAdminOut)
def change_vip(
    code: str,
    body: VipActionIn,
    admin: AdminUser = Depends(get_current_admin),
    db: Session = Depends(get_db),
) -> DeviceAdminOut:
    device = _get_or_404(db, code)
    updated = vip_service.apply_action(
        db,
        device.id,
        action=body.action,
        days=body.days,
        lifetime=body.lifetime,
        admin_username=admin.username,
    )
    return _to_out(updated, clock.utcnow(), _has_active(db, updated.id))


@router.post("/{code}/block", response_model=DeviceAdminOut)
def change_block(code: str, body: BlockIn, db: Session = Depends(get_db)) -> DeviceAdminOut:
    device = _get_or_404(db, code)
    updated = device_service.set_blocked(db, device.id, body.blocked, body.reason)
    return _to_out(updated, clock.utcnow(), _has_active(db, updated.id))


@router.post("/{code}/reset-quota", response_model=DeviceAdminOut)
def reset_quota(code: str, db: Session = Depends(get_db)) -> DeviceAdminOut:
    device = _get_or_404(db, code)
    updated = device_service.reset_quota(db, device.id)
    return _to_out(updated, clock.utcnow(), _has_active(db, updated.id))
