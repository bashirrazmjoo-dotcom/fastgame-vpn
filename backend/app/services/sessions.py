"""VPN session accounting.

Time is measured only here, only with the server clock. The app never reports
how long it has been connected; it only says "I am still connected" through
heartbeats. Each heartbeat credits the time since the previous one, capped at
``heartbeat_grace_seconds`` so a stalled or blocked client cannot be credited
(or charged) for an arbitrarily long gap.

Concurrency: every operation takes a row lock on the device first, so two
requests for the same device are always processed one after the other.
"""

import logging
from dataclasses import dataclass
from datetime import timedelta
from uuid import UUID

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.core import clock
from app.core.config import get_settings
from app.core.errors import ApiError
from app.models.device import Device
from app.models.server import VpnServer
from app.models.session import VpnSession
from app.services import quota

log = logging.getLogger(__name__)


@dataclass
class StartResult:
    session: VpnSession
    server: VpnServer
    vip: bool
    snapshot: quota.QuotaSnapshot


@dataclass
class StateResult:
    status: str  # active | quota_exhausted | closed
    vip: bool
    snapshot: quota.QuotaSnapshot


def lock_device(db: Session, device_id: UUID) -> Device:
    return db.execute(select(Device).where(Device.id == device_id).with_for_update()).scalar_one()


def open_sessions(db: Session, device_id: UUID) -> list[VpnSession]:
    stmt = (
        select(VpnSession)
        .where(VpnSession.device_id == device_id, VpnSession.ended_at.is_(None))
        .with_for_update()
    )
    return list(db.execute(stmt).scalars())


def account(device: Device, session: VpnSession, now) -> float:
    """Credit the time since the last heartbeat. Returns the credited seconds."""
    if session.connected_at is None:
        # Still establishing the tunnel: nothing is charged until the app confirms it is up.
        session.last_heartbeat_at = now
        return 0.0
    elapsed =(now - session.last_heartbeat_at).total_seconds()
    credited = max(0.0, min(elapsed, float(get_settings().heartbeat_grace_seconds)))
    session.counted_seconds += credited
    session.last_heartbeat_at = now
    device.total_used_seconds += credited
    if not quota.is_vip(device, now):
        quota.charge(device, credited, now)
    return credited


def end(session: VpnSession, when, reason: str) -> None:
    session.ended_at = when
    session.end_reason = reason


def close(device: Device, session: VpnSession, now, reason: str) -> None:
    account(device, session, now)
    end(session, now, reason)


def start_session(db: Session, device_id: UUID, server_id: int) -> StartResult:
    now = clock.utcnow()
    device = lock_device(db, device_id)
    if device.is_blocked:
        raise ApiError(403, "device_blocked", "This account has been blocked.")

    server = db.get(VpnServer, server_id)
    if server is None or not server.is_active:
        raise ApiError(404, "server_unavailable", "This server is not available.")

    # A device may only be connected once: starting a new session replaces the old one.
    for old in open_sessions(db, device.id):
        close(device, old, now, "replaced")
    db.flush()

    vip = quota.is_vip(device, now)
    if not vip:
        quota.roll_cycle(device, now)
        if quota.remaining_exact(device, now) <= 0:
            snap = quota.snapshot(device, now)
            db.commit()  # keep the time credited to the replaced session
            raise ApiError(
                403,
                "quota_exhausted",
                "The free quota for this cycle is used up.",
                {"resets_at": snap.resets_at.isoformat() if snap.resets_at else None},
            )

    session = VpnSession(
        device_id=device.id, server_id=server.id, started_at=now, last_heartbeat_at=now, was_vip=vip
    )
    db.add(session)
    db.commit()
    return StartResult(session, server, vip, quota.snapshot(device, now))


def _get_session(db: Session, device_id: UUID, session_id: UUID) -> VpnSession:
    stmt = (
        select(VpnSession)
        .where(VpnSession.id == session_id, VpnSession.device_id == device_id)
        .with_for_update()
    )
    session = db.execute(stmt).scalar_one_or_none()
    if session is None:
        raise ApiError(404, "session_not_found", "Session not found.")
    return session


def _open_session(db: Session, device_id: UUID, session_id: UUID) -> tuple[Device, VpnSession]:
    device = lock_device(db, device_id)
    session = _get_session(db, device.id, session_id)
    if session.ended_at is not None:
        raise ApiError(409, "session_closed", "This session has ended.", {"reason": session.end_reason})
    return device, session


def heartbeat(db: Session, device_id: UUID, session_id: UUID) -> StateResult:
    now = clock.utcnow()
    device, session = _open_session(db, device_id, session_id)
    return _heartbeat_locked(db, device, session, now)


def confirm_connected(db: Session, device_id: UUID, session_id: UUID) -> StateResult:
    """The app reports the tunnel is up: from now on connected time is charged.

    Calling it again on an already-confirmed session behaves like a heartbeat, so it
    can never be used to skip charging.
    """
    now = clock.utcnow()
    device, session = _open_session(db, device_id, session_id)
    if session.connected_at is None:
        session.connected_at = now
        session.last_heartbeat_at = now
        db.commit()
        return StateResult("active", quota.is_vip(device, now), quota.snapshot(device, now))
    return _heartbeat_locked(db, device, session, now)


def _heartbeat_locked(db: Session, device: Device, session: VpnSession, now) -> StateResult:
    account(device, session, now)
    if device.is_blocked:
        end(session, now, "blocked")
        db.commit()
        raise ApiError(403, "device_blocked", "This account has been blocked.")

    vip = quota.is_vip(device, now)
    if not vip and quota.remaining_exact(device, now) <= 0:
        end(session, now, "quota_exhausted")
        db.commit()
        return StateResult("quota_exhausted", False, quota.snapshot(device, now))

    db.commit()
    return StateResult("active", vip, quota.snapshot(device, now))


def stop_session(db: Session, device_id: UUID, session_id: UUID) -> StateResult:
    """Idempotent: stopping an already-ended session is not an error."""
    now = clock.utcnow()
    device = lock_device(db, device_id)
    session = _get_session(db, device.id, session_id)
    if session.ended_at is None:
        close(device, session, now, "user")
        db.commit()
    return StateResult("closed", quota.is_vip(device, now), quota.snapshot(device, now))


def reap_stale_sessions(db: Session) -> int:
    """Close sessions whose app stopped sending heartbeats (crash, no network...).

    The session ends at its last heartbeat; no extra time is charged.
    """
    cutoff = clock.utcnow() - timedelta(seconds=get_settings().session_timeout_seconds)
    stmt = (
        select(VpnSession)
        .where(VpnSession.ended_at.is_(None), VpnSession.last_heartbeat_at < cutoff)
        .with_for_update(skip_locked=True)
    )
    stale = list(db.execute(stmt).scalars())
    for session in stale:
        end(session, session.last_heartbeat_at, "timeout")
    db.commit()
    if stale:
        log.info("closed %d stale session(s)", len(stale))
    return len(stale)
