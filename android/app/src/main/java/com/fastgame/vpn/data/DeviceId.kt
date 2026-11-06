package com.fastgame.vpn.data

import android.content.Context
import android.provider.Settings
import java.util.UUID

/**
 * Stable per-device value sent at registration so the server can recognise a returning
 * device (reinstall or cleared data) instead of granting a fresh free quota.
 *
 * ANDROID_ID survives uninstall/reinstall for the same app signing key and is reset only
 * by a factory reset. It is sent to the server, which stores only a keyed hash of it.
 */
object DeviceId {
    private const val BROKEN_EMULATOR_ID = "9774d56d682e549c"

    fun hardwareId(context: Context, prefs: Prefs): String {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        if (!androidId.isNullOrBlank() && androidId != BROKEN_EMULATOR_ID) return "aid-$androidId"

        prefs.fallbackHardwareId?.let { return it }
        return "rnd-${UUID.randomUUID()}".also { prefs.fallbackHardwareId = it }
    }
}
