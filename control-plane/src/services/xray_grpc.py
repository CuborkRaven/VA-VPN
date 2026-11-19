import logging
import grpc
from src.config import get_settings

logger = logging.getLogger(__name__)
settings = get_settings()

# Compiled stubs are generated in /app/app/grpc_gen during Docker build
from app.proxyman.command import command_pb2 as proxyman_pb2  # type: ignore
from app.proxyman.command import command_pb2_grpc as proxyman_grpc  # type: ignore
from app.stats.command import command_pb2 as stats_pb2  # type: ignore
from app.stats.command import command_pb2_grpc as stats_grpc  # type: ignore
from common.protocol import user_pb2  # type: ignore
from common.serial import typed_message_pb2  # type: ignore
from proxy.vless import account_pb2 as vless_pb2  # type: ignore


def _to_typed_message(type_name: str, msg) -> typed_message_pb2.TypedMessage:
    return typed_message_pb2.TypedMessage(
        type=type_name,
        value=msg.SerializeToString(),
    )


class XrayGrpcClient:
    """Async gRPC client for Xray-core HandlerService & StatsService."""

    def __init__(self, host: str, port: int, timeout: float | None = None):
        self.target = f"{host}:{port}"
        self.timeout = timeout or settings.grpc_timeout_seconds

    async def add_vless_user(
        self,
        inbound_tag: str,
        user_email: str,
        user_uuid: str,
        flow: str = "xtls-rprx-vision",
        level: int = 0,
    ) -> bool:
        """Dynamically add a VLESS user to a running Xray inbound without restart."""
        account = vless_pb2.Account(
            id=str(user_uuid),
            flow=flow,
            encryption="none",
        )
        proto_user = user_pb2.User(
            level=level,
            email=user_email,
            account=_to_typed_message("xray.proxy.vless.Account", account),
        )
        op = proxyman_pb2.AddUserOperation(user=proto_user)
        req = proxyman_pb2.AlterInboundRequest(
            tag=inbound_tag,
            operation=_to_typed_message(
                "xray.app.proxyman.command.AddUserOperation", op
            ),
        )

        async with grpc.aio.insecure_channel(self.target) as channel:
            stub = proxyman_grpc.HandlerServiceStub(channel)
            try:
                await stub.AlterInbound(req, timeout=self.timeout)
                return True
            except grpc.aio.AioRpcError as exc:
                details = (exc.details() or "").lower()
                if "already exists" in details:
                    return True
                logger.warning(
                    "Xray gRPC add_user failed on %s (%s): %s",
                    self.target,
                    inbound_tag,
                    exc.details(),
                )
                return False

    async def remove_user(self, inbound_tag: str, user_email: str) -> bool:
        """Dynamically revoke a user from a running Xray inbound."""
        op = proxyman_pb2.RemoveUserOperation(email=user_email)
        req = proxyman_pb2.AlterInboundRequest(
            tag=inbound_tag,
            operation=_to_typed_message(
                "xray.app.proxyman.command.RemoveUserOperation", op
            ),
        )

        async with grpc.aio.insecure_channel(self.target) as channel:
            stub = proxyman_grpc.HandlerServiceStub(channel)
            try:
                await stub.AlterInbound(req, timeout=self.timeout)
                return True
            except grpc.aio.AioRpcError as exc:
                details = (exc.details() or "").lower()
                if "not found" in details:
                    return True
                logger.warning(
                    "Xray gRPC remove_user failed on %s (%s): %s",
                    self.target,
                    inbound_tag,
                    exc.details(),
                )
                return False

    async def query_all_user_traffic(self, reset: bool = True) -> dict[str, int]:
        """
        Pull per-user uplink + downlink byte deltas from Xray StatsService.
        Stat name format: user>>><email>>>>traffic>>>uplink|downlink
        """
        req = stats_pb2.QueryStatsRequest(
            pattern="user>>>",
            reset=reset,
        )
        usage: dict[str, int] = {}

        async with grpc.aio.insecure_channel(self.target) as channel:
            stub = stats_grpc.StatsServiceStub(channel)
            resp = await stub.QueryStats(req, timeout=self.timeout)
            for stat in resp.stat:
                parts = stat.name.split(">>>")
                if len(parts) == 4 and parts[0] == "user" and parts[2] == "traffic":
                    email = parts[1]
                    usage[email] = usage.get(email, 0) + int(stat.value)
        return usage

    async def get_sys_stats(self) -> dict[str, int] | None:
        """Check node responsiveness and live goroutine/uptime stats."""
        async with grpc.aio.insecure_channel(self.target) as channel:
            stub = stats_grpc.StatsServiceStub(channel)
            try:
                resp = await stub.GetSysStats(
                    stats_pb2.SysStatsRequest(), timeout=self.timeout
                )
                return {
                    "num_goroutine": resp.NumGoroutine,
                    "uptime": resp.Uptime,
                    "alloc": resp.Alloc,
                }
            except grpc.aio.AioRpcError:
                return None
