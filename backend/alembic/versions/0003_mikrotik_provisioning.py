"""per-session MikroTik accounts

Revision ID: 0003
Revises: 0002
"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa

revision: str = "0003"
down_revision: Union[str, None] = "0002"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.add_column("vpn_servers", sa.Column("mt_api_url", sa.String(255)))
    op.add_column("vpn_servers", sa.Column("mt_api_user", sa.String(64)))
    op.add_column("vpn_servers", sa.Column("mt_api_password_enc", sa.Text()))
    op.add_column("vpn_servers", sa.Column("mt_ppp_profile", sa.String(64)))
    op.add_column("vpn_servers", sa.Column("mt_ca_pem", sa.Text()))
    op.add_column("vpn_sessions", sa.Column("revoked_at", sa.DateTime(timezone=True)))
    op.create_index(
        "ix_vpn_sessions_pending_revoke",
        "vpn_sessions",
        ["ended_at"],
        postgresql_where=sa.text("ended_at IS NOT NULL AND revoked_at IS NULL"),
    )


def downgrade() -> None:
    op.drop_index("ix_vpn_sessions_pending_revoke", table_name="vpn_sessions")
    op.drop_column("vpn_sessions", "revoked_at")
    for c in ("mt_ca_pem", "mt_ppp_profile", "mt_api_password_enc", "mt_api_user", "mt_api_url"):
        op.drop_column("vpn_servers", c)
