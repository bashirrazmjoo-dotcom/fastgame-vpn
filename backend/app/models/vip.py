import uuid
from datetime import datetime

from sqlalchemy import Boolean, DateTime, ForeignKey, Integer, String, text
from sqlalchemy.orm import Mapped, mapped_column

from app.core import clock
from app.core.db import Base


def _now() -> datetime:
    return clock.utcnow()


class VipEvent(Base):
    """Audit trail of every VIP change made by an admin."""

    __tablename__ = "vip_events"

    id: Mapped[int] = mapped_column(primary_key=True)
    device_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("devices.id", ondelete="CASCADE"), index=True)
    action: Mapped[str] = mapped_column(String(16))  # activate|extend|cancel
    days: Mapped[int | None] = mapped_column(Integer)
    lifetime: Mapped[bool] = mapped_column(Boolean, default=False, server_default=text("false"))
    expires_at_after: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    admin_username: Mapped[str] = mapped_column(String(64))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=_now)
