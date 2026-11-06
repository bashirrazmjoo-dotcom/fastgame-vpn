package com.fastgame.vpn.data

import android.content.Context

/** Small private key-value store. It is excluded from backups (see data_extraction_rules.xml). */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("fastgame", Context.MODE_PRIVATE)

    var deviceToken: String?
        get() = sp.getString("device_token", null)
        set(value) = sp.edit().putString("device_token", value).apply()

    var userCode: String?
        get() = sp.getString("user_code", null)
        set(value) = sp.edit().putString("user_code", value).apply()

    /** Used only when the device does not expose a usable ANDROID_ID. */
    var fallbackHardwareId: String?
        get() = sp.getString("fallback_hardware_id", null)
        set(value) = sp.edit().putString("fallback_hardware_id", value).apply()

    var lastServerId: Int
        get() = sp.getInt("last_server_id", -1)
        set(value) = sp.edit().putInt("last_server_id", value).apply()

    var disclosureAccepted: Boolean
        get() = sp.getBoolean("disclosure_accepted", false)
        set(value) = sp.edit().putBoolean("disclosure_accepted", value).apply()
}
