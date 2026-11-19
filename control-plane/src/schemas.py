from datetime import datetime
from uuid import UUID
from pydantic import BaseModel, EmailStr, Field


class DeviceAuthRequest(BaseModel):
    device_id: str = Field(..., min_length=6, max_length=255)
    device_fingerprint: str = Field(..., min_length=6, max_length=255)
    email: EmailStr | None = None


class DeviceAuthResponse(BaseModel):
    user_id: UUID
    vpn_uuid: UUID
    is_active: bool
    expires_at: datetime
    traffic_limit_bytes: int
    traffic_used_bytes: int


class NodeCreateRequest(BaseModel):
    name: str = Field(..., max_length=100)
    country_code: str = Field(..., min_length=2, max_length=5)
    public_ip: str = Field(..., max_length=45)
    grpc_port: int = 10085
    reality_public_key: str
    reality_short_id: str
    reality_sni: str = "www.microsoft.com"
    hysteria_port: int = 443
    hysteria_obfs_password: str | None = None
    hysteria_sni: str = "bing.com"
    hysteria_cert_sha256: str | None = None
    cdn_host: str | None = None
    cdn_ws_path: str = "/ws-tunnel"


class NodeResponse(BaseModel):
    id: int
    name: str
    country_code: str
    public_ip: str
    is_online: bool
    current_load: int

    model_config = {"from_attributes": True}


class UserStatsResponse(BaseModel):
    user_id: UUID
    is_active: bool
    traffic_limit_bytes: int
    traffic_used_bytes: int
    traffic_remaining_bytes: int
    expires_at: datetime
    active_devices: int


class HysteriaAuthRequest(BaseModel):
    addr: str
    auth: str
    tx: int = 0
