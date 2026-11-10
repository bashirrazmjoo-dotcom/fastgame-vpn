"""xray servers (3x-ui provisioning)

Revision ID: 0004
Revises: 0003
"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa

revision: str = "0004"
down_revision: Union[str, None] = "0003"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.add_column("vpn_servers", sa.Column("kind", sa.String(16), nullable=False, server_default="openvpn"))
    op.add_column("vpn_servers", sa.Column("xui_url", sa.String(255)))
    op.add_column("vpn_servers", sa.Column("xui_user", sa.String(64)))
    op.add_column("vpn_servers", sa.Column("xui_password_enc", sa.Text()))
    op.add_column("vpn_servers", sa.Column("xui_inbound_id", sa.Integer()))


def downgrade() -> None:
    for c in ("xui_inbound_id", "xui_password_enc", "xui_user", "xui_url", "kind"):
        op.drop_column("vpn_servers", c)
