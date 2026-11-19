package com.example.model

enum class VpnConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED
}

enum class VpnProtocolMode(
    val displayName: String,
    val shortTag: String,
    val description: String
) {
    AUTO_FALLBACK(
        displayName = "Авто (urltest)",
        shortTag = "Auto-Fallback",
        description = "VLESS Reality → Hysteria 2 → CDN"
    ),
    VLESS_REALITY(
        displayName = "VLESS + Reality",
        shortTag = "Reality",
        description = "TCP 443 · XTLS-Vision · uTLS Chrome"
    ),
    HYSTERIA2(
        displayName = "Hysteria 2",
        shortTag = "Hysteria 2",
        description = "UDP/QUIC · Salamander Obfs"
    ),
    VLESS_CDN(
        displayName = "VLESS + CDN",
        shortTag = "CDN WS",
        description = "WebSocket + TLS 1.3 · Cloudflare"
    )
}

data class VpnServerNode(
    val id: String,
    val countryCode: String,
    val countryName: String,
    val city: String,
    val flagEmoji: String,
    val endpointHost: String,
    val port: Int = 443,
    val region: String,
    val loadPercent: Int,
    val defaultPingMs: Int,
    val measuredPingMs: Int? = null,
    val measuredBandwidthMbps: Double? = null,
    val supportsReality: Boolean = true,
    val supportsHysteria2: Boolean = true,
    val supportsCdn: Boolean = true,
    val uuid: String = "",
    val sni: String = "www.microsoft.com",
    val publicKey: String = "",
    val shortId: String = "",
    val hy2Password: String = "",
    val hy2ObfsPassword: String = "",
    val isCustom: Boolean = false
) {
    val effectivePingMs: Int
        get() = measuredPingMs ?: defaultPingMs
}

fun findFastestNode(nodes: List<VpnServerNode>): VpnServerNode? {
    return nodes.minWithOrNull(
        compareBy<VpnServerNode> { it.effectivePingMs }
            .thenByDescending { it.measuredBandwidthMbps ?: 0.0 }
            .thenBy { it.loadPercent }
    )
}

data class RoutingSettings(
    val bypassDomesticRu: Boolean = true,
    val bypassPrivateNetworks: Boolean = true,
    val blockDnsLeaksDoH: Boolean = true,
    val strictKillSwitch: Boolean = true,
    val mtu: Int = 1400,
    val excludedPackages: Set<String> = emptySet(),
    val primaryApiUrl: String = "https://api.shieldbox.example",
    val dohDiscoveryDomain: String = "_cfg.shieldbox.example"
)

data class VpnTelemetry(
    val connectionState: VpnConnectionState = VpnConnectionState.DISCONNECTED,
    val activeProtocolLabel: String = "Reality",
    val activeNodeId: String = "de-fra-1",
    val connectedSeconds: Long = 0L,
    val downloadBytesPerSec: Long = 0L,
    val uploadBytesPerSec: Long = 0L,
    val totalDownloadBytes: Long = 0L,
    val totalUploadBytes: Long = 0L,
    val livePingMs: Int? = null,
    val downloadHistory: List<Float> = List(20) { 0f },
    val uploadHistory: List<Float> = List(20) { 0f },
    val tunActive: Boolean = false,
    val errorMessage: String? = null
)

object DefaultNodes {
    val list: List<VpnServerNode> = listOf(
        VpnServerNode(
            id = "de-fra-1",
            countryCode = "DE",
            countryName = "Германия",
            city = "Франкфурт",
            flagEmoji = "🇩🇪",
            endpointHost = "198.51.100.10",
            region = "EU",
            loadPercent = 24,
            defaultPingMs = 38
        ),
        VpnServerNode(
            id = "nl-ams-1",
            countryCode = "NL",
            countryName = "Нидерланды",
            city = "Амстердам",
            flagEmoji = "🇳🇱",
            endpointHost = "198.51.100.20",
            region = "EU",
            loadPercent = 31,
            defaultPingMs = 42
        ),
        VpnServerNode(
            id = "fi-hel-1",
            countryCode = "FI",
            countryName = "Финляндия",
            city = "Хельсинки",
            flagEmoji = "🇫🇮",
            endpointHost = "198.51.100.30",
            region = "EU",
            loadPercent = 18,
            defaultPingMs = 29
        ),
        VpnServerNode(
            id = "ch-zrh-1",
            countryCode = "CH",
            countryName = "Швейцария",
            city = "Цюрих",
            flagEmoji = "🇨🇭",
            endpointHost = "198.51.100.35",
            region = "EU",
            loadPercent = 15,
            defaultPingMs = 44
        ),
        VpnServerNode(
            id = "us-nyc-1",
            countryCode = "US",
            countryName = "США",
            city = "Нью-Йорк",
            flagEmoji = "🇺🇸",
            endpointHost = "198.51.100.40",
            region = "US",
            loadPercent = 47,
            defaultPingMs = 112
        ),
        VpnServerNode(
            id = "sg-sin-1",
            countryCode = "SG",
            countryName = "Сингапур",
            city = "Сингапур",
            flagEmoji = "🇸🇬",
            endpointHost = "198.51.100.50",
            region = "ASIA",
            loadPercent = 29,
            defaultPingMs = 138
        )
    )
}

