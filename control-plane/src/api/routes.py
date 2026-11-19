from datetime import datetime, timedelta, timezone
from uuid import UUID
from fastapi import APIRouter, Depends, Header, HTTPException, Query, status
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from src.config import get_settings
from src.db.models import Node, User, UserDevice
from src.db.session import get_db
from src.schemas import (
    DeviceAuthRequest,
    DeviceAuthResponse,
    HysteriaAuthRequest,
    NodeCreateRequest,
    NodeResponse,
    UserStatsResponse,
)
from src.services.load_balancer import (
    mark_node_health,
    select_best_nodes,
    track_user_session,
)
from src.services.singbox_config import build_singbox_client_config
from src.services.xray_grpc import XrayGrpcClient

router = APIRouter(prefix="/api/v1")
settings = get_settings()


async def require_admin(x_admin_key: str = Header(...)) -> None:
    if x_admin_key != settings.admin_api_key:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Invalid admin key")


async def _provision_user_on_node(node: Node, user: User) -> bool:
    client = XrayGrpcClient(node.public_ip, node.grpc_port)
    ok_reality = await client.add_vless_user(
        inbound_tag=settings.xray_vless_inbound_tag,
        user_email=user.grpc_email,
        user_uuid=str(user.vpn_uuid),
        flow="xtls-rprx-vision",
    )
    if node.cdn_host:
        await client.add_vless_user(
            inbound_tag=settings.xray_cdn_inbound_tag,
            user_email=user.grpc_email,
            user_uuid=str(user.vpn_uuid),
            flow="",
        )
    return ok_reality


@router.post("/auth/device", response_model=DeviceAuthResponse)
async def authenticate_device(
    payload: DeviceAuthRequest,
    db: AsyncSession = Depends(get_db),
):
    """Register or authenticate a client device and enforce per-account device limits."""
    stmt = select(User).where(User.device_id == payload.device_id)
    user = (await db.execute(stmt)).scalar_one_or_none()

    if user is None:
        user = User(
            device_id=payload.device_id,
            email=payload.email,
            is_active=True,
            traffic_limit_bytes=settings.default_traffic_limit_bytes,
            expires_at=datetime.now(timezone.utc) + timedelta(days=settings.default_trial_days),
        )
        db.add(user)
        await db.flush()

    # Track/verify device fingerprint limit
    fp_stmt = select(UserDevice).where(
        UserDevice.user_id == user.id,
        UserDevice.device_fingerprint == payload.device_fingerprint,
    )
    existing_device = (await db.execute(fp_stmt)).scalar_one_or_none()
    if existing_device:
        existing_device.last_seen = datetime.now(timezone.utc)
    else:
        count_stmt = select(func.count(UserDevice.id)).where(UserDevice.user_id == user.id)
        device_count = (await db.execute(count_stmt)).scalar_one()
        if device_count >= settings.max_devices_per_user:
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail="Device limit reached for this account",
            )
        db.add(UserDevice(user_id=user.id, device_fingerprint=payload.device_fingerprint))

    await db.commit()
    await db.refresh(user)
    return user


@router.get("/config/{user_id}")
async def get_client_config(
    user_id: UUID,
    country: str | None = Query(default=None, max_length=5),
    split_country: str = Query(default="ru", max_length=5),
    db: AsyncSession = Depends(get_db),
):
    """
    Pick optimal VPN nodes via Redis load balancer, dynamically provision user via Xray gRPC
    without restarting nodes, and return a ready-to-run sing-box JSON configuration.
    """
    user = await db.get(User, user_id)
    if user is None:
        raise HTTPException(status_code=404, detail="User not found")

    now = datetime.now(timezone.utc)
    if not user.is_active or user.expires_at <= now or user.traffic_used_bytes >= user.traffic_limit_bytes:
        raise HTTPException(status_code=403, detail="Subscription expired or traffic limit exceeded")

    nodes = await select_best_nodes(db, country_code=country, limit=3)
    if not nodes:
        raise HTTPException(status_code=503, detail="No online VPN nodes available")

    provisioned_nodes: list[Node] = []
    for node in nodes:
        if await _provision_user_on_node(node, user):
            provisioned_nodes.append(node)

    if not provisioned_nodes:
        raise HTTPException(status_code=503, detail="Failed to provision user on available nodes")

    await track_user_session(str(user.id), provisioned_nodes[0].id)
    return build_singbox_client_config(
        user=user,
        nodes=provisioned_nodes,
        split_country=split_country,
    )


