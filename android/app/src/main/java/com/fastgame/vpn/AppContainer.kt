package com.fastgame.vpn

import android.app.Application
import com.fastgame.vpn.data.ApiClient
import com.fastgame.vpn.data.Backend
import com.fastgame.vpn.data.BackendImpl
import com.fastgame.vpn.data.DeviceId
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

    private val api: FastGameApi = ApiClient(BuildConfig.API_BASE_URL)

    val backend: Backend = BackendImpl(
        api = api,
        prefs = prefs,
        hardwareId = { DeviceId.hardwareId(app, prefs) },
        versionCode = BuildConfig.APP_VERSION_CODE,
        locale = { Locale.getDefault().language },
    )

    val engine: VpnEngine = createVpnEngine(app)
    val connection = ConnectionController(backend, engine, appScope)
}