fun buildSingBoxPreviewJson(
    node: VpnServerNode,
    protocolMode: VpnProtocolMode,
    routing: RoutingSettings
): String {
    val activeTag = when (protocolMode) {
        VpnProtocolMode.AUTO_FALLBACK -> "auto-proxy"
        VpnProtocolMode.VLESS_REALITY -> "vless-reality"
        VpnProtocolMode.HYSTERIA2 -> "hysteria2"
        VpnProtocolMode.VLESS_CDN -> "vless-cdn"
    }
    val effectiveUuid = node.uuid.ifBlank { "CLIENT_UUID" }
    val effectivePbk = node.publicKey.ifBlank { "REALITY_PUBLIC_KEY" }
    val effectiveSid = node.shortId.ifBlank { "SHORT_ID" }
    val effectiveSni = node.sni.ifBlank { "www.microsoft.com" }
    val effectiveHy2Pass = node.hy2Password.ifBlank { "HY2_PASSWORD" }
    val excludedBlock = if (routing.excludedPackages.isNotEmpty()) {
        val listJson = routing.excludedPackages.sorted().joinToString(", ") { "\"$it\"" }
        ",\n      \"exclude_package\": [$listJson]"
    } else {
        ""
    }
    return """
{
  "log": { "level": "warn", "timestamp": true },
  "dns": {
    "servers": [
      { "tag": "dns-remote", "type": "https", "server": "1.1.1.1", "detour": "$activeTag" },
      { "tag": "dns-direct", "type": "udp", "server": "77.88.8.8", "detour": "direct" }
    ],
    "final": "${if (routing.blockDnsLeaksDoH) "dns-remote" else "dns-direct"}",
    "strategy": "prefer_ipv4"
  },
  "inbounds": [
    {
      "type": "tun",
      "tag": "tun-in",
      "address": ["172.19.0.1/30", "fdfe:dcba:9876::1/126"],
      "mtu": ${routing.mtu},
      "auto_route": true,
      "strict_route": ${routing.strictKillSwitch},
      "stack": "gvisor"$excludedBlock
    }
  ],
  "outbounds": [
    {
      "type": "urltest",
      "tag": "auto-proxy",
      "outbounds": ["vless-reality", "hysteria2", "vless-cdn"],
      "url": "https://www.gstatic.com/generate_204",
      "interval": "3m"
    },
    {
      "type": "vless",
      "tag": "vless-reality",
      "server": "${node.endpointHost}",
      "server_port": ${node.port},
      "uuid": "$effectiveUuid",
      "flow": "xtls-rprx-vision",
      "tls": {
        "enabled": true,
        "server_name": "$effectiveSni",
        "utls": { "enabled": true, "fingerprint": "chrome" },
        "reality": {
          "enabled": true,
          "public_key": "$effectivePbk",
          "short_id": "$effectiveSid"
        }
      }
    },
    {
      "type": "hysteria2",
      "tag": "hysteria2",
      "server": "${node.endpointHost}",
      "server_port": ${node.port},
      "password": "$effectiveHy2Pass",
      "obfs": {
        "type": "salamander",
        "password": "${node.hy2ObfsPassword.ifBlank { "HY2_OBFS_PASSWORD" }}"
      }
    },
    { "type": "direct", "tag": "direct" }
  ],
  "route": {
    "default_domain_resolver": "dns-direct",
    "rules": [
      { "action": "sniff" },
      { "protocol": "dns", "action": "hijack-dns" }${if (routing.bypassPrivateNetworks) ",\n      { \"ip_is_private\": true, \"action\": \"route\", \"outbound\": \"direct\" }" else ""}${if (routing.bypassDomesticRu) ",\n      { \"rule_set\": [\"geosite-category-ru\", \"geoip-ru\"], \"action\": \"route\", \"outbound\": \"direct\" }" else ""}
    ],
    "final": "$activeTag",
    "auto_detect_interface": true
  }
}
    """.trimIndent()
}
