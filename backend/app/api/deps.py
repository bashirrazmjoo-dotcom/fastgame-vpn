from collections.abc import Iterator

from fastapi import Depends, Header, Request
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.core import clock, security
from app.core.db import SessionLocal
from app.core.errors import ApiError
from app.models.admin import AdminUser
from app.models.device import Device


def get_db() -> Iterator[Session]:
    with SessionLocal() as db:
        yield db


def client_ip(request: Request) -> str:
    # Behind Nginx, uvicorn runs with --proxy-headers so this is the real client IP.
    return request.client.host if request.client else "unknown"


def _bearer(authorization: str | None) -> str | None:
    if not authorization:
        return None
    scheme, _, value = authorization.partition(" ")
    value = value.strip()
    return value if scheme.lower() == "bearer" and value else None


def get_current_device(
    authorization: str | None = Header(default=None), db: Session = Depends(get_db)
) -> Device:
    token = _bearer(authorization)
    device = None
    if token:
        stmt = select(Device).where(Device.token_hash == security.hash_token(token))
        device = db.execute(stmt).scalar_one_or_none()
    if device is None:
        raise ApiError(401, "invalid_token", "Invalid or expired access token.")

    now = clock.utcnow()
    if device.last_seen_at is None or (now - device.last_seen_at).total_seconds() > 60:
        device.last_seen_at = now
        db.commit()
    return device


def get_current_admin(
    authorization: str | None = Header(default=None), db: Session = Depends(get_db)
) -> AdminUser:
    token = _bearer(authorization)
    username = security.decode_admin_token(token) if token else None
    admin = None
    if username:
        stmt = select(AdminUser).where(AdminUser.username == username, AdminUser.is_active.is_(True))
        admin = db.execute(stmt).scalar_one_or_none()
    if admin is None:
        raise ApiError(401, "unauthorized", "Admin authentication required.")
    return admin
