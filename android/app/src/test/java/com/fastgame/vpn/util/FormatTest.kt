package com.fastgame.vpn.util

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {
    @Test fun duration() {
        assertEquals("01:02:05", formatDuration(3725, Locale.US))
        assertEquals("00:00:00", formatDuration(-5, Locale.US))
        assertEquals("02:00:00", formatDuration(7200, Locale.US))
    }

    @Test fun flag() {
        assertEquals(String(Character.toChars(0x1F1E9)) + String(Character.toChars(0x1F1EA)), flagEmoji("de"))
        assertEquals("🌐", flagEmoji("xyz"))
    }
}
