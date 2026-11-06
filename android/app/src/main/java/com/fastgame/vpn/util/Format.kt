package com.fastgame.vpn.util

import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** "01:02:05" for 3725 seconds. Negative values are shown as zero. */
fun formatDuration(totalSeconds: Long, locale: Locale = Locale.getDefault()): String {
    val s = totalSeconds.coerceAtLeast(0)
    return String.format(locale, "%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
}

/** Flag emoji for an ISO 3166-1 alpha-2 country code ("DE" -> 🇩🇪). */
fun flagEmoji(countryCode: String): String {
    if (countryCode.length != 2 || !countryCode.all { it.isLetter() }) return "🌐"
    val base = 0x1F1E6 - 'A'.code
    return countryCode.uppercase().map { String(Character.toChars(base + it.code)) }.joinToString("")
}

fun formatDate(millis: Long, locale: Locale = Locale.getDefault()): String =
    DateFormat.getDateInstance(DateFormat.MEDIUM, locale).format(Date(millis))
