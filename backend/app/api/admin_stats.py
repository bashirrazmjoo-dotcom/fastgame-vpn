"""Admin: dashboard numbers and live connections."""

from datetime import timedelta

from fastapi import APIRouter, Depends
from sqlalchemy import func, select
from sqlalchemy.orm import Session

from app.api.deps import get_current_admin, get_db
from app.core import clock
from app.models.device import Device
from app.models.server import VpnServer
from app.models.session import VpnSession
from app.schemas.admin import ActiveSessionOut, StatsOut
from app.services import quota

router = APIRouter(prefix="/admin", tags=["admin"], dependencies=[Depends(get_current_admin)])


@router.get("/stats", response_model=StatsOut)
def stats(db: Session = Depends(get_db)) -> StatsOut:
    now = clock.utcnow()
    day_ago = now - timedelta(hours=24)
    vip_cond = quota.vip_sql_condition(now)

    def count(*conditions) -> int:
        return db.scalar(select(func.count()).select_from(Device).where(*conditions)) or 0

    return StatsOut(
        total_devices=count(),
        vip_devices=count(vip_cond),
        blocked_devices=count(Device.is_blocked.is_(True)),
        new_devices_24h=count(Device.created_at >= day_ago),
        active_sessions=db.scalar(select(func.count()).where(VpnSession.ended_at.is_(None))) or 0,
        sessions_started_24h=db.scalar(select(func.count()).where(VpnSession.started_at >= day_ago)) or 0,
        usage_seconds_24h=int(
            db.scalar(
                select(func.coalesce(func.sum(VpnSession.counted_seconds), 0)).where(
                    VpnSession.started_at >= day_ago
                )
            )
            or 0
        ),
    )


@router.get("/sessions/active", response_model=list[ActiveSessionOut])
def active_sessions(db: Session = Depends(get_db)) -> list[ActiveSessionOut]:
    now = clock.utcnow()
    rows = db.execute(
        select(VpnSession, Device.user_code, VpnServer.name)
        .join(Device, Device.id == VpnSession.device_id)
        .join(VpnServer, VpnServer.id == VpnSession.server_id)
        .where(VpnSession.ended_at.is_(None))
        .order_by(VpnSession.started_at)
    ).all()
    return [
        ActiveSessionOut(
            session_id=s.id,
            user_code=code,
            server_id=s.server_id,
            server_name=name,
            started_at=s.started_at,
            last_heartbeat_at=s.last_heartbeat_at,
            duration_seconds=int((now - s.started_at).total_seconds()),
            counted_seconds=int(s.counted_seconds),
            was_vip=s.was_vip,
        )
        for s, code, name in rows
    ]
