package com.fastgame.vpn.data

import org.json.JSONObject
import java.time.OffsetDateTime

data class Quota(
    val totalSeconds: Long,
    val usedSeconds: Long,
    val remainingSeconds: Long,
    val resetsAtMillis: Long?,
)

data class Account(
    val userCode: String,
    val isVip: Boolean,
    val vipExpiresAtMillis: Long?,
    val vipLifetime: Boolean,
    val isBlocked: Boolean,
    val quota: Quota,
    val vipPurchaseUrl: String,
)

data class VpnServerInfo(
    val id: Int,
    val name: String,
    val countryCode: String,
    val countryNameEn: String,
    val countryNameFa: String,
)

data class AppConfig(
    val heartbeatIntervalSeconds: Int,
    val supportUrl: String,
    val minSupportedVersionCode: Int,
    val latestVersionCode: Int,
    val updateUrl: String,
    val privacyPolicyUrl: String,
)

data class Registration(val userCode: String, val deviceToken: String)

data class StartedSession(
    val sessionId: String,
    val serverId: Int,
    val heartbeatIntervalSeconds: Int,
    val isVip: Boolean,
    val remainingSeconds: Long?, // null = unlimited (VIP)
    val resetsAtMillis: Long?,
    val ovpnConfig: String,
    val authUsername: String?,
    val authPassword: String?,
    val xrayLink: String? = null, // vless:// link of this session's client (Xray servers)
)

enum class SessionStatus { ACTIVE, QUOTA_EXHAUSTED, CLOSED }

data class SessionState(
    val status: SessionStatus,
    val isVip: Boolean,
    val remainingSeconds: Long?, // null = unlimited (VIP)
    val resetsAtMillis: Long?,
)

/** Parses the ISO-8601 timestamps sent by the API, with or without a zone offset. */
fun parseIsoMillis(value: String): Long = OffsetDateTime.parse(value).toInstant().toEpochMilli()

private fun JSONObject.stringOrNull(name: String): String? = if (isNull(name)) null else getString(name)

private fun JSONObject.longOrNull(name: String): Long? = if (isNull(name)) null else getLong(name)

private fun JSONObject.isoMillisOrNull(name: String): Long? = stringOrNull(name)?.let(::parseIsoMillis)

internal fun JSONObject.toQuota() = Quota(
    totalSeconds = getLong("total_seconds"),
    usedSeconds = getLong("used_seconds"),
    remainingSeconds = getLong("remaining_seconds"),
    resetsAtMillis = isoMillisOrNull("resets_at"),
)

internal fun JSONObject.toAccount() = Account(
    userCode = getString("user_code"),
    isVip = getBoolean("is_vip"),
    vipExpiresAtMillis = isoMillisOrNull("vip_expires_at"),
    vipLifetime = getBoolean("vip_lifetime"),
    isBlocked = getBoolean("is_blocked"),
    quota = getJSONObject("quota").toQuota(),
    vipPurchaseUrl = getString("vip_purchase_url"),
)

internal fun JSONObject.toServerInfo() = VpnServerInfo(
    id = getInt("id"),
    name = getString("name"),
    countryCode = getString("country_code"),
    countryNameEn = getString("country_name_en"),
    countryNameFa = getString("country_name_fa"),
)

internal fun JSONObject.toAppConfig() = AppConfig(
    heartbeatIntervalSeconds = getInt("heartbeat_interval_seconds"),
    supportUrl = getString("support_url"),
    minSupportedVersionCode = getInt("min_supported_version_code"),
    latestVersionCode = optInt("latest_version_code", 0),
    updateUrl = optString("update_url", ""),
    privacyPolicyUrl = getString("privacy_policy_url"),
)

internal fun JSONObject.toRegistration() = Registration(
    userCode = getString("user_code"),
    deviceToken = getString("device_token"),
)

internal fun JSONObject.toStartedSession() = StartedSession(
    sessionId = getString("session_id"),
    serverId = getInt("server_id"),
    heartbeatIntervalSeconds = getInt("heartbeat_interval_seconds"),
    isVip = getBoolean("is_vip"),
    remainingSeconds = longOrNull("remaining_seconds"),
    resetsAtMillis = isoMillisOrNull("resets_at"),
    ovpnConfig = getString("ovpn_config"),
    authUsername = stringOrNull("auth_username"),
    authPassword = stringOrNull("auth_password"),
    xrayLink = stringOrNull("xray_link"),
)

internal fun JSONObject.toSessionState() = SessionState(
    status = when (getString("status")) {
        "active" -> SessionStatus.ACTIVE
        "quota_exhausted" -> SessionStatus.QUOTA_EXHAUSTED
        else -> SessionStatus.CLOSED
    },
    isVip = getBoolean("is_vip"),
    remainingSeconds = longOrNull("remaining_seconds"),
    resetsAtMillis = isoMillisOrNull("resets_at"),
)