@router.get("/users/{user_id}/stats", response_model=UserStatsResponse)
async def get_user_stats(user_id: UUID, db: AsyncSession = Depends(get_db)):
    user = await db.get(User, user_id)
    if user is None:
        raise HTTPException(status_code=404, detail="User not found")

    dev_count = (
        await db.execute(select(func.count(UserDevice.id)).where(UserDevice.user_id == user.id))
    ).scalar_one()

    remaining = max(0, user.traffic_limit_bytes - user.traffic_used_bytes)
    return UserStatsResponse(
        user_id=user.id,
        is_active=user.is_active,
        traffic_limit_bytes=user.traffic_limit_bytes,
        traffic_used_bytes=user.traffic_used_bytes,
        traffic_remaining_bytes=remaining,
        expires_at=user.expires_at,
        active_devices=dev_count,
    )


@router.get("/nodes", response_model=list[NodeResponse])
async def list_online_nodes(db: AsyncSession = Depends(get_db)):
    result = await db.execute(
        select(Node).where(Node.is_online.is_(True)).order_by(Node.current_load.asc())
    )
    return list(result.scalars().all())


@router.post("/admin/nodes", response_model=NodeResponse, dependencies=[Depends(require_admin)])
async def register_node(payload: NodeCreateRequest, db: AsyncSession = Depends(get_db)):
    node = Node(
        name=payload.name,
        country_code=payload.country_code.upper(),
        public_ip=payload.public_ip,
        grpc_port=payload.grpc_port,
        reality_public_key=payload.reality_public_key,
        reality_short_id=payload.reality_short_id,
        reality_sni=payload.reality_sni,
        hysteria_port=payload.hysteria_port,
        hysteria_obfs_password=payload.hysteria_obfs_password,
        hysteria_sni=payload.hysteria_sni,
        hysteria_cert_sha256=payload.hysteria_cert_sha256,
        cdn_host=payload.cdn_host,
        cdn_ws_path=payload.cdn_ws_path,
        is_online=True,
        current_load=0,
    )
    db.add(node)
    await db.commit()
    await db.refresh(node)

    # Probe gRPC health immediately
    stats = await XrayGrpcClient(node.public_ip, node.grpc_port).get_sys_stats()
    node.is_online = stats is not None
    await db.commit()
    await mark_node_health(node.id, node.is_online, node.current_load)
    return node


@router.post("/admin/users/{user_id}/provision", dependencies=[Depends(require_admin)])
async def provision_user_everywhere(user_id: UUID, db: AsyncSession = Depends(get_db)):
    user = await db.get(User, user_id)
    if user is None:
        raise HTTPException(status_code=404, detail="User not found")

    nodes = (await db.execute(select(Node).where(Node.is_online.is_(True)))).scalars().all()
    results = {}
    for node in nodes:
        results[node.name] = await _provision_user_on_node(node, user)
    return {"user_id": str(user.id), "nodes": results}


@router.delete("/admin/users/{user_id}/revoke", dependencies=[Depends(require_admin)])
async def revoke_user_everywhere(user_id: UUID, db: AsyncSession = Depends(get_db)):
    user = await db.get(User, user_id)
    if user is None:
        raise HTTPException(status_code=404, detail="User not found")

    user.is_active = False
    await db.commit()

    nodes = (await db.execute(select(Node).where(Node.is_online.is_(True)))).scalars().all()
    revoked = {}
    for node in nodes:
        client = XrayGrpcClient(node.public_ip, node.grpc_port)
        ok_r = await client.remove_user(settings.xray_vless_inbound_tag, user.grpc_email)
        if node.cdn_host:
            await client.remove_user(settings.xray_cdn_inbound_tag, user.grpc_email)
        revoked[node.name] = ok_r
    return {"user_id": str(user.id), "revoked": revoked}


@router.post("/internal/hy2/auth")
async def hysteria2_http_auth(payload: HysteriaAuthRequest, db: AsyncSession = Depends(get_db)):
    """
    HTTP authentication webhook for Hysteria 2 server nodes (`auth.type: http`).
    Allows zero-restart user validation using `vpn_uuid` as password.
    """
    try:
        vpn_uuid = UUID(payload.auth.strip())
    except ValueError:
        return {"ok": False, "id": ""}

    stmt = select(User).where(User.vpn_uuid == vpn_uuid)
    user = (await db.execute(stmt)).scalar_one_or_none()
    now = datetime.now(timezone.utc)
    if (
        user is None
        or not user.is_active
        or user.expires_at <= now
        or user.traffic_used_bytes >= user.traffic_limit_bytes
    ):
        return {"ok": False, "id": ""}

    return {"ok": True, "id": str(user.id)}
