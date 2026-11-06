"""VIP management. VIP removes the free quota limit while it is active."""

from datetime import timedelta
from uuid import UUID

from sqlalchemy.orm import Session

from app.core import clock
from app.core.errors import ApiError
from app.models.device import Device
from app.models.vip import VipEvent
from app.services import sessions


def apply_action(
    db: Session,
    device_id: UUID,
    *,
    action: str,
    days: int | None,
    lifetime: bool,
    admin_username: str,
) -> Device:
    """``activate`` starts VIP from now, ``extend`` adds to the current end, ``cancel`` removes it."""
    now = clock.utcnow()
    device = sessions.lock_device(db, device_id)

    if action == "cancel":
        device.vip_expires_at = None
        device.vip_lifetime = False
        days = None
        lifetime = False

    elif action == "activate":
        if lifetime:
            device.vip_lifetime = True
            device.vip_expires_at = None
            days = None
        else:
            if not days:
                raise ApiError(422, "days_required", "Provide the number of days or lifetime.")
            device.vip_lifetime = False
            device.vip_expires_at = now + timedelta(days=days)

    elif action == "extend":
        if lifetime:
            raise ApiError(422, "invalid_extend", "Use activate to grant lifetime VIP.")
        if device.vip_lifetime:
            raise ApiError(409, "already_lifetime", "This account already has lifetime VIP.")
        if not days:
            raise ApiError(422, "days_required", "Provide the number of days to add.")
        base = device.vip_expires_at if device.vip_expires_at and device.vip_expires_at > now else now
        device.vip_expires_at = base + timedelta(days=days)

    else:  # pragma: no cover - the request schema restricts the values
        raise ApiError(422, "invalid_action", "Unknown VIP action.")

    db.add(
        VipEvent(
            device_id=device.id,
            action=action,
            days=days,
            lifetime=device.vip_lifetime,
            expires_at_after=device.vip_expires_at,
            admin_username=admin_username,
        )
    )
    db.commit()
    return device
