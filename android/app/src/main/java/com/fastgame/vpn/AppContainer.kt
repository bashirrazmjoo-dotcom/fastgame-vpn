package com.fastgame.vpn

import android.app.Application
import com.fastgame.vpn.data.ApiClient
import com.fastgame.vpn.data.Backend
import com.fastgame.vpn.data.BackendImpl
import com.fastgame.vpn.data.DeviceId
import com.fastgame.vpn.data.Endpoint
import com.fastgame.vpn.data.FastGameApi
import com.fastgame.vpn.data.Prefs
import com.fastgame.vpn.vpn.ConnectionController
import com.fastgame.vpn.vpn.VpnEngine
import com.fastgame.vpn.vpn.createVpnEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.util.Locale

/** Hand-written dependency container: one instance for the whole process. */
class AppContainer(app: Application) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val prefs = Prefs(app)

    private val api: FastGameApi = ApiClient(
        listOf(
            Endpoint(BuildConfig.API_BASE_URL, ApiClient.defaultClient()),
            Endpoint(
                BuildConfig.API_FALLBACK_URL,
                ApiClient.pinnedClient(BuildConfig.API_FALLBACK_CERT_SHA256),
                BuildConfig.API_FALLBACK_HOST,
            ),
        ),
    )

    val backend: Backend = BackendImpl(
        api = api,
        prefs = prefs,
        hardwareId = { DeviceId.hardwareId(app, prefs) },
        versionCode = BuildConfig.APP_VERSION_CODE,
        locale = { Locale.getDefault().language },
    )

    val engine: VpnEngine = createVpnEngine(app)
    val connection = ConnectionController(backend, engine, appScope, onlyApps = { if (prefs.splitEnabled) prefs.splitApps.ifEmpty { null } else null })

    init {
        // Tell the controller when the underlying network changes (the VPN itself is excluded).
        runCatching {
            val cm = app.getSystemService(android.net.ConnectivityManager::class.java)
            val request = android.net.NetworkRequest.Builder()
                .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            var last: android.net.Network? = null
            cm.registerNetworkCallback(request, object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    if (last != null && last != network) connection.onNetworkChanged()
                    last = network
                }

                override fun onLost(network: android.net.Network) {
                    if (last == network) last = null
                    connection.onNetworkChanged()
                }
            })
        }
    }
}
