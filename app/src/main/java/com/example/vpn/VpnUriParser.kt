package com.example.vpn

import android.net.Uri
import android.util.Base64
import com.example.model.VpnServerNode
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.util.UUID

object VpnUriParser {

    fun parseInput(rawInput: String): List<VpnServerNode> {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return emptyList()

        val candidates = if (
            trimmed.startsWith("vless://", ignoreCase = true) ||
            trimmed.startsWith("hy2://", ignoreCase = true) ||
            trimmed.startsWith("hysteria2://", ignoreCase = true)
        ) {
            trimmed.lines().map { it.trim() }.filter { it.isNotEmpty() }
        } else {
            val decoded = runCatching {
                val normalized = trimmed.replace('-', '+').replace('_', '/')
                String(Base64.decode(normalized, Base64.DEFAULT), Charsets.UTF_8)
            }.getOrNull()

            if (decoded != null && (
                    decoded.contains("vless://", ignoreCase = true) ||
                        decoded.contains("hy2://", ignoreCase = true) ||
                        decoded.contains("hysteria2://", ignoreCase = true)
                    )
            ) {
                decoded.lines().map { it.trim() }.filter { it.isNotEmpty() }
            } else {
                trimmed.lines().map { it.trim() }.filter { it.isNotEmpty() }
            }
        }

        return candidates.mapNotNull { parseSingleUri(it) }
    }

    fun parseSingleUri(uriString: String): VpnServerNode? {
        val lower = uriString.lowercase()
        return when {
            lower.startsWith("vless://") -> parseVless(uriString)
            lower.startsWith("hy2://") || lower.startsWith("hysteria2://") -> parseHysteria2(uriString)
            else -> null
        }
    }

    private fun parseVless(raw: String): VpnServerNode? {
        return runCatching {
            val uri = Uri.parse(raw)
            val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
            val port = uri.port.takeIf { it in 1..65535 } ?: 443
            val userUuid = uri.userInfo.orEmpty()
            val sni = uri.getQueryParameter("sni")
                ?: uri.getQueryParameter("host")
                ?: "www.microsoft.com"
            val pbk = uri.getQueryParameter("pbk").orEmpty()
            val sid = uri.getQueryParameter("sid").orEmpty()
            val security = uri.getQueryParameter("security").orEmpty().lowercase()
            val type = uri.getQueryParameter("type").orEmpty().lowercase()
            val label = decodeFragment(uri.fragment) ?: host

            val supportsCdn = type == "ws" || security == "tls"
            val supportsReality = security == "reality" || pbk.isNotBlank() || !supportsCdn

            VpnServerNode(
                id = "custom-${UUID.randomUUID().toString().take(8)}",
                countryCode = guessCountryCode(label),
                countryName = label,
                city = if (supportsReality) "VLESS Reality" else "VLESS WS",
                flagEmoji = guessFlagEmoji(label),
                endpointHost = host,
                port = port,
                region = "CUSTOM",
                loadPercent = 12,
                defaultPingMs = 45,
                supportsReality = supportsReality,
                supportsHysteria2 = false,
                supportsCdn = supportsCdn,
                uuid = userUuid,
                sni = sni,
                publicKey = pbk,
                shortId = sid,
                isCustom = true
            )
        }.getOrNull()
    }

    private fun parseHysteria2(raw: String): VpnServerNode? {
        return runCatching {
            val normalized = if (raw.lowercase().startsWith("hysteria2://")) {
                "hy2://" + raw.substring("hysteria2://".length)
            } else {
                raw
            }
            val uri = Uri.parse(normalized)
            val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
            val port = uri.port.takeIf { it in 1..65535 } ?: 443
            val password = uri.userInfo.orEmpty()
            val sni = uri.getQueryParameter("sni") ?: "bing.com"
            val obfsPassword = uri.getQueryParameter("obfs-password").orEmpty()
            val label = decodeFragment(uri.fragment) ?: host

            VpnServerNode(
                id = "custom-${UUID.randomUUID().toString().take(8)}",
                countryCode = guessCountryCode(label),
                countryName = label,
                city = "Hysteria 2 · QUIC",
                flagEmoji = guessFlagEmoji(label),
                endpointHost = host,
                port = port,
                region = "CUSTOM",
                loadPercent = 10,
                defaultPingMs = 40,
                supportsReality = false,
                supportsHysteria2 = true,
                supportsCdn = false,
                sni = sni,
                hy2Password = password,
                hy2ObfsPassword = obfsPassword,
                isCustom = true
            )
        }.getOrNull()
    }

