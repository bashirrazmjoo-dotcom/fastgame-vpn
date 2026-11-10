package com.fastgame.vpn.vpn

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder

/** A parsed `vless://uuid@host:port?query#name` link. */
data class XrayLink(val uuid: String, val host: String, val port: Int, val params: Map<String, String>) {
    companion object {
        fun parse(link: String): XrayLink {
            if (!link.startsWith("vless://")) throw EngineException("Unsupported server link.")
            val uri = try { URI(link) } catch (e: Exception) { throw EngineException("The server link is invalid.") }
            val host = uri.host
            val user = uri.userInfo
            if (host.isNullOrBlank() || user.isNullOrBlank() || uri.port !in 1..65535) {
                throw EngineException("The server link is incomplete.")
            }
            val params = uri.rawQuery.orEmpty().split('&').filter { it.contains('=') }.associate {
                val (k, v) = it.split('=', limit = 2)
                URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
            }
            return XrayLink(user, host, uri.port, params)
        }
    }
}

/** Builds the Xray configuration for one link. Pure function, covered by unit tests. */
object XrayConfig {
    private const val TUN_TAG = "tun"

    /** [withTun] false gives the outbound-only config used to probe the server before the VPN starts. */
    fun build(link: XrayLink, withTun: Boolean = true): String {
        val outbounds = JSONArray()
            .put(proxyOutbound(link))
            .put(JSONObject().put("tag", "direct").put("protocol", "freedom"))
            .put(JSONObject().put("tag", "block").put("protocol", "blackhole"))
        val root = JSONObject()
            .put("log", JSONObject().put("loglevel", "warning"))
            .put("outbounds", outbounds)
            .put("routing", JSONObject().put("domainStrategy", "AsIs").put("rules", JSONArray()))
        if (withTun) {
            val tun = JSONObject()
                .put("tag", TUN_TAG)
                .put("protocol", "tun")
                .put("settings", JSONObject().put("name", "xray0").put("MTU", 1500).put("userLevel", 0))
                .put(
                    "sniffing",
                    JSONObject().put("enabled", true).put("destOverride", JSONArray().put("http").put("tls").put("quic")),
                )
            root.put("inbounds", JSONArray().put(tun))
        }
        return root.toString()
    }

    private fun proxyOutbound(l: XrayLink): JSONObject {
        val user = JSONObject().put("id", l.uuid).put("encryption", l.params["encryption"] ?: "none")
        l.params["flow"]?.takeIf { it.isNotBlank() }?.let { user.put("flow", it) }
        val vnext = JSONObject().put("address", l.host).put("port", l.port).put("users", JSONArray().put(user))
        return JSONObject()
            .put("tag", "proxy")
            .put("protocol", "vless")
            .put("settings", JSONObject().put("vnext", JSONArray().put(vnext)))
            .put("streamSettings", streamSettings(l))
    }

    private fun streamSettings(l: XrayLink): JSONObject {
        val p = l.params
        val network = p["type"] ?: "tcp"
        val s = JSONObject().put("network", network)
        when (network) {
            "ws" -> s.put(
                "wsSettings",
                JSONObject().put("path", p["path"] ?: "/").apply { (p["host"] ?: p["sni"])?.let { put("headers", JSONObject().put("Host", it)) } },
            )
            "grpc" -> s.put("grpcSettings", JSONObject().put("serviceName", p["serviceName"] ?: ""))
            "httpupgrade" -> s.put(
                "httpupgradeSettings",
                JSONObject().put("path", p["path"] ?: "/").apply { (p["host"] ?: p["sni"])?.let { put("host", it) } },
            )
            "tcp" -> Unit
            else -> throw EngineException("Unsupported transport: $network")
        }
        when (val security = p["security"] ?: "none") {
            "tls" -> {
                s.put("security", "tls")
                val tls = JSONObject().put("serverName", p["sni"] ?: l.host).put("allowInsecure", false)
                p["fp"]?.takeIf { it.isNotBlank() }?.let { tls.put("fingerprint", it) }
                p["alpn"]?.takeIf { it.isNotBlank() }?.let { a ->
                    tls.put("alpn", JSONArray().apply { a.split(',').forEach { put(it.trim()) } })
                }
                s.put("tlsSettings", tls)
            }
            "reality" -> {
                s.put("security", "reality")
                val r = JSONObject()
                    .put("serverName", p["sni"] ?: l.host)
                    .put("fingerprint", p["fp"] ?: "chrome")
                    .put("publicKey", p["pbk"] ?: throw EngineException("The server link has no Reality key."))
                    .put("shortId", p["sid"] ?: "")
                    .put("spiderX", p["spx"] ?: "")
                s.put("realitySettings", r)
            }
            "none" -> Unit
            else -> throw EngineException("Unsupported security: $security")
        }
        return s
    }
}
