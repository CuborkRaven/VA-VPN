from datetime import datetime
import uuid
from sqlalchemy import (
    BigInteger,
    Boolean,
    DateTime,
    ForeignKey,
    Integer,
    String,
    UniqueConstraint,
    func,
    text,
)
from sqlalchemy.dialects.postgresql import UUID
from sqlalchemy.orm import Mapped, mapped_column, relationship
from src.db.session import Base


class User(Base):
    __tablename__ = "users"

    id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True),
        primary_key=True,
        server_default=text("gen_random_uuid()"),
        default=uuid.uuid4,
    )
    email: Mapped[str | None] = mapped_column(String(255), unique=True, nullable=True)
    device_id: Mapped[str | None] = mapped_column(String(255), unique=True, nullable=True)
    vpn_uuid: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True),
        unique=True,
        nullable=False,
        server_default=text("gen_random_uuid()"),
        default=uuid.uuid4,
    )
    is_active: Mapped[bool] = mapped_column(Boolean, default=True, server_default=text("TRUE"))
    traffic_limit_bytes: Mapped[int] = mapped_column(
        BigInteger, default=107_374_182_400, server_default=text("107374182400")
    )
    traffic_used_bytes: Mapped[int] = mapped_column(
        BigInteger, default=0, server_default=text("0")
    )
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now()
    )

    devices: Mapped[list["UserDevice"]] = relationship(
        back_populates="user", cascade="all, delete-orphan"
    )

    @property
    def grpc_email(self) -> str:
        """Stable identifier used inside Xray-core HandlerService & StatsService."""
        return self.email or f"{self.vpn_uuid}@shieldbox.local"


class Node(Base):
    __tablename__ = "nodes"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    name: Mapped[str] = mapped_column(String(100), nullable=False)
    country_code: Mapped[str] = mapped_column(String(5), nullable=False, index=True)
    public_ip: Mapped[str] = mapped_column(String(45), nullable=False)
    grpc_port: Mapped[int] = mapped_column(Integer, default=10085, server_default=text("10085"))

    # VLESS + XTLS-Reality
    reality_public_key: Mapped[str] = mapped_column(String(255), nullable=False)
    reality_short_id: Mapped[str] = mapped_column(String(32), nullable=False)
    reality_sni: Mapped[str] = mapped_column(
        String(255), default="www.microsoft.com", server_default=text("'www.microsoft.com'")
    )

    # Hysteria 2
    hysteria_port: Mapped[int] = mapped_column(Integer, default=443, server_default=text("443"))
    hysteria_obfs_password: Mapped[str | None] = mapped_column(String(255), nullable=True)
    hysteria_sni: Mapped[str] = mapped_column(
        String(255), default="bing.com", server_default=text("'bing.com'")
    )
    hysteria_cert_sha256: Mapped[str | None] = mapped_column(String(128), nullable=True)

    # Fallback 2: VLESS + WebSocket + TLS + CDN (Cloudflare)
    cdn_host: Mapped[str | None] = mapped_column(String(255), nullable=True)
    cdn_ws_path: Mapped[str] = mapped_column(
        String(128), default="/ws-tunnel", server_default=text("'/ws-tunnel'")
    )

    is_online: Mapped[bool] = mapped_column(Boolean, default=True, server_default=text("TRUE"))
    current_load: Mapped[int] = mapped_column(Integer, default=0, server_default=text("0"))


class UserDevice(Base):
    __tablename__ = "user_devices"
    __table_args__ = (UniqueConstraint("user_id", "device_fingerprint", name="uq_user_device_fp"),)

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    user_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("users.id", ondelete="CASCADE"), nullable=False
    )
    device_fingerprint: Mapped[str] = mapped_column(String(255), nullable=False)
    last_seen: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), onupdate=func.now()
    )

    user: Mapped[User] = relationship(back_populates="devices")
