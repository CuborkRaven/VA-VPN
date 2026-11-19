package com.example.vpn

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

data class NetworkDiagnosticsResult(
    val publicIp: String? = null,
    val countryCode: String? = null,
    val edgeColocation: String? = null,
    val dohReachable: Boolean = false,
    val dohLatencyMs: Int? = null,
    val nodeTcpReachable: Boolean? = null,
    val nodeTcpLatencyMs: Int? = null,
    val error: String? = null
)

object NetworkDiagnostics {

    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.SECONDS)
        .build()

    fun measureTcpPingMs(host: String, port: Int, timeoutMs: Int = 2200): Int? {
        if (host.startsWith("198.51.100.") || host.startsWith("192.0.2.") || host.startsWith("203.0.113.")) {
            return null
        }
        return runCatching {
            val start = System.nanoTime()
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
            }
            ((System.nanoTime() - start) / 1_000_000L).toInt().coerceAtLeast(1)
        }.getOrNull()
    }

    fun measureHttpLatencyMs(url: String = "https://www.gstatic.com/generate_204"): Int? {
        return runCatching {
            val start = System.nanoTime()
            val req = Request.Builder().url(url).head().build()
            client.newCall(req).execute().use { resp ->
                if (resp.code in 200..499) {
                    ((System.nanoTime() - start) / 1_000_000L).toInt().coerceAtLeast(1)
                } else {
                    null
                }
            }
        }.getOrNull()
    }

    /**
     * Quick lightweight download probe (~384 KB) to measure baseline link throughput in Mbps
     * before establishing a VPN tunnel.
     */
    fun measureQuickBandwidthMbps(): Double? {
        val urls = listOf(
            "https://speed.cloudflare.com/__down?bytes=393216",
            "https://www.google.com/images/branding/googlelogo/2x/googlelogo_color_272x92dp.png"
        )
        for (probeUrl in urls) {
            val mbps = runCatching {
                val req = Request.Builder().url(probeUrl).build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val body = resp.body ?: return@use null
                    val buffer = ByteArray(16 * 1024)
                    var totalBytes = 0L
                    val startNs = System.nanoTime()
                    body.byteStream().use { stream ->
                        while (true) {
                            val read = stream.read(buffer)
                            if (read <= 0) break
                            totalBytes += read
                        }
                    }
                    val elapsedNs = (System.nanoTime() - startNs).coerceAtLeast(1_000_000L)
                    if (totalBytes < 4096L) return@use null
                    val seconds = elapsedNs / 1_000_000_000.0
                    ((totalBytes * 8.0) / (seconds * 1_000_000.0)).coerceIn(0.5, 2500.0)
                }
            }.getOrNull()
            if (mbps != null) return mbps
        }
        return null
    }

    fun estimateNodeBandwidthMbps(
        baselineMbps: Double?,
        latencyMs: Int,
        loadPercent: Int
    ): Double {
        val base = baselineMbps ?: 64.0
        val loadFactor = (1.0 - (loadPercent * 0.0045)).coerceIn(0.45, 0.98)
        val rttFactor = when {
            latencyMs < 40 -> 0.96
            latencyMs < 80 -> 0.88
            latencyMs < 130 -> 0.74
            else -> 0.58
        }
        return (base * loadFactor * rttFactor).coerceAtLeast(1.2)
    }

    fun runFullCheck(targetHost: String?, targetPort: Int): NetworkDiagnosticsResult {
        var publicIp: String? = null
        var country: String? = null
        var colo: String? = null
        var errorMsg: String? = null

        // 1. Public IP & Edge location via Cloudflare trace, fallback to ipify
        runCatching {
            val traceReq = Request.Builder().url("https://1.1.1.1/cdn-cgi/trace").build()
            client.newCall(traceReq).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string().orEmpty()
                    val map = body.lines()
                        .mapNotNull { line ->
                            val idx = line.indexOf('=')
                            if (idx > 0) line.substring(0, idx) to line.substring(idx + 1) else null
                        }
                        .toMap()
                    publicIp = map["ip"]?.takeIf { it.isNotBlank() }
                    country = map["loc"]?.takeIf { it.isNotBlank() }
                    colo = map["colo"]?.takeIf { it.isNotBlank() }
                }
            }
        }

        if (publicIp == null) {
            runCatching {
                val ipReq = Request.Builder().url("https://api.ipify.org?format=json").build()
                client.newCall(ipReq).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val json = JSONObject(resp.body?.string().orEmpty())
                        publicIp = json.optString("ip").takeIf { it.isNotBlank() }
                    }
                }
            }.onFailure { err ->
                errorMsg = err.message
            }
        }

        // 2. DoH DNS Leak / Reachability test (1.1.1.1 DNS-over-HTTPS)
        var dohReachable = false
        var dohLatency: Int? = null
        runCatching {
            val start = System.nanoTime()
            val dohReq = Request.Builder()
                .url("https://1.1.1.1/dns-query?name=example.com&type=A")
                .header("Accept", "application/dns-json")
                .build()
            client.newCall(dohReq).execute().use { resp ->
                if (resp.isSuccessful) {
                    val json = JSONObject(resp.body?.string().orEmpty())
                    dohReachable = json.optInt("Status", -1) == 0
                    dohLatency = ((System.nanoTime() - start) / 1_000_000L).toInt().coerceAtLeast(1)
                }
            }
        }

        // 3. Target VPN endpoint TCP reachability (if non-documentation host)
        val tcpLatency = if (!targetHost.isNullOrBlank()) {
            measureTcpPingMs(targetHost, targetPort)
        } else {
            null
        }
        val tcpReachable = if (targetHost.isNullOrBlank() || targetHost.startsWith("198.51.100.")) {
            null
        } else {
            tcpLatency != null
        }

        return NetworkDiagnosticsResult(
            publicIp = publicIp,
            countryCode = country,
            edgeColocation = colo,
            dohReachable = dohReachable,
            dohLatencyMs = dohLatency,
            nodeTcpReachable = tcpReachable,
            nodeTcpLatencyMs = tcpLatency,
            error = if (publicIp == null && !dohReachable) (errorMsg ?: "Нет доступа к сети") else null
        )
    }
}
