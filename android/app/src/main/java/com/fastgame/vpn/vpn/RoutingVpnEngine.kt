package com.fastgame.vpn.vpn

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

/** Sends each session to the engine that speaks its protocol: Xray when the profile has a vless link. */
@OptIn(ExperimentalCoroutinesApi::class)
class RoutingVpnEngine(private val ics: VpnEngine, private val xray: VpnEngine) : VpnEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val current = MutableStateFlow(ics)

    override val state: StateFlow<EngineState> =
        current.flatMapLatest { it.state }.stateIn(scope, SharingStarted.Eagerly, EngineState.Disconnected)

    override val detail: StateFlow<String?> =
        current.flatMapLatest { it.detail }.stateIn(scope, SharingStarted.Eagerly, null)

    override suspend fun connect(profile: VpnProfile) {
        current.value = if (profile.xrayLink != null) xray else ics
        current.value.connect(profile)
    }

    override suspend fun disconnect() = current.value.disconnect()

    override fun diagnostics(): String = current.value.diagnostics()
}
