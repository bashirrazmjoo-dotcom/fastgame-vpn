package com.fastgame.vpn.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What the rest of the app talks to: the API without any token handling. */
interface Backend {
    suspend fun config(): AppConfig
    suspend fun account(): Account
    suspend fun servers(): List<VpnServerInfo>
    suspend fun startSession(serverId: Int): StartedSession
    suspend fun confirmConnected(sessionId: String): SessionState
    suspend fun heartbeat(sessionId: String): SessionState
    suspend fun stopSession(sessionId: String): SessionState
}

/**
 * Registers the device on first use, stores its token, and transparently registers again
 * (same hardware id gives the same account) if the server says the token is no longer valid.
 */
class BackendImpl(
    private val api: FastGameApi,
    private val prefs: Prefs,
    private val hardwareId: () -> String,
    private val versionCode: Int,
    private val locale: () -> String,
) : Backend {

    private val registerLock = Mutex()

    private suspend fun ensureToken(): String {
        prefs.deviceToken?.let { return it }
        return registerLock.withLock { prefs.deviceToken ?: register() }
    }

    private suspend fun register(): String {
        val registration = api.register(hardwareId(), versionCode, locale())
        prefs.deviceToken = registration.deviceToken
        prefs.userCode = registration.userCode
        return registration.deviceToken
    }

    private suspend fun <T> authed(call: suspend (token: String) -> T): T {
        val token = ensureToken()
        try {
            return call(token)
        } catch (e: ApiException) {
            if (e.httpStatus != 401) throw e
        }
        prefs.deviceToken = null
        return call(ensureToken())
    }

    override suspend fun config() = api.config()
    override suspend fun account() = authed { api.me(it) }
    override suspend fun servers() = authed { api.servers(it) }
    override suspend fun startSession(serverId: Int) = authed { api.startSession(it, serverId) }
    override suspend fun confirmConnected(sessionId: String) = authed { api.confirmConnected(it, sessionId) }
    override suspend fun heartbeat(sessionId: String) = authed { api.heartbeat(it, sessionId) }
    override suspend fun stopSession(sessionId: String) = authed { api.stopSession(it, sessionId) }
}
