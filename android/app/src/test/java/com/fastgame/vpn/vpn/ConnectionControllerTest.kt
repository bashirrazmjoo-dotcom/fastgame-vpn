package com.fastgame.vpn.vpn

import com.fastgame.vpn.data.Account
import com.fastgame.vpn.data.ApiException
import com.fastgame.vpn.data.AppConfig
import com.fastgame.vpn.data.Backend
import com.fastgame.vpn.data.SessionState
import com.fastgame.vpn.data.SessionStatus
import com.fastgame.vpn.data.StartedSession
import com.fastgame.vpn.data.VpnServerInfo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeEngine : VpnEngine {
    val s = MutableStateFlow<EngineState>(EngineState.Disconnected)
    override val state: StateFlow<EngineState> = s
    var connectError: EngineException? = null
    var disconnects = 0
    override suspend fun connect(profile: VpnProfile) {
        connectError?.let { throw it }
        s.value = EngineState.Connected
    }
    override suspend fun disconnect() { disconnects++; s.value = EngineState.Disconnected }
}

private class FakeBackend(var remaining: Long? = 100) : Backend {
    var startError: ApiException? = null
    var beats = 0
    var stops = 0
    var heartbeatState: () -> SessionState = { SessionState(SessionStatus.ACTIVE, false, remaining, null) }
    override suspend fun config(): AppConfig = error("unused")
    override suspend fun account(): Account = error("unused")
    override suspend fun servers(): List<VpnServerInfo> = error("unused")
    override suspend fun startSession(serverId: Int): StartedSession {
        startError?.let { throw it }
        return StartedSession("sid", serverId, 10, remaining == null, remaining, null, "cfg", "u", "p")
    }
    override suspend fun confirmConnected(sessionId: String) = SessionState(SessionStatus.ACTIVE, remaining == null, remaining, null)
    override suspend fun heartbeat(sessionId: String): SessionState { beats++; return heartbeatState() }
    override suspend fun stopSession(sessionId: String): SessionState { stops++; return SessionState(SessionStatus.CLOSED, false, remaining, null) }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionControllerTest {
    private val server = VpnServerInfo(1, "de-1", "DE", "Germany", "آلمان")

    private fun TestScope.controller(b: Backend, e: VpnEngine) =
        ConnectionController(b, e, backgroundScope) { currentTime }

    @Test fun connectsHeartbeatsAndDisconnects() = runTest {
        val b = FakeBackend(); val e = FakeEngine(); val c = controller(b, e)
        assertTrue(c.connect(server))
        runCurrent()
        assertEquals(Phase.CONNECTED, c.state.value.phase)
        advanceTimeBy(25_000); runCurrent()
        assertTrue(b.beats >= 2)
        assertEquals(95L, c.state.value.remainingSeconds) // re-anchored on the 100s the server reported at t=20s
        c.disconnect(); runCurrent()
        assertEquals(Phase.IDLE, c.state.value.phase)
        assertEquals(1, b.stops); assertEquals(1, e.disconnects)
        assertNull(c.state.value.notice)
    }

    @Test fun quotaExhaustedOnStart() = runTest {
        val b = FakeBackend().apply { startError = ApiException(403, "quota_exhausted", "x", JSONObject("""{"resets_at":"2026-01-01T00:00:00Z"}""")) }
        val c = controller(b, FakeEngine())
        c.connect(server); runCurrent()
        assertEquals(Phase.IDLE, c.state.value.phase)
        assertTrue(c.state.value.notice is Notice.QuotaExhausted)
        assertEquals(0, b.stops) // no session was opened
    }

    @Test fun serverEndsSessionWhenQuotaRunsOut() = runTest {
        val b = FakeBackend(5).apply { heartbeatState = { SessionState(SessionStatus.QUOTA_EXHAUSTED, false, 0, null) } }
        val e = FakeEngine(); val c = controller(b, e)
        c.connect(server); advanceTimeBy(10_000); runCurrent()
        assertEquals(Phase.IDLE, c.state.value.phase)
        assertTrue(c.state.value.notice is Notice.QuotaExhausted)
        assertEquals(1, b.stops); assertEquals(1, e.disconnects)
    }

    @Test fun repeatedEngineFailuresEventuallyGiveUp() = runTest {
        val b = FakeBackend(); val e = FakeEngine().apply { connectError = EngineException("boom") }
        val c = controller(b, e)
        c.connect(server); runCurrent()
        assertEquals(Phase.STARTING, c.state.value.phase) // retrying, no notice yet
        assertNull(c.state.value.notice)
        advanceTimeBy(200_000); runCurrent()
        assertEquals(Phase.IDLE, c.state.value.phase)
        assertEquals(Notice.EngineFailed("boom"), c.state.value.notice)
        assertEquals(2, b.stops) // engine errors are reported after two attempts, not hidden behind a spinner
    }

    @Test fun tunnelDropReconnectsByItself() = runTest {
        val b = FakeBackend(); val e = FakeEngine(); val c = controller(b, e)
        c.connect(server); runCurrent()
        e.s.value = EngineState.Disconnected
        advanceTimeBy(2_000); runCurrent()
        assertEquals(Phase.STARTING, c.state.value.phase)
        advanceTimeBy(5_000); runCurrent()
        assertEquals(Phase.CONNECTED, c.state.value.phase) // a second session is up
        assertEquals(1, b.stops)
        c.disconnect(); runCurrent()
        assertEquals(Phase.IDLE, c.state.value.phase)
        assertNull(c.state.value.notice)
        assertEquals(2, b.stops)
    }

    @Test fun serverClosedSessionReconnects() = runTest {
        var closed = true
        val b = FakeBackend().apply {
            heartbeatState = {
                if (closed) { closed = false; SessionState(SessionStatus.CLOSED, false, 50, null) }
                else SessionState(SessionStatus.ACTIVE, false, 50, null)
            }
        }
        val c = controller(b, FakeEngine())
        c.connect(server); advanceTimeBy(12_000); runCurrent()
        advanceTimeBy(5_000); runCurrent()
        assertEquals(Phase.CONNECTED, c.state.value.phase)
        c.disconnect(); runCurrent()
        assertNull(c.state.value.notice)
    }

    @Test fun secondConnectIsRejected() = runTest {
        val c = controller(FakeBackend(), FakeEngine())
        assertTrue(c.connect(server))
        assertEquals(false, c.connect(server))
    }
}
