from functools import lru_cache
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    database_url: str = "postgresql+asyncpg://shieldbox:change_me_strong_password@postgres:5432/shieldbox"
    redis_url: str = "redis://redis:6379/0"

    admin_api_key: str = "replace_with_random_64_char_secret"
    default_traffic_limit_bytes: int = 107_374_182_400  # 100 GiB
    default_trial_days: int = 30
    max_devices_per_user: int = 5
    sync_interval_seconds: int = 30

    xray_vless_inbound_tag: str = "vless-reality-in"
    xray_cdn_inbound_tag: str = "vless-ws-in"
    grpc_timeout_seconds: float = 5.0


@lru_cache
def get_settings() -> Settings:
    return Settings()
