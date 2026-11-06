from datetime import datetime

from sqlalchemy import Boolean, DateTime, Integer, String, Text, text
from sqlalchemy.orm import Mapped, mapped_column

from app.core import clock
from app.core.db import Base


def _now() -> datetime:
    return clock.utcnow()


class VpnServer(Base):
    """An existing OpenVPN server. FastGame only reads its connection profile."""

    __tablename__ = "vpn_servers"

    id: Mapped[int] = mapped_column(primary_key=True)
    name: Mapped[str] = mapped_column(String(64))
    country_code: Mapped[str] = mapped_column(String(2))
    country_name_en: Mapped[str] = mapped_column(String(64))
    country_name_fa: Mapped[str] = mapped_column(String(64))

    # Shown in the admin panel and used for health checks.
    host: Mapped[str] = mapped_column(String(255))
    port: Mapped[int] = mapped_column(Integer)
    proto: Mapped[str] = mapped_column(String(8))

    # Full .ovpn profile handed to the app when a session starts.
    ovpn_config: Mapped[str] = mapped_column(Text)
    auth_username: Mapped[str | None] = mapped_column(String(128))
    auth_password_enc: Mapped[str | None] = mapped_column(Text)  # encrypted, see security.encrypt_secret

    # Optional MikroTik RouterOS 7 REST access. When set, every session gets its own short-lived
    # PPP account on the router instead of the shared credentials above.
    mt_api_url: Mapped[str | None] = mapped_column(String(255))  # https://host:port
    mt_api_user: Mapped[str | None] = mapped_column(String(64))
    mt_api_password_enc: Mapped[str | None] = mapped_column(Text)
    mt_ppp_profile: Mapped[str | None] = mapped_column(String(64))
    mt_ca_pem: Mapped[str | None] = mapped_column(Text)  # pinned (self-signed) certificate, public part

    is_active: Mapped[bool] = mapped_column(Boolean, default=True, server_default=text("true"))
    sort_order: Mapped[int] = mapped_column(Integer, default=0, server_default=text("0"))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=_now)
