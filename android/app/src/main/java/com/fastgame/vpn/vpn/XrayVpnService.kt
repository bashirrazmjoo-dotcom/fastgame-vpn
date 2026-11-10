package com.fastgame.vpn.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import com.fastgame.vpn.R
import go.Seq
import libv2ray.CoreCallbackHandler
import libv2ray.Libv2ray
import kotlin.concurrent.thread

/**
 * Owns the TUN interface for Xray sessions and runs the Xray core on it.
 * The app itself is excluded from the VPN so the core's own connection to the server (and the
 * app's API calls) never loop back into the tunnel.
 */
class XrayVpnService : VpnService() {
    private var tun: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }
        val config = XrayRuntime.pendingConfig
        XrayRuntime.pendingConfig = null
        if (config == null) {
            XrayRuntime.state.value = EngineState.Failed("No configuration to start.")
            stopSelf()
            return START_NOT_STICKY
        }
        runCatching { startForeground(NOTIFICATION_ID, notification()) } // may be refused in the background
        thread(name = "xray-start") { start(config) }
        return START_NOT_STICKY
    }

    private fun start(config: String) {
        try {
            Seq.setContext(applicationContext)
            Libv2ray.initCoreEnv(filesDir.absolutePath, "")
            val builder = Builder()
                .setSession(getString(R.string.app_name))
                .setMtu(MTU)
                .addAddress("10.8.0.2", 30)
                .addRoute("0.0.0.0", 0)
                .addAddress("fd00:8::2", 126)
                .addRoute("::", 0)
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")
                .setMetered(false)
            builder.addDisallowedApplication(packageName)
            val pfd = builder.establish() ?: throw IllegalStateException("The VPN permission was revoked.")
            tun = pfd
            val controller = Libv2ray.newCoreController(object : CoreCallbackHandler {
                override fun startup(): Long = 0
                override fun shutdown(): Long = 0
                override fun onEmitStatus(code: Long, message: String?): Long = 0
            })
            XrayRuntime.controller = controller
            controller.startLoop(config, pfd.fd)
            if (!controller.isRunning) throw IllegalStateException("The core did not start.")
            XrayRuntime.state.value = EngineState.Connected
        } catch (e: Throwable) {
            Log.w(TAG, "Xray start failed", e)
            XrayRuntime.state.value = EngineState.Failed(e.message ?: e.javaClass.simpleName)
            shutdown()
        }
    }

    private fun shutdown() {
        runCatching { XrayRuntime.controller?.stopLoop() }
        XrayRuntime.controller = null
        runCatching { tun?.close() }
        tun = null
        if (XrayRuntime.state.value is EngineState.Connected) XrayRuntime.state.value = EngineState.Disconnected
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    // The system revoked the VPN (another VPN app took over, or the user turned it off).
    override fun onRevoke() = shutdown()

    override fun onDestroy() {
        if (tun != null) shutdown()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_vpn)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.status_connected))
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.fastgame.vpn.XRAY_STOP"
        private const val TAG = "XrayVpnService"
        private const val CHANNEL_ID = "xray_vpn"
        private const val NOTIFICATION_ID = 2
        private const val MTU = 1500
    }
}
