import uuid
from datetime import datetime

from sqlalchemy import Boolean, DateTime, Float, ForeignKey, Index, String, text
from sqlalchemy.orm import Mapped, mapped_column

from app.core.db import Base


class VpnSession(Base):
    """One connected period. ``counted_seconds`` is what the server credited."""

    __tablename__ = "vpn_sessions"
    __table_args__ = (
        # At most one open session per device, enforced by the database itself.
        Index(
            "uq_vpn_sessions_one_open_per_device",
            "device_id",
            unique=True,
            postgresql_where=text("ended_at IS NULL"),
        ),
        Index(
            "ix_vpn_sessions_open_heartbeat",
            "last_heartbeat_at",
            postgresql_where=text("ended_at IS NULL"),
        ),
    )

    id: Mapped[uuid.UUID] = mapped_column(primary_key=True, default=uuid.uuid4)
    device_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("devices.id", ondelete="CASCADE"), index=True)
    server_id: Mapped[int] = mapped_column(ForeignKey("vpn_servers.id", ondelete="RESTRICT"), index=True)

    started_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    last_heartbeat_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    # Set when the app confirms the tunnel is up. Nothing is charged before that.
    connected_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    ended_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    end_reason: Mapped[str | None] = mapped_column(String(24))  # user|quota_exhausted|timeout|replaced|blocked

    # Set once the per-session router account has been removed (only for MikroTik servers).
    revoked_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))

    counted_seconds: Mapped[float] = mapped_column(Float, default=0.0, server_default=text("0"))
    was_vip: Mapped[bool] = mapped_column(Boolean, default=False, server_default=text("false"))
