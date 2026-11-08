package com.fastgame.vpn.data

import android.content.Context
import android.media.MediaDrm
import android.os.Build
import android.provider.Settings
import java.security.MessageDigest
import java.util.UUID

/**
 * Stable per-device value sent at registration so the server can recognise a returning
 * device (reinstall or cleared data) instead of granting a fresh free quota.
 *
 * Preferred source: the device's DRM (Widevine) unique id. It survives uninstall/reinstall
 * and does not depend on the key the APK is signed with. Fallbacks: ANDROID_ID (also survives
 * reinstall, but changes when the signing key changes), then a random id kept in app storage.
 * Only a hash of the DRM id is sent, and the server stores only a keyed hash of what it gets.
 */
object DeviceId {
    private const val BROKEN_EMULATOR_ID = "9774d56d682e549c"

    private val WIDEVINE = UUID(-0x121074568629b532L, -0x5c37d8232ae2de13L)

    private fun drmId(): String? = runCatching {
        val drm = MediaDrm(WIDEVINE)
        try {
            val raw = drm.getPropertyByteArray(MediaDrm.PROPERTY_DEVICE_UNIQUE_ID)
            if (raw.isEmpty()) null
            else "drm-" + MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it) }
        } finally {
            if (Build.VERSION.SDK_INT >= 28) drm.close() else @Suppress("DEPRECATION") drm.release()
        }
    }.getOrNull()

    fun hardwareId(context: Context, prefs: Prefs): String {
        drmId()?.let { return it }
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        if (!androidId.isNullOrBlank() && androidId != BROKEN_EMULATOR_ID) return "aid-$androidId"

        prefs.fallbackHardwareId?.let { return it }
        return "rnd-${UUID.randomUUID()}".also { prefs.fallbackHardwareId = it }
    }
}
