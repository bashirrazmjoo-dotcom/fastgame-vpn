package com.fastgame.vpn.vpn

import android.content.Context

/** The single place that decides which OpenVPN engine the app uses. */
fun createVpnEngine(context: Context): VpnEngine = IcsVpnEngine(context.applicationContext)
