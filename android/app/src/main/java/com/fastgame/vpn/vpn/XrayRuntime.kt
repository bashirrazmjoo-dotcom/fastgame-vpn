package com.fastgame.vpn.vpn

import kotlinx.coroutines.flow.MutableStateFlow
import libv2ray.CoreController

/** State shared by [XrayEngine] and [XrayVpnService]; both run in the app's main process. */
internal object XrayRuntime {
    val state = MutableStateFlow<EngineState>(EngineState.Disconnected)
    @Volatile var pendingConfig: String? = null
    @Volatile var controller: CoreController? = null
}
