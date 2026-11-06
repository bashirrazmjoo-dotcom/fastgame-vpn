package com.fastgame.vpn

import android.os.Build
import de.blinkt.openvpn.core.ICSOpenVPNApplication
import java.io.File

/**
 * Extends the engine's application class: it sets up the engine's status listener and
 * notification channels. The engine's VPN service runs in a second process (":openvpn") that
 * also creates this class, so the container is created lazily and only used in the main process.
 */
class FastGameApp : ICSOpenVPNApplication() {
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        installCrashRecorder() // before anything else, so a crash during startup is recorded too
        super.onCreate()
    }

    /** Writes the stack trace of an uncaught exception (any process) to a file the app can show. */
    private fun installCrashRecorder() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val process = if (Build.VERSION.SDK_INT >= 28) getProcessName() else "app"
        val file = File(filesDir, CRASH_FILE)
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                file.writeText("process=$process thread=${thread.name}\n" + error.stackTraceToString().take(3000))
            }
            previous?.uncaughtException(thread, error)
        }
    }

    companion object {
        const val CRASH_FILE = "last_crash.txt"
    }
}
