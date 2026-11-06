"""Ties VPN sessions to router accounts: hand out credentials at start, take them back at end."""

import logging
from uuid import UUID

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.core import clock, security
from app.models.device import Device
from app.models.server import VpnServer
from app.models.session import VpnSession
from app.services import mikrotik, sessions

log = logging.getLogger(__name__)

# Router-side hard stop = remaining free time + this slack (connect time is not charged).
UPTIME_SLACK_SECONDS = 60
SWEEP_BATCH = 50


def uses_router_accounts(server: VpnServer) -> bool:
    return bool(server.mt_api_url)


def issue(db: Session, server: VpnServer, session: VpnSession, remaining_seconds: int | None):
    """Create the session's router account. Returns (username, password).

    On failure the session is closed without charge and the error is raised to the caller.
    """
    username, password = security.session_credentials(session.id)
    limit = None if remaining_seconds is None else max(1, remaining_seconds) + UPTIME_SLACK_SECONDS
    try:
        mikrotik.create_account(server, username, password, limit_uptime_seconds=limit)
    except mikrotik.MikrotikError:
        session.ended_at = clock.utcnow()
        session.end_reason = "provision_failed"
        session.revoked_at = session.ended_at  # nothing was created
        db.commit()
        raise
    return username, password


def revoke(db: Session, session: VpnSession) -> bool:
    """Remove the session's router account. Returns True when it is gone."""
    server = db.get(VpnServer, session.server_id)
    if server is None or not uses_router_accounts(server):
        return True
    username, _ = security.session_credentials(session.id)
    try:
        mikrotik.remove_account(server, username)
    except mikrotik.MikrotikError as e:
        log.warning("could not revoke account of session %s: %s", session.id, e)
        return False
    session.revoked_at = clock.utcnow()
    db.commit()
    return True


def revoke_now(db: Session, session_id: UUID) -> None:
    """Best effort right after a session ended; the sweeper retries failures."""
    session = db.get(VpnSession, session_id)
    if session is not None and session.ended_at is not None and session.revoked_at is None:
        revoke(db, session)


def sweep(db: Session) -> int:
    """End sessions of blocked users, then revoke the accounts of every ended session."""
    now = clock.utcnow()
    blocked = db.execute(
        select(VpnSession)
        .join(Device, Device.id == VpnSession.device_id)
        .where(VpnSession.ended_at.is_(None), Device.is_blocked.is_(True))
        .with_for_update(skip_locked=True)
    ).scalars().all()
    for s in blocked:
        sessions.end(s, now, "blocked")
    db.commit()

    pending = db.execute(
        select(VpnSession)
        .join(VpnServer, VpnServer.id == VpnSession.server_id)
        .where(
            VpnSession.ended_at.is_not(None),
            VpnSession.revoked_at.is_(None),
            VpnServer.mt_api_url.is_not(None),
        )
        .limit(SWEEP_BATCH)
        .with_for_update(of=VpnSession, skip_locked=True)
    ).scalars().all()
    return sum(1 for s in pending if revoke(db, s))
