package com.fastgame.vpn

import de.blinkt.openvpn.core.ICSOpenVPNApplication

/**
 * Extends the engine's application class: it sets up the engine's status listener and
 * notification channels. The engine's VPN service runs in a second process (":openvpn") that
 * also creates this class, so the container is created lazily and only used in the main process.
 */
class FastGameApp : ICSOpenVPNApplication() {
    val container: AppContainer by lazy { AppContainer(this) }
}
