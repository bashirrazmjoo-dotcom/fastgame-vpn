package com.fastgame.vpn.vpn

import android.content.Context

/** The single place that decides which OpenVPN engine the app uses. */
@Suppress("UNUSED_PARAMETER")
fun createVpnEngine(context: Context): VpnEngine = UnavailableVpnEngine()
