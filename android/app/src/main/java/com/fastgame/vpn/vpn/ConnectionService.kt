package com.fastgame.vpn.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.fastgame.vpn.FastGameApp
import com.fastgame.vpn.MainActivity
import com.fastgame.vpn.R
import com.fastgame.vpn.util.formatDuration
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the app process (and with it the heartbeat loop of the
 * [ConnectionController]) alive while a VPN session runs, and shows the ongoing notification.
 * It stops itself as soon as the controller is idle again.
 */
class ConnectionService : Service() {

    private var observer: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val container = (application as FastGameApp).container

        if (intent?.action == ACTION_DISCONNECT) container.connection.disconnect()

        createChannel()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(container.connection.state.value), type)

        if (observer == null) {
            observer = container.appScope.launch {
                container.connection.state.collect { state ->
                    if (state.phase == Phase.IDLE) {
                        stopSelf()
                    } else {
                        getSystemService(NotificationManager::class.java)
                            .notify(NOTIFICATION_ID, buildNotification(state))
                    }
                }
            }
        }
        return START_NOT_STICKY // if the OS kills us the session simply times out on the server
    }

    override fun onDestroy() {
        observer?.cancel()
        observer = null
        super.onDestroy()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(state: ConnectionState): Notification {
        val text = when (state.phase) {
            Phase.IDLE, Phase.STARTING, Phase.CONNECTING -> getString(R.string.notification_connecting)
            Phase.CONNECTED ->
                if (state.isVip) {
                    getString(R.string.notification_connected_vip)
                } else {
                    getString(R.string.notification_connected, formatDuration(state.remainingSeconds ?: 0))
                }
            Phase.DISCONNECTING -> getString(R.string.notification_disconnecting)
        }
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        val disconnect = PendingIntent.getService(
            this, 1, Intent(this, ConnectionService::class.java).setAction(ACTION_DISCONNECT), flags,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_vpn)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .addAction(0, getString(R.string.action_disconnect), disconnect)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private companion object {
        const val CHANNEL_ID = "vpn_connection"
        const val NOTIFICATION_ID = 1
        const val ACTION_DISCONNECT = "com.fastgame.vpn.action.DISCONNECT"
    }
}
