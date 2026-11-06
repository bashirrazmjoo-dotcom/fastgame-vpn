package com.fastgame.vpn.vpn

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import de.blinkt.openvpn.core.ConfigParser
import de.blinkt.openvpn.core.ConnectionStatus
import de.blinkt.openvpn.core.IOpenVPNServiceInternal
import de.blinkt.openvpn.core.OpenVPNService
import de.blinkt.openvpn.core.ProfileManager
import de.blinkt.openvpn.core.VPNLaunchHelper
import de.blinkt.openvpn.core.VpnStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.StringReader
import de.blinkt.openvpn.VpnProfile as IcsProfile

/**
 * [VpnEngine] backed by the embedded ics-openvpn library (GPLv2).
 * The tunnel itself runs in the library's own VPN service in the ":openvpn" process.
 */
class IcsVpnEngine(private val context: Context) : VpnEngine, VpnStatus.StateListener {

    private val _state = MutableStateFlow<EngineState>(EngineState.Disconnected)
    override val state: StateFlow<EngineState> = _state.asStateFlow()

    private val _detail = MutableStateFlow<String?>(null)
    override val detail: StateFlow<String?> = _detail.asStateFlow()

    @Volatile private var active = false
    private var icsProfile: IcsProfile? = null

    override suspend fun connect(profile: VpnProfile) {
        val parsed = withContext(Dispatchers.Default) { parse(profile) }
        val problem = parsed.checkProfile(context)
        if (problem != de.blinkt.openvpn.R.string.no_error_found) {
            throw EngineException("Invalid server profile: " + context.getString(problem))
        }
        icsProfile = parsed
        // The VPN service runs in another process and loads the profile from disk by its id.
        ProfileManager.setTemporaryProfile(context, parsed)

        _state.value = EngineState.Connecting
        // Registering replays the last known state once; that stale event is ignored (active == false).
        VpnStatus.addStateListener(this)
        sawProgress = false
        active = true
        VPNLaunchHelper.startOpenVpn(parsed, context, "FastGame session", true)

        when (val result = _state.first { it !is EngineState.Connecting }) {
            EngineState.Connected -> Unit
            is EngineState.Failed -> throw EngineException(result.reason)
            else -> throw EngineException("The connection was closed.")
        }
    }

    private fun parse(profile: VpnProfile): IcsProfile {
        try {
            val parser = ConfigParser()
            parser.parseConfig(StringReader(profile.ovpnConfig))
            return parser.convertProfile().also {
                it.mName = profile.serverName
                it.mUsername = profile.username.orEmpty()
                it.mPassword = profile.password.orEmpty()
            }
        } catch (e: ConfigParser.ConfigParseError) {
            throw EngineException("The server profile is invalid: ${e.message}")
        } catch (e: IOException) {
            throw EngineException("The server profile could not be read.")
        }
    }

    override suspend fun disconnect() {
        if (!active && icsProfile == null) return
        withTimeoutOrNull(STOP_TIMEOUT_MS) { stopService() }
        VpnStatus.removeStateListener(this)
        active = false
        runCatching { ProfileManager.setConnectedVpnProfileDisconnected(context) }
        // The temporary profile file holds this session's (short-lived) credentials: remove it.
        icsProfile?.let { p -> runCatching { ProfileManager.getInstance(context).removeProfile(context, p) } }
        icsProfile = null
        _detail.value = null
        _state.value = EngineState.Disconnected
    }

    private suspend fun stopService() {
        val done = CompletableDeferred<Unit>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                runCatching { IOpenVPNServiceInternal.Stub.asInterface(binder).stopVPN(false) }
                done.complete(Unit)
            }

            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        val intent = Intent(context, OpenVPNService::class.java).setAction(OpenVPNService.START_SERVICE)
        if (!context.bindService(intent, connection, Context.BIND_AUTO_CREATE)) return
        try {
            done.await()
        } finally {
            runCatching { context.unbindService(connection) }
        }
    }

    // --- VpnStatus.StateListener (called from the engine's status thread) ---

    override fun updateState(
        state: String?, logmessage: String?, localizedResId: Int, level: ConnectionStatus?, intent: Intent?,
    ) {
        if (!active) return
        _detail.value = listOfNotNull(state, logmessage).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { null }
        _state.value = when (level) {
            ConnectionStatus.LEVEL_CONNECTED -> EngineState.Connected
            ConnectionStatus.LEVEL_AUTH_FAILED -> EngineState.Failed("Authentication failed.")
            ConnectionStatus.LEVEL_NOTCONNECTED -> {
                if (_state.value is EngineState.Connecting && !sawProgress) return // stale "not connected"
                EngineState.Disconnected
            }
            else -> {
                sawProgress = true
                // Reconnecting after a network change is not an end of the session.
                if (_state.value is EngineState.Connected) EngineState.Connecting else _state.value
            }
        }
    }

    @Volatile private var sawProgress = false

    override fun setConnectedVPN(uuid: String?) = Unit

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
