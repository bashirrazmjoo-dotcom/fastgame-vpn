package com.fastgame.vpn.vpn

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Everything an OpenVPN engine needs to bring up one tunnel. Credentials are never logged. */
data class VpnProfile(
    val serverName: String,
    val ovpnConfig: String,
    val username: String?,
    val password: String?,
)

sealed interface EngineState {
    data object Disconnected : EngineState
    data object Connecting : EngineState
    data object Connected : EngineState
    data class Failed(val reason: String) : EngineState
}

class EngineException(message: String) : Exception(message)

/**
 * The only thing the rest of the app knows about the OpenVPN implementation.
 *
 * [connect] must return only once the tunnel is really up, and throw [EngineException]
 * if it cannot be established. [state] must report later drops (Failed/Disconnected).
 */
interface VpnEngine {
    val state: StateFlow<EngineState>
    suspend fun connect(profile: VpnProfile)
    suspend fun disconnect()
}

/** Honest placeholder used until a real OpenVPN engine is plugged in: it never pretends to connect. */
class UnavailableVpnEngine : VpnEngine {
    private val _state = MutableStateFlow<EngineState>(EngineState.Disconnected)
    override val state: StateFlow<EngineState> = _state.asStateFlow()

    override suspend fun connect(profile: VpnProfile) {
        throw EngineException("The OpenVPN engine is not included in this build.")
    }

    override suspend fun disconnect() = Unit
}
