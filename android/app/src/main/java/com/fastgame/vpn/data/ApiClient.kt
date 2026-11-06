package com.fastgame.vpn.data

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The service answered with an error. [code] is the API error code, e.g. "quota_exhausted". */
class ApiException(
    val httpStatus: Int,
    val code: String,
    override val message: String,
    val details: JSONObject?,
) : Exception(message)

/** The service could not be reached (offline, DNS, TLS, timeout...). */
class NetworkException(cause: Throwable) : Exception(cause.message, cause)

/** Raw API, one function per endpoint. Authentication tokens are passed in by the caller. */
interface FastGameApi {
    suspend fun config(): AppConfig
    suspend fun register(hardwareId: String, versionCode: Int, locale: String): Registration
    suspend fun me(token: String): Account
    suspend fun servers(token: String): List<VpnServerInfo>
    suspend fun startSession(token: String, serverId: Int): StartedSession
    suspend fun confirmConnected(token: String, sessionId: String): SessionState
    suspend fun heartbeat(token: String, sessionId: String): SessionState
    suspend fun stopSession(token: String, sessionId: String): SessionState
}

class ApiClient(
    baseUrl: String,
    private val client: OkHttpClient = defaultClient(),
) : FastGameApi {

    private val base = baseUrl.trimEnd('/')

    override suspend fun config() = JSONObject(execute("/v1/config")).toAppConfig()

    override suspend fun register(hardwareId: String, versionCode: Int, locale: String): Registration {
        val body = JSONObject()
            .put("hardware_id", hardwareId)
            .put("app_version_code", versionCode)
            .put("locale", locale.take(8))
        return JSONObject(execute("/v1/devices/register", body = body, post = true)).toRegistration()
    }

    override suspend fun me(token: String) = JSONObject(execute("/v1/me", token)).toAccount()

    override suspend fun servers(token: String): List<VpnServerInfo> {
        val array = JSONArray(execute("/v1/servers", token))
        return List(array.length()) { array.getJSONObject(it).toServerInfo() }
    }

    override suspend fun startSession(token: String, serverId: Int): StartedSession {
        val body = JSONObject().put("server_id", serverId)
        return JSONObject(execute("/v1/sessions", token, body, post = true)).toStartedSession()
    }

    override suspend fun confirmConnected(token: String, sessionId: String) =
        JSONObject(execute("/v1/sessions/$sessionId/connected", token, post = true)).toSessionState()

    override suspend fun heartbeat(token: String, sessionId: String) =
        JSONObject(execute("/v1/sessions/$sessionId/heartbeat", token, post = true)).toSessionState()

    override suspend fun stopSession(token: String, sessionId: String) =
        JSONObject(execute("/v1/sessions/$sessionId/stop", token, post = true)).toSessionState()

    private suspend fun execute(
        path: String,
        token: String? = null,
        body: JSONObject? = null,
        post: Boolean = false,
    ): String {
        val builder = Request.Builder().url(base + path).header("Accept", "application/json")
        if (token != null) builder.header("Authorization", "Bearer $token")
        if (post) builder.post((body?.toString() ?: "").toRequestBody(JSON))

        val response = client.newCall(builder.build()).await()
        response.use { r ->
            val text = r.body?.string().orEmpty()
            if (r.isSuccessful) return text
            throw parseError(r.code, text)
        }
    }

    private fun parseError(status: Int, text: String): ApiException = try {
        val error = JSONObject(text).getJSONObject("error")
        ApiException(
            status,
            error.optString("code", "http_$status"),
            error.optString("message", "HTTP $status"),
            error.optJSONObject("details"),
        )
    } catch (_: Exception) {
        ApiException(status, "http_$status", "HTTP $status", null)
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(NetworkException(e))
            }

            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response)
            }
        })
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .build()
    }
}
