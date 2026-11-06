import uuid
from datetime import datetime

from sqlalchemy import Boolean, CheckConstraint, DateTime, Float, Integer, String, text
from sqlalchemy.orm import Mapped, mapped_column

from app.core import clock
from app.core.db import Base


def _now() -> datetime:
    return clock.utcnow()


class Device(Base):
    """An app installation identity. Free quota, VIP and block state live here.

    The server never stores the raw hardware id or the raw access token, only
    keyed hashes of them. No IP addresses or traffic data are stored at all.
    """

    __tablename__ = "devices"
    __table_args__ = (CheckConstraint("cycle_used_seconds >= 0", name="cycle_used_non_negative"),)

    id: Mapped[uuid.UUID] = mapped_column(primary_key=True, default=uuid.uuid4)
    user_code: Mapped[str] = mapped_column(String(16), unique=True)
    token_hash: Mapped[str] = mapped_column(String(64), unique=True)
    fingerprint_hash: Mapped[str] = mapped_column(String(64), unique=True)

    app_version_code: Mapped[int] = mapped_column(Integer, default=1, server_default=text("1"))
    locale: Mapped[str] = mapped_column(String(8), default="en", server_default=text("'en'"))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=_now)
    last_seen_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))

    # Free-tier quota: usage is charged inside the current cycle window.
    cycle_started_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    cycle_used_seconds: Mapped[float] = mapped_column(Float, default=0.0, server_default=text("0"))
    total_used_seconds: Mapped[float] = mapped_column(Float, default=0.0, server_default=text("0"))

    # VIP: unlimited usage until ``vip_expires_at``, or forever when ``vip_lifetime``.
    vip_expires_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    vip_lifetime: Mapped[bool] = mapped_column(Boolean, default=False, server_default=text("false"))

    is_blocked: Mapped[bool] = mapped_column(Boolean, default=False, server_default=text("false"))
    blocked_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    block_reason: Mapped[str | None] = mapped_column(String(255))
