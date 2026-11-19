import asyncio
import logging
from datetime import datetime, timezone
from sqlalchemy import select, update
from src.config import get_settings
from src.db.models import Node, User
from src.db.session import AsyncSessionLocal
from src.services.load_balancer import mark_node_health
from src.services.xray_grpc import XrayGrpcClient

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
)
logger = logging.getLogger("sync_worker")
settings = get_settings()


async def sync_nodes_and_traffic() -> None:
    async with AsyncSessionLocal() as db:
        nodes = list((await db.execute(select(Node))).scalars().all())
        traffic_deltas_by_email: dict[str, int] = {}

        for node in nodes:
            client = XrayGrpcClient(node.public_ip, node.grpc_port)
            sys_stats = await client.get_sys_stats()
            if sys_stats is None:
                node.is_online = False
                await mark_node_health(node.id, False, node.current_load)
                continue

            node.is_online = True
            # Use active goroutines as lightweight load proxy
            node.current_load = int(sys_stats.get("num_goroutine", 0))
            await mark_node_health(node.id, True, node.current_load)

            try:
                node_usage = await client.query_all_user_traffic(reset=True)
                for email, delta in node_usage.items():
                    if delta > 0:
                        traffic_deltas_by_email[email] = traffic_deltas_by_email.get(email, 0) + delta
            except Exception as exc:
                logger.warning("Failed to query stats on node %s: %s", node.name, exc)

        # Apply traffic deltas in PostgreSQL
        if traffic_deltas_by_email:
            users = list((await db.execute(select(User).where(User.is_active.is_(True)))).scalars().all())
            email_to_user = {u.grpc_email: u for u in users}
            for email, delta in traffic_deltas_by_email.items():
                user = email_to_user.get(email)
                if user:
                    await db.execute(
                        update(User)
                        .where(User.id == user.id)
                        .values(traffic_used_bytes=User.traffic_used_bytes + delta)
                    )

        await db.commit()

        # Enforce traffic limits & subscription expiration across all online nodes
        now = datetime.now(timezone.utc)
        expired_stmt = select(User).where(
            User.is_active.is_(True),
            (User.expires_at <= now) | (User.traffic_used_bytes >= User.traffic_limit_bytes),
        )
        to_revoke = list((await db.execute(expired_stmt)).scalars().all())

        if to_revoke:
            online_nodes = [n for n in nodes if n.is_online]
            for user in to_revoke:
                user.is_active = False
                for node in online_nodes:
                    client = XrayGrpcClient(node.public_ip, node.grpc_port)
                    await client.remove_user(settings.xray_vless_inbound_tag, user.grpc_email)
                    if node.cdn_host:
                        await client.remove_user(settings.xray_cdn_inbound_tag, user.grpc_email)
                logger.info("Revoked expired/over-quota user %s", user.id)

            await db.commit()


async def main_loop() -> None:
    logger.info("Starting Control Plane sync worker (interval=%ss)", settings.sync_interval_seconds)
    while True:
        try:
            await sync_nodes_and_traffic()
        except Exception as exc:
            logger.exception("Sync cycle error: %s", exc)
        await asyncio.sleep(settings.sync_interval_seconds)


if __name__ == "__main__":
    asyncio.run(main_loop())
