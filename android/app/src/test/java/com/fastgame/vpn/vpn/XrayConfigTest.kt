package com.fastgame.vpn.vpn

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class XrayConfigTest {
    private val uuid = "0f472a85-5c6c-4078-bc8e-360407dee4d3"
    private val tcpTls = "vless://$uuid@xray.test:23088?type=tcp&security=tls&fp=chrome&alpn=h2%2Chttp%2F1.1&sni=xray.test#FastGame"

    @Test fun parsesLink() {
        val l = XrayLink.parse(tcpTls)
        assertEquals(uuid, l.uuid); assertEquals("xray.test", l.host); assertEquals(23088, l.port)
        assertEquals("h2,http/1.1", l.params["alpn"])
    }

    @Test fun tlsConfigIsVerifiedAndHasTun() {
        val root = JSONObject(XrayConfig.build(XrayLink.parse(tcpTls)))
        val proxy = root.getJSONArray("outbounds").getJSONObject(0)
        assertEquals("vless", proxy.getString("protocol"))
        val stream = proxy.getJSONObject("streamSettings")
        assertEquals("tls", stream.getString("security"))
        val tls = stream.getJSONObject("tlsSettings")
        assertFalse(tls.getBoolean("allowInsecure"))
        assertEquals("xray.test", tls.getString("serverName"))
        assertEquals("h2", tls.getJSONArray("alpn").getString(0))
        assertEquals("tun", root.getJSONArray("inbounds").getJSONObject(0).getString("protocol"))
    }

    @Test fun probeConfigHasNoTun() {
        assertFalse(JSONObject(XrayConfig.build(XrayLink.parse(tcpTls), withTun = false)).has("inbounds"))
    }

    @Test fun websocketBehindCdn() {
        val l = XrayLink.parse("vless://$uuid@cdn.test:443?type=ws&security=tls&sni=cdn.test&host=cdn.test&path=%2Fws")
        val ws = JSONObject(XrayConfig.build(l)).getJSONArray("outbounds").getJSONObject(0)
            .getJSONObject("streamSettings").getJSONObject("wsSettings")
        assertEquals("/ws", ws.getString("path"))
        assertEquals("cdn.test", ws.getJSONObject("headers").getString("Host"))
    }

    @Test fun realityNeedsKey() {
        try {
            XrayConfig.build(XrayLink.parse("vless://$uuid@r.test:443?type=tcp&security=reality&sni=x.test"))
            fail("expected EngineException")
        } catch (e: EngineException) {
            assertTrue(e.message!!.contains("Reality"))
        }
    }

    @Test fun rejectsOtherSchemes() {
        try { XrayLink.parse("vmess://abc"); fail() } catch (e: EngineException) { /* ok */ }
    }
}
