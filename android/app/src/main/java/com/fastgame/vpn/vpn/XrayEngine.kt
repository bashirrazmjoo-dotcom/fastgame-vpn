package com.fastgame.vpn.vpn

import android.content.Context
import android.content.Intent
import android.os.Build
import go.Seq
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import libv2ray.Libv2ray

/**
 * [VpnEngine] for Xray (VLESS) servers, using the embedded Xray core (MPL-2.0).
 * The tunnel runs in [XrayVpnService]; this class probes the server first so a dead server fails fast.
 */
class XrayEngine(private val context: Context) : VpnEngine {
    override val state: StateFlow<EngineState> = XrayRuntime.state.asStateFlow()
    private val _detail = MutableStateFlow<String?>(null)
    override val detail: StateFlow<String?> = _detail.asStateFlow()
    @Volatile private var lastError = ""

    override suspend fun connect(profile: VpnProfile) {
        if (Build.SUPPORTED_ABIS.none { it == "arm64-v8a" }) {
            throw EngineException("This server type needs a 64-bit (arm64) phone.")
        }
        val link = XrayLink.parse(profile.xrayLink ?: throw EngineException("No server link."))
        XrayRuntime.state.value = EngineState.Connecting
        _detail.value = "xray"

        // Probe: can the core reach the server and the internet through it (before touching the VPN)?
        val delay = withContext(Dispatchers.IO) { probe(XrayConfig.build(link, withTun = false)) }
        if (delay < 0) {
            XrayRuntime.state.value = EngineState.Disconnected
            throw EngineException("The server did not answer. $lastError".trim())
        }

        XrayRuntime.pendingConfig = XrayConfig.build(link, withTun = true)
        val intent = Intent(context, XrayVpnService::class.java)
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)

        when (val result = XrayRuntime.state.first { it !is EngineState.Connecting }) {
            EngineState.Connected -> Unit
            is EngineState.Failed -> {
                XrayRuntime.state.value = EngineState.Disconnected
                throw EngineException(result.reason)
            }
            else -> throw EngineException("The connection was closed.")
        }
    }

    private fun probe(config: String): Long = try {
        Seq.setContext(context.applicationContext)
        Libv2ray.initCoreEnv(context.filesDir.absolutePath, "")
        Libv2ray.measureOutboundDelay(config, PROBE_URL)
    } catch (e: Throwable) {
        lastError = e.message.orEmpty().take(160)
        -1L
    }

    override suspend fun disconnect() {
        if (XrayRuntime.controller == null && XrayRuntime.state.value is EngineState.Disconnected) return
        withTimeoutOrNull(STOP_TIMEOUT_MS) {
            context.startService(Intent(context, XrayVpnService::class.java).setAction(XrayVpnService.ACTION_STOP))
            XrayRuntime.state.first { it is EngineState.Disconnected }
        }
        XrayRuntime.state.value = EngineState.Disconnected
        _detail.value = null
    }

    override fun diagnostics(): String = "xray: ${XrayRuntime.state.value} $lastError".trim()

    private companion object {
        const val PROBE_URL = "https://www.gstatic.com/generate_204"
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
