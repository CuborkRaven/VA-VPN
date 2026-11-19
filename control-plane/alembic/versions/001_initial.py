"""Initial users, nodes, user_devices schema

Revision ID: 001_initial
Revises:
Create Date: 2026-11-19
"""
from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

revision = "001_initial"
down_revision = None
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.execute('CREATE EXTENSION IF NOT EXISTS "pgcrypto"')

    op.create_table(
        "users",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True, server_default=sa.text("gen_random_uuid()")),
        sa.Column("email", sa.String(255), unique=True, nullable=True),
        sa.Column("device_id", sa.String(255), unique=True, nullable=True),
        sa.Column("vpn_uuid", postgresql.UUID(as_uuid=True), unique=True, nullable=False, server_default=sa.text("gen_random_uuid()")),
        sa.Column("is_active", sa.Boolean(), server_default=sa.text("TRUE"), nullable=False),
        sa.Column("traffic_limit_bytes", sa.BigInteger(), server_default=sa.text("107374182400"), nullable=False),
        sa.Column("traffic_used_bytes", sa.BigInteger(), server_default=sa.text("0"), nullable=False),
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.text("NOW()"), nullable=False),
    )

    op.create_table(
        "nodes",
        sa.Column("id", sa.Integer(), primary_key=True, autoincrement=True),
        sa.Column("name", sa.String(100), nullable=False),
        sa.Column("country_code", sa.String(5), nullable=False),
        sa.Column("public_ip", sa.String(45), nullable=False),
        sa.Column("grpc_port", sa.Integer(), server_default=sa.text("10085"), nullable=False),
        sa.Column("reality_public_key", sa.String(255), nullable=False),
        sa.Column("reality_short_id", sa.String(32), nullable=False),
        sa.Column("reality_sni", sa.String(255), server_default=sa.text("'www.microsoft.com'"), nullable=False),
        sa.Column("hysteria_port", sa.Integer(), server_default=sa.text("443"), nullable=False),
        sa.Column("hysteria_obfs_password", sa.String(255), nullable=True),
        sa.Column("hysteria_sni", sa.String(255), server_default=sa.text("'bing.com'"), nullable=False),
        sa.Column("hysteria_cert_sha256", sa.String(128), nullable=True),
        sa.Column("cdn_host", sa.String(255), nullable=True),
        sa.Column("cdn_ws_path", sa.String(128), server_default=sa.text("'/ws-tunnel'"), nullable=False),
        sa.Column("is_online", sa.Boolean(), server_default=sa.text("TRUE"), nullable=False),
        sa.Column("current_load", sa.Integer(), server_default=sa.text("0"), nullable=False),
    )
    op.create_index("ix_nodes_country_online", "nodes", ["country_code", "is_online", "current_load"])

    op.create_table(
        "user_devices",
        sa.Column("id", sa.Integer(), primary_key=True, autoincrement=True),
        sa.Column("user_id", postgresql.UUID(as_uuid=True), sa.ForeignKey("users.id", ondelete="CASCADE"), nullable=False),
        sa.Column("device_fingerprint", sa.String(255), nullable=False),
        sa.Column("last_seen", sa.DateTime(timezone=True), server_default=sa.text("NOW()"), nullable=False),
        sa.UniqueConstraint("user_id", "device_fingerprint", name="uq_user_device_fp"),
    )


def downgrade() -> None:
    op.drop_table("user_devices")
    op.drop_index("ix_nodes_country_online", table_name="nodes")
    op.drop_table("nodes")
    op.drop_table("users")
