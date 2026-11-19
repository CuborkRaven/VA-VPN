from src.db.models import Node, User


def build_singbox_client_config(
    user: User,
    nodes: list[Node],
    split_country: str = "ru",
) -> dict:
    """
    Generate client-side sing-box (v1.12 / v1.13+) JSON configuration with:
      - urltest automatic fallback across VLESS+Reality, Hysteria 2, and VLESS+WS+TLS+CDN
      - GeoIP/GeoSite (.srs rule_set) Split Tunneling
      - DNS leak prevention (remote DNS via proxy, domestic DNS direct)
    """
    user_uuid = str(user.vpn_uuid)
    country = split_country.lower()

    outbounds: list[dict] = []
    fallback_tags: list[str] = []

    for node in nodes:
        prefix = f"n{node.id}-{node.country_code.lower()}"

        # 1. Primary: VLESS + XTLS-Reality
        reality_tag = f"{prefix}-vless-reality"
        outbounds.append(
            {
                "type": "vless",
                "tag": reality_tag,
                "server": node.public_ip,
                "server_port": 443,
                "uuid": user_uuid,
                "flow": "xtls-rprx-vision",
                "tls": {
                    "enabled": True,
                    "server_name": node.reality_sni,
                    "utls": {
                        "enabled": True,
                        "fingerprint": "chrome",
                    },
                    "reality": {
                        "enabled": True,
                        "public_key": node.reality_public_key,
                        "short_id": node.reality_short_id,
                    },
                },
            }
        )
        fallback_tags.append(reality_tag)

        # 2. Fallback 1: Hysteria 2 (QUIC/UDP + Salamander obfs)
        hy2_tag = f"{prefix}-hysteria2"
        hy2_tls: dict = {
            "enabled": True,
            "server_name": node.hysteria_sni,
            "alpn": ["h3"],
        }
        if node.hysteria_cert_sha256:
            hy2_tls["certificate_public_key_sha256"] = [node.hysteria_cert_sha256]
        else:
            hy2_tls["insecure"] = True

        hy2_outbound: dict = {
            "type": "hysteria2",
            "tag": hy2_tag,
            "server": node.public_ip,
            "server_port": node.hysteria_port,
            "password": user_uuid,
            "up_mbps": 100,
            "down_mbps": 200,
            "tls": hy2_tls,
        }
        if node.hysteria_obfs_password:
            hy2_outbound["obfs"] = {
                "type": "salamander",
                "password": node.hysteria_obfs_password,
            }
        outbounds.append(hy2_outbound)
        fallback_tags.append(hy2_tag)

        # 3. Fallback 2: VLESS + WebSocket + TLS + CDN (Cloudflare)
        if node.cdn_host:
            cdn_tag = f"{prefix}-vless-cdn"
            outbounds.append(
                {
                    "type": "vless",
                    "tag": cdn_tag,
                    "server": node.cdn_host,
                    "server_port": 443,
                    "uuid": user_uuid,
                    "transport": {
                        "type": "ws",
                        "path": node.cdn_ws_path,
                        "headers": {
                            "Host": node.cdn_host,
                        },
                        "max_early_data": 2048,
                        "early_data_header_name": "Sec-WebSocket-Protocol",
                    },
                    "tls": {
                        "enabled": True,
                        "server_name": node.cdn_host,
                        "alpn": ["http/1.1"],
                        "utls": {
                            "enabled": True,
                            "fingerprint": "chrome",
                        },
                    },
                }
            )
            fallback_tags.append(cdn_tag)

    # Put urltest + selector at the top of outbounds
    header_outbounds = [
        {
            "type": "selector",
            "tag": "proxy",
            "outbounds": ["auto-proxy", *fallback_tags, "direct"],
            "default": "auto-proxy",
        },
        {
            "type": "urltest",
            "tag": "auto-proxy",
            "outbounds": fallback_tags,
            "url": "https://www.gstatic.com/generate_204",
            "interval": "3m",
            "tolerance": 80,
            "interrupt_exist_connections": False,
        },
    ]

    footer_outbounds = [
        {
            "type": "direct",
            "tag": "direct",
        }
    ]

    return {
        "log": {
            "level": "warn",
            "timestamp": True,
        },
        "dns": {
            "servers": [
                {
                    "tag": "dns-remote",
                    "type": "https",
                    "server": "1.1.1.1",
                    "detour": "proxy",
                },
                {
                    "tag": "dns-direct",
                    "type": "udp",
                    "server": "77.88.8.8",
                    "server_port": 53,
                    "detour": "direct",
                },
            ],
            "rules": [
                {
                    "rule_set": [f"geosite-{country}"],
                    "server": "dns-direct",
                }
            ],
            "final": "dns-remote",
            "strategy": "prefer_ipv4",
            "independent_cache": True,
        },
        "inbounds": [
            {
                "type": "tun",
                "tag": "tun-in",
                "address": [
                    "172.19.0.1/30",
                    "fdfe:dcba:9876::1/126",
                ],
                "mtu": 1400,
                "auto_route": True,
                "strict_route": True,
                "stack": "gvisor",
            },
            {
                "type": "mixed",
                "tag": "mixed-in",
                "listen": "127.0.0.1",
                "listen_port": 2080,
            },
        ],
        "outbounds": [*header_outbounds, *outbounds, *footer_outbounds],
        "route": {
            "default_domain_resolver": "dns-direct",
            "rule_set": [
                {
                    "tag": f"geosite-{country}",
                    "type": "remote",
                    "format": "binary",
                    "url": f"https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-category-{country}.srs",
                    "download_detour": "proxy",
                    "update_interval": "1d",
                },
                {
                    "tag": f"geoip-{country}",
                    "type": "remote",
                    "format": "binary",
                    "url": f"https://raw.githubusercontent.com/SagerNet/sing-geoip/rule-set/geoip-{country}.srs",
                    "download_detour": "proxy",
                    "update_interval": "1d",
                },
                {
                    "tag": "geosite-private",
                    "type": "remote",
                    "format": "binary",
                    "url": "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-private.srs",
                    "download_detour": "direct",
                    "update_interval": "7d",
                },
            ],
            "rules": [
                {
                    "action": "sniff",
                },
                {
                    "protocol": "dns",
                    "action": "hijack-dns",
                },
                {
                    "ip_is_private": True,
                    "action": "route",
                    "outbound": "direct",
                },
                {
                    "rule_set": [
                        "geosite-private",
                        f"geosite-{country}",
                        f"geoip-{country}",
                    ],
                    "action": "route",
                    "outbound": "direct",
                },
            ],
            "final": "proxy",
            "auto_detect_interface": True,
        },
        "experimental": {
            "cache_file": {
                "enabled": True,
                "path": "cache.db",
            }
        },
    }
