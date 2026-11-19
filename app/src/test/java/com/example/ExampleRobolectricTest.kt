package com.example

import android.app.Application
import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import com.example.model.RoutingSettings
import com.example.model.VpnProtocolMode
import com.example.model.VpnServerNode
import com.example.model.buildSingBoxPreviewJson
import com.example.model.findFastestNode
import com.example.viewmodel.VpnViewModel
import com.example.vpn.NetworkDiagnostics
import com.example.vpn.VpnUriParser
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `app name matches ShieldBox`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("ShieldBox", context.getString(R.string.app_name))
    }

    @Test
    fun `parses vless reality and hysteria2 subscription links`() {
        val vless = "vless://11111111-2222-3333-4444-555555555555@203.0.113.10:8443?security=reality&sni=www.microsoft.com&pbk=pubKey123&sid=ab12#DE%20Frankfurt"
        val hy2 = "hy2://secretPass@203.0.113.20:443?sni=bing.com&obfs=salamander&obfs-password=obfsKey#NL%20Amsterdam"
        val payload = Base64.encodeToString("$vless\n$hy2".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

        val parsed = VpnUriParser.parseInput(payload)
        assertEquals(2, parsed.size)

        assertEquals("203.0.113.10", parsed[0].endpointHost)
        assertEquals(8443, parsed[0].port)
        assertEquals("pubKey123", parsed[0].publicKey)
        assertEquals("ab12", parsed[0].shortId)
        assertEquals("DE Frankfurt", parsed[0].countryName)
        assertTrue(parsed[0].supportsReality)

        assertEquals("203.0.113.20", parsed[1].endpointHost)
        assertEquals("secretPass", parsed[1].hy2Password)
        assertEquals("obfsKey", parsed[1].hy2ObfsPassword)
        assertTrue(parsed[1].supportsHysteria2)
    }

    @Test
    fun `generated singbox config is valid json with real node fields and excluded apps`() {
        val node = VpnUriParser.parseSingleUri(
            "vless://uuid-abc@1.2.3.4:443?security=reality&sni=cdn.example.com&pbk=keyXYZ&sid=01ab#FI%20Node"
        )!!
        val jsonText = buildSingBoxPreviewJson(
            node = node,
            protocolMode = VpnProtocolMode.AUTO_FALLBACK,
            routing = RoutingSettings(
                mtu = 1280,
                excludedPackages = setOf("com.bank.app")
            )
        )
        val root = JSONObject(jsonText)
        val tun = root.getJSONArray("inbounds").getJSONObject(0)
        assertEquals(1280, tun.getInt("mtu"))
        assertEquals("gvisor", tun.getString("stack"))
        assertEquals("com.bank.app", tun.getJSONArray("exclude_package").getString(0))

        val vlessOutbound = root.getJSONArray("outbounds").getJSONObject(1)
        assertEquals("1.2.3.4", vlessOutbound.getString("server"))
        assertEquals("uuid-abc", vlessOutbound.getString("uuid"))
        assertEquals("keyXYZ", vlessOutbound.getJSONObject("tls").getJSONObject("reality").getString("public_key"))
    }

    @Test
    fun `connect on app launch preference persists via SharedPreferences`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        app.getSharedPreferences("shieldbox_prefs", Context.MODE_PRIVATE).edit().clear().commit()

        val vm1 = VpnViewModel(app)
        assertFalse(vm1.uiState.value.connectOnAppLaunch)

        vm1.setConnectOnAppLaunch(true)
        vm1.setMtu(1500)

        val vm2 = VpnViewModel(app)
        assertTrue(vm2.uiState.value.connectOnAppLaunch)
        assertEquals(1500, vm2.uiState.value.routing.mtu)
        assertTrue(vm2.consumeAutoConnectIfNeeded())
        assertFalse(vm2.consumeAutoConnectIfNeeded())
    }

    @Test
    fun `findFastestNode ranks by latency then bandwidth and estimates bandwidth reasonably`() {
        val slow = VpnServerNode(
            id = "slow", countryCode = "US", countryName = "США", city = "Нью-Йорк",
            flagEmoji = "🇺🇸", endpointHost = "198.51.100.30", region = "NA",
            loadPercent = 40, defaultPingMs = 120, measuredPingMs = 115, measuredBandwidthMbps = 50.0
        )
        val fastLowBw = VpnServerNode(
            id = "fast_low_bw", countryCode = "DE", countryName = "Германия", city = "Франкфурт",
            flagEmoji = "🇩🇪", endpointHost = "198.51.100.10", region = "EU",
            loadPercent = 24, defaultPingMs = 38, measuredPingMs = 35, measuredBandwidthMbps = 60.0
        )
        val fastHighBw = VpnServerNode(
            id = "fast_high_bw", countryCode = "FI", countryName = "Финляндия", city = "Хельсинки",
            flagEmoji = "🇫🇮", endpointHost = "198.51.100.25", region = "EU",
            loadPercent = 18, defaultPingMs = 40, measuredPingMs = 35, measuredBandwidthMbps = 95.0
        )

        val winner = findFastestNode(listOf(slow, fastLowBw, fastHighBw))
        assertEquals("fast_high_bw", winner?.id)

        val highRttBw = NetworkDiagnostics.estimateNodeBandwidthMbps(baselineMbps = 100.0, latencyMs = 150, loadPercent = 50)
        val lowRttBw = NetworkDiagnostics.estimateNodeBandwidthMbps(baselineMbps = 100.0, latencyMs = 25, loadPercent = 15)
        assertTrue(lowRttBw > highRttBw)
    }
}
