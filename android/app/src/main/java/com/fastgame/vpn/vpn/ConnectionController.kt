package com.fastgame.vpn.vpn

import android.os.SystemClock
import com.fastgame.vpn.data.ApiException
import com.fastgame.vpn.data.Backend
import com.fastgame.vpn.data.NetworkException
import com.fastgame.vpn.data.SessionState
import com.fastgame.vpn.data.SessionStatus
import com.fastgame.vpn.data.VpnServerInfo
import com.fastgame.vpn.data.parseIsoMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

enum class Phase { IDLE, STARTING, CONNECTING, CONNECTED, DISCONNECTING }

/** Why a connection ended or could not start. Shown once to the user. */
sealed interface Notice {
    data class QuotaExhausted(val resetsAtMillis: Long?) : Notice
    data object Blocked : Notice
    data object SessionEnded : Notice
    data object ConnectionLost : Notice
    data object NetworkLost : Notice
    data object ServerUnavailable : Notice
    data class EngineFailed(val reason: String) : Notice
    data class ServiceError(val message: String) : Notice
}

data class ConnectionState(
    val phase: Phase = Phase.IDLE,
    val serverId: Int? = null,
    val isVip: Boolean = false,
    /** Seconds of free time left; null when unlimited (VIP) or unknown. */
    val remainingSeconds: Long? = null,
    val notice: Notice? = null,
    /** Engine progress text while connecting. */
    val detail: String? = null,
)

/**
 * Runs one VPN session end to end:
 *
 * 1. ask the server to start a session (it checks block state and quota, and hands out the profile)
 * 2. bring up the tunnel through the [VpnEngine]
 * 3. tell the server the tunnel is up (charging of connected time starts here)
 * 4. send a heartbeat every few seconds; the server is the only authority on time
 * 5. on any end (user, quota, block, network, engine drop) tear down the tunnel and close the session
 *
 * The countdown shown to the user is only a local estimate anchored on the last server value.
 */
class ConnectionController(
    private val backend: Backend,
    private val engine: VpnEngine,
    private val scope: CoroutineScope,
    private val nowMillis: () -> Long = { SystemClock.elapsedRealtime() },
) {
    private val _state = MutableStateFlow(ConnectionState())
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private var job: Job? = null

    init {
        scope.launch {
            engine.detail.collect { text ->
                _state.update { if (it.phase == Phase.IDLE) it else it.copy(detail = text) }
            }
        }
    }
    private var anchorRemaining: Long? = null
    private var anchorAtMillis: Long = 0L

    /** Returns false if a session is already running. */
    fun connect(server: VpnServerInfo): Boolean {
        if (job?.isActive == true) return false
        _state.value = ConnectionState(phase = Phase.STARTING, serverId = server.id)
        job = scope.launch { runSession(server) }
        return true
    }

    fun disconnect() {
        val running = job ?: return
        if (!running.isActive) return
        _state.update { it.copy(phase = Phase.DISCONNECTING) }
        running.cancel() // the cleanup in runSession's finally block does the rest
    }

    fun dismissNotice() {
        _state.update { if (it.phase == Phase.IDLE) it.copy(notice = null) else it }
    }

    private suspend fun runSession(server: VpnServerInfo) {
        var sessionId: String? = null
        var notice: Notice? = null
        try {
            val started = backend.startSession(server.id)
            sessionId = started.sessionId
            anchor(started.isVip, started.remainingSeconds)
            _state.update { it.copy(phase = Phase.CONNECTING) }

            val profile = VpnProfile(server.name, started.ovpnConfig, started.authUsername, started.authPassword)
            withTimeoutOrNull(CONNECT_TIMEOUT_MS) { engine.connect(profile) }
                ?: throw EngineException("Timed out while connecting.")

            notice = evaluate(backend.confirmConnected(started.sessionId))
            if (notice == null) {
                _state.update { it.copy(phase = Phase.CONNECTED) }
                notice = monitor(started.sessionId, started.heartbeatIntervalSeconds)
            }
        } catch (e: CancellationException) {
            throw e // the user pressed disconnect
        } catch (e: ApiException) {
            notice = noticeFor(e)
        } catch (e: NetworkException) {
            notice = Notice.NetworkLost
        } catch (e: EngineException) {
            notice = Notice.EngineFailed(e.message ?: "unknown error")
        } catch (e: Exception) {
            notice = Notice.ServiceError(e.message ?: e.javaClass.simpleName)
        } finally {
            withContext(NonCancellable) {
                runCatching { engine.disconnect() }
                sessionId?.let { id -> runCatching { backend.stopSession(id) } }
                anchorRemaining = null
                _state.value = ConnectionState(phase = Phase.IDLE, serverId = server.id, notice = notice)
            }
        }
    }

    /** Heartbeat loop. Returns the reason the session has to end (it never returns normally). */
    private suspend fun monitor(sessionId: String, heartbeatSeconds: Int): Notice {
        val interval = heartbeatSeconds.coerceAtLeast(MIN_HEARTBEAT_SECONDS)
        var secondsSinceBeat = 0
        var lastSuccessAt = nowMillis()
        while (true) {
            delay(1_000)
            secondsSinceBeat++

            when (val engineState = engine.state.value) {
                is EngineState.Failed -> return Notice.EngineFailed(engineState.reason)
                EngineState.Disconnected -> return Notice.ConnectionLost
                else -> Unit
            }

            val left = tickRemaining()
            val exhaustedLocally = left != null && left <= 0
            if (secondsSinceBeat < interval && !exhaustedLocally) continue

            secondsSinceBeat = 0
            try {
                val result = backend.heartbeat(sessionId)
                lastSuccessAt = nowMillis()
                evaluate(result)?.let { return it }
            } catch (e: ApiException) {
                return noticeFor(e)
            } catch (e: NetworkException) {
                if (nowMillis() - lastSuccessAt > NETWORK_GRACE_MS) return Notice.NetworkLost
            }
        }
    }

    /** Null means the session is fine and the local countdown was re-anchored on the server value. */
    private fun evaluate(result: SessionState): Notice? = when (result.status) {
        SessionStatus.ACTIVE -> {
            anchor(result.isVip, result.remainingSeconds)
            null
        }
        SessionStatus.QUOTA_EXHAUSTED -> Notice.QuotaExhausted(result.resetsAtMillis)
        SessionStatus.CLOSED -> Notice.SessionEnded
    }

    private fun anchor(isVip: Boolean, remaining: Long?) {
        anchorRemaining = if (isVip) null else remaining
        anchorAtMillis = nowMillis()
        _state.update { it.copy(isVip = isVip, remainingSeconds = anchorRemaining) }
    }

    private fun tickRemaining(): Long? {
        val base = anchorRemaining ?: return null
        val left = (base - (nowMillis() - anchorAtMillis) / 1_000).coerceAtLeast(0)
        _state.update { it.copy(remainingSeconds = left) }
        return left
    }

    private fun noticeFor(e: ApiException): Notice = when (e.code) {
        "quota_exhausted" -> Notice.QuotaExhausted(
            e.details?.optString("resets_at")
                ?.takeIf { it.isNotEmpty() && it != "null" }
                ?.let { runCatching { parseIsoMillis(it) }.getOrNull() },
        )
        "device_blocked" -> Notice.Blocked
        "session_closed" -> Notice.SessionEnded
        "server_unavailable" -> Notice.ServerUnavailable
        else -> Notice.ServiceError(e.message)
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 90_000L
        const val NETWORK_GRACE_MS = 180_000L
        const val MIN_HEARTBEAT_SECONDS = 5
    }
}
