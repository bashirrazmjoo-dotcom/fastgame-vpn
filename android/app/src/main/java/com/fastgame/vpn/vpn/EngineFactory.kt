package com.fastgame.vpn.vpn

import android.content.Context

/** The single place that decides which engines the app uses: OpenVPN (ics) and Xray, picked per server. */
fun createVpnEngine(context: Context): VpnEngine {
    val app = context.applicationContext
    return RoutingVpnEngine(ics = IcsVpnEngine(app), xray = XrayEngine(app))
}
