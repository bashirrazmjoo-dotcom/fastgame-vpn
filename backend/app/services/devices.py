"""Device registration and moderation."""

from uuid import UUID

from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from app.core import clock, security
from app.core.errors import ApiError
from app.models.device import Device
from app.services import sessions


def register_device(
    db: Session, *, hardware_id: str, app_version_code: int, locale: str
) -> tuple[Device, str, bool]:
    """Create the identity for this install, or recognise a returning device.

    The same hardware id always maps to the same device, so reinstalling the app
    (or clearing its data) returns the same user code, quota and block state
    instead of a fresh free quota. A new access token is issued each time, which
    invalidates the token of the previous install.

    Returns ``(device, plaintext_token, created)``.
    """
    fingerprint = security.hash_fingerprint(hardware_id)
    token = security.new_device_token()
    token_hash = security.hash_token(token)
    now = clock.utcnow()

    for _ in range(5):
        stmt = select(Device).where(Device.fingerprint_hash == fingerprint).with_for_update()
        device = db.execute(stmt).scalar_one_or_none()
        if device is not None:
            device.token_hash = token_hash
            device.app_version_code = app_version_code
            device.locale = locale
            device.last_seen_at = now
            db.commit()
            return device, token, False
        try:
            with db.begin_nested():
                device = Device(
                    user_code=security.new_user_code(),
                    token_hash=token_hash,
                    fingerprint_hash=fingerprint,
                    app_version_code=app_version_code,
                    locale=locale,
                    last_seen_at=now,
                )
                db.add(device)
                db.flush()
            db.commit()
            return device, token, True
        except IntegrityError:
            # Either a user-code collision or a concurrent registration of the
            # same device: loop and look it up again.
            continue
    raise ApiError(503, "register_failed", "Could not create the account, please retry.")


def set_blocked(db: Session, device_id: UUID, blocked: bool, reason: str | None) -> Device:
    now = clock.utcnow()
    device = sessions.lock_device(db, device_id)
    device.is_blocked = blocked
    device.blocked_at = now if blocked else None
    device.block_reason = (reason or None) if blocked else None
    if blocked:
        for session in sessions.open_sessions(db, device.id):
            sessions.close(device, session, now, "blocked")
    db.commit()
    return device


def reset_quota(db: Session, device_id: UUID) -> Device:
    """Give the device a full fresh quota (support action)."""
    device = sessions.lock_device(db, device_id)
    device.cycle_started_at = None
    device.cycle_used_seconds = 0.0
    db.commit()
    return device
