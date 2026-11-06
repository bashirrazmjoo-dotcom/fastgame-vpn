"""Free-tier quota rules. Pure functions over a Device and a server-side ``now``.

A cycle starts the first time a free user connects after the previous cycle has
ended and lasts ``cycle_seconds``. Inside it the user may use
``free_quota_seconds`` in total, across any number of connections. When the
cycle window ends the quota is fully recharged on the next connection.
"""

import math
from dataclasses import dataclass
from datetime import datetime, timedelta

from sqlalchemy import and_, or_

from app.core.config import get_settings
from app.models.device import Device


@dataclass(frozen=True)
class QuotaSnapshot:
    total_seconds: int
    used_seconds: int
    remaining_seconds: int
    exhausted: bool
    cycle_started_at: datetime | None
    resets_at: datetime | None


def is_vip(device: Device, now: datetime) -> bool:
    return device.vip_lifetime or (device.vip_expires_at is not None and device.vip_expires_at > now)


def vip_sql_condition(now: datetime):
    """SQL twin of ``is_vip``. It is never NULL, so it is safe to negate with ``~``."""
    return or_(
        Device.vip_lifetime.is_(True),
        and_(Device.vip_expires_at.is_not(None), Device.vip_expires_at > now),
    )


def cycle_is_active(device: Device, now: datetime) -> bool:
    if device.cycle_started_at is None:
        return False
    return now < device.cycle_started_at + timedelta(seconds=get_settings().cycle_seconds)


def roll_cycle(device: Device, now: datetime) -> None:
    """Start a fresh cycle if the previous one is over (or never started)."""
    if not cycle_is_active(device, now):
        device.cycle_started_at = now
        device.cycle_used_seconds = 0.0


def remaining_exact(device: Device, now: datetime) -> float:
    total = float(get_settings().free_quota_seconds)
    if not cycle_is_active(device, now):
        return total
    return max(0.0, total - device.cycle_used_seconds)


def charge(device: Device, seconds: float, now: datetime) -> None:
    """Charge connected time to the free quota (never beyond the total)."""
    roll_cycle(device, now)
    total = float(get_settings().free_quota_seconds)
    device.cycle_used_seconds = min(total, device.cycle_used_seconds + seconds)


def snapshot(device: Device, now: datetime) -> QuotaSnapshot:
    total = get_settings().free_quota_seconds
    exact = remaining_exact(device, now)
    remaining = min(total, math.ceil(exact))
    active = cycle_is_active(device, now)
    started = device.cycle_started_at if active else None
    resets = started + timedelta(seconds=get_settings().cycle_seconds) if started else None
    return QuotaSnapshot(
        total_seconds=total,
        used_seconds=total - remaining,
        remaining_seconds=remaining,
        exhausted=exact <= 0,
        cycle_started_at=started,
        resets_at=resets,
    )