    fun encodeCustomNodes(nodes: List<VpnServerNode>): String {
        val array = JSONArray()
        nodes.filter { it.isCustom }.forEach { node ->
            val obj = JSONObject().apply {
                put("id", node.id)
                put("countryCode", node.countryCode)
                put("countryName", node.countryName)
                put("city", node.city)
                put("flagEmoji", node.flagEmoji)
                put("endpointHost", node.endpointHost)
                put("port", node.port)
                put("region", node.region)
                put("loadPercent", node.loadPercent)
                put("defaultPingMs", node.defaultPingMs)
                put("supportsReality", node.supportsReality)
                put("supportsHysteria2", node.supportsHysteria2)
                put("supportsCdn", node.supportsCdn)
                put("uuid", node.uuid)
                put("sni", node.sni)
                put("publicKey", node.publicKey)
                put("shortId", node.shortId)
                put("hy2Password", node.hy2Password)
                put("hy2ObfsPassword", node.hy2ObfsPassword)
            }
            array.put(obj)
        }
        return array.toString()
    }

    fun decodeCustomNodes(json: String?): List<VpnServerNode> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(json)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    add(
                        VpnServerNode(
                            id = obj.getString("id"),
                            countryCode = obj.optString("countryCode", "VPS"),
                            countryName = obj.getString("countryName"),
                            city = obj.optString("city", "Custom Node"),
                            flagEmoji = obj.optString("flagEmoji", "🛰️"),
                            endpointHost = obj.getString("endpointHost"),
                            port = obj.optInt("port", 443),
                            region = obj.optString("region", "CUSTOM"),
                            loadPercent = obj.optInt("loadPercent", 15),
                            defaultPingMs = obj.optInt("defaultPingMs", 45),
                            supportsReality = obj.optBoolean("supportsReality", true),
                            supportsHysteria2 = obj.optBoolean("supportsHysteria2", false),
                            supportsCdn = obj.optBoolean("supportsCdn", false),
                            uuid = obj.optString("uuid", ""),
                            sni = obj.optString("sni", "www.microsoft.com"),
                            publicKey = obj.optString("publicKey", ""),
                            shortId = obj.optString("shortId", ""),
                            hy2Password = obj.optString("hy2Password", ""),
                            hy2ObfsPassword = obj.optString("hy2ObfsPassword", ""),
                            isCustom = true
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun decodeFragment(fragment: String?): String? {
        if (fragment.isNullOrBlank()) return null
        return runCatching {
            URLDecoder.decode(fragment, "UTF-8").trim().takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    private fun guessCountryCode(label: String): String {
        val u = label.uppercase()
        return when {
            u.contains("DE") || u.contains("GERM") || u.contains("FRANKFURT") -> "DE"
            u.contains("NL") || u.contains("AMSTERDAM") || u.contains("NETHER") -> "NL"
            u.contains("FI") || u.contains("HELSINKI") || u.contains("FIN") -> "FI"
            u.contains("CH") || u.contains("ZURICH") || u.contains("SWISS") -> "CH"
            u.contains("US") || u.contains("USA") || u.contains("NEW YORK") -> "US"
            u.contains("SG") || u.contains("SINGAPORE") -> "SG"
            u.contains("GB") || u.contains("UK") || u.contains("LONDON") -> "GB"
            else -> "VPS"
        }
    }

    private fun guessFlagEmoji(label: String): String {
        return when (guessCountryCode(label)) {
            "DE" -> "🇩🇪"
            "NL" -> "🇳🇱"
            "FI" -> "🇫🇮"
            "CH" -> "🇨🇭"
            "US" -> "🇺🇸"
            "SG" -> "🇸🇬"
            "GB" -> "🇬🇧"
            else -> "🛰️"
        }
    }
}
