package com.fastgame.vpn.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelsTest {
    @Test fun isoWithOffsets() {
        val z = parseIsoMillis("2026-01-01T00:00:00Z")
        assertEquals(z, parseIsoMillis("2026-01-01T00:00:00+00:00"))
        assertEquals(z, parseIsoMillis("2026-01-01T03:30:00+03:30"))
        assertEquals(z + 500, parseIsoMillis("2026-01-01T00:00:00.500Z"))
    }

    @Test fun sessionStateParsing() {
        val s = JSONObject(
            """{"status":"quota_exhausted","is_vip":false,"remaining_seconds":0,"resets_at":"2026-01-01T00:00:00Z"}""",
        ).toSessionState()
        assertEquals(SessionStatus.QUOTA_EXHAUSTED, s.status)
        assertEquals(0L, s.remainingSeconds)
        assertTrue(s.resetsAtMillis != null)
    }

    @Test fun vipHasNoRemaining() {
        val s = JSONObject(
            """{"status":"active","is_vip":true,"remaining_seconds":null,"resets_at":null}""",
        ).toSessionState()
        assertEquals(SessionStatus.ACTIVE, s.status)
        assertNull(s.remainingSeconds)
        assertNull(s.resetsAtMillis)
    }
}
