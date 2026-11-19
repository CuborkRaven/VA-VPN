from fastapi import FastAPI
from src.api.routes import router as api_router
from src.db.redis import redis_client

app = FastAPI(
    title="ShieldBox Control Plane",
    version="1.0.0",
    docs_url="/docs",
)

app.include_router(api_router)


@app.get("/healthz")
async def healthz():
    pong = await redis_client.ping()
    return {"status": "ok", "redis": bool(pong)}
