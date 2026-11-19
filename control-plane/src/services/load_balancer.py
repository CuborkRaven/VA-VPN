from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession
from src.db.models import Node
from src.db.redis import redis_client

NODE_ONLINE_HASH = "shieldbox:nodes:online"
NODE_LOAD_ZSET = "shieldbox:nodes:load"
USER_ONLINE_PREFIX = "shieldbox:user:online:"


async def mark_node_health(node_id: int, is_online: bool, load_score: int) -> None:
    if is_online:
        await redis_client.hset(NODE_ONLINE_HASH, str(node_id), "1")
        await redis_client.zadd(NODE_LOAD_ZSET, {str(node_id): load_score})
    else:
        await redis_client.hdel(NODE_ONLINE_HASH, str(node_id))
        await redis_client.zrem(NODE_LOAD_ZSET, str(node_id))


async def track_user_session(user_id: str, node_id: int, ttl_seconds: int = 120) -> None:
    """Track active user session in Redis with short TTL for online estimation."""
    key = f"{USER_ONLINE_PREFIX}{user_id}"
    await redis_client.setex(key, ttl_seconds, str(node_id))


async def select_best_nodes(
    db: AsyncSession,
    country_code: str | None = None,
    limit: int = 3,
) -> list[Node]:
    """
    Pick best online nodes using Redis load scores first, falling back to PostgreSQL `current_load`.
    Returns up to `limit` nodes for multi-node fallback in client urltest.
    """
    stmt = select(Node).where(Node.is_online.is_(True))
    if country_code:
        stmt = stmt.where(Node.country_code == country_code.upper())

    result = await db.execute(stmt.order_by(Node.current_load.asc()))
    nodes = list(result.scalars().all())
    if not nodes:
        return []

    redis_scores = await redis_client.zrange(NODE_LOAD_ZSET, 0, -1, withscores=True)
    score_map = {int(member): score for member, score in redis_scores}

    nodes.sort(key=lambda n: score_map.get(n.id, float(n.current_load)))
    return nodes[:limit]
