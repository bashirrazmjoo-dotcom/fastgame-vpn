package com.fastgame.vpn

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fastgame.vpn.ui.FastGameTheme
import com.fastgame.vpn.ui.HomeActions
import com.fastgame.vpn.ui.HomeScreen
import com.fastgame.vpn.ui.MainViewModel
import com.fastgame.vpn.vpn.Phase
import java.util.Locale

private const val SUPPORT_URL = "https://t.me/fastgamevpn"
private const val SHARE_URL = "https://github.com/bashirrazmjoo-dotcom/fastgame-vpn/releases/download/stable/FastGameVPN.apk"

class MainActivity : AppCompatActivity() {

    private val container get() = (application as FastGameApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // First launch: Persian unless a language was already chosen.
        if (AppCompatDelegate.getApplicationLocales().isEmpty) AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("fa"))
        val uiPrefs = getSharedPreferences("ui", MODE_PRIVATE)
        com.fastgame.vpn.ui.Pal.light = uiPrefs.getBoolean("light", true)
        setContent {
            FastGameTheme {
                val vm: MainViewModel = viewModel(factory = object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(container) as T
                })
                val ui by vm.ui.collectAsStateWithLifecycle()
                val conn by vm.connection.collectAsStateWithLifecycle()

                fun startService() {
                    // The OpenVPN engine runs as the foreground VPN service (one "connected" notification)
                    // and keeps the process, and with it the heartbeat loop, alive.
                    vm.startConnection()
                }

                val vpnPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
                    if (r.resultCode == RESULT_OK) startService()
                }
                fun askVpnPermission() {
                    val intent = VpnService.prepare(this)
                    if (intent == null) startService() else vpnPermission.launch(intent)
                }
                val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                    askVpnPermission() // connect even if the user declines notifications
                }
                fun beginConnect() {
                    val needsNotif = Build.VERSION.SDK_INT >= 33 &&
                        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    if (needsNotif) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else askVpnPermission()
                }

                val actions = HomeActions(
                    onToggle = {
                        if (conn.phase == Phase.IDLE) {
                            if (vm.requestConnect()) beginConnect()
                        } else {
                            vm.disconnect()
                        }
                    },
                    onCopyText = { text ->
                        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("FastGame", text))
                        Toast.makeText(this, R.string.details_copied, Toast.LENGTH_SHORT).show()
                    },
                    onSelectServer = vm::selectServer,
                    onRetry = vm::load,
                    onCopyId = { code ->
                        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("FastGame ID", code))
                        Toast.makeText(this, R.string.id_copied, Toast.LENGTH_SHORT).show()
                    },
                    onBuyVip = { ui.account?.let { openInCustomTab(it.vipPurchaseUrl) } },
                    onSupport = { open(SUPPORT_URL) },
                    onShare = {
                        val link = ui.config?.updateUrl?.takeIf { it.isNotBlank() } ?: SHARE_URL
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, getString(R.string.share_text, link))
                        }
                        startActivity(Intent.createChooser(send, getString(R.string.share_app)))
                    },
                    onUpdate = { ui.config?.updateUrl?.takeIf { it.isNotBlank() }?.let { open(it) } },
                    onPrivacy = { ui.config?.let { open(it.privacyPolicyUrl) } },
                    onToggleLanguage = {
                        val next = if (Locale.getDefault().language == "fa") "en" else "fa"
                        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(next))
                    },
                    onToggleTheme = {
                        com.fastgame.vpn.ui.Pal.light = !com.fastgame.vpn.ui.Pal.light
                        uiPrefs.edit().putBoolean("light", com.fastgame.vpn.ui.Pal.light).apply()
                    },
                    onDismissNotice = vm::dismissNotice,
                    onAcceptDisclosure = { if (vm.acceptDisclosure()) beginConnect() },
                    onDeclineDisclosure = vm::declineDisclosure,
                    loadAppFilter = vm::appFilter,
                    onSaveAppFilter = vm::saveAppFilter,
                )
                HomeScreen(ui, conn, actions)

                // Android's idle mode (Doze) pauses the heartbeat after a while with the screen off; the
                // user is asked once, after the first connection, to let the app run in the background.
                var askBattery by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
                androidx.compose.runtime.LaunchedEffect(conn.phase) {
                    val pm = getSystemService(android.os.PowerManager::class.java)
                    if (conn.phase == com.fastgame.vpn.vpn.Phase.CONNECTED && !pm.isIgnoringBatteryOptimizations(packageName) &&
                        !uiPrefs.getBoolean("battery_asked", false)
                    ) askBattery = true
                }
                if (askBattery) {
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { askBattery = false },
                        title = { androidx.compose.material3.Text(getString(R.string.battery_title)) },
                        text = { androidx.compose.material3.Text(getString(R.string.battery_text)) },
                        confirmButton = {
                            androidx.compose.material3.TextButton(onClick = {
                                askBattery = false
                                uiPrefs.edit().putBoolean("battery_asked", true).apply()
                                runCatching {
                                    startActivity(
                                        Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")),
                                    )
                                }
                            }) { androidx.compose.material3.Text(getString(R.string.battery_allow)) }
                        },
                        dismissButton = {
                            androidx.compose.material3.TextButton(onClick = {
                                askBattery = false
                                uiPrefs.edit().putBoolean("battery_asked", true).apply()
                            }) { androidx.compose.material3.Text(getString(R.string.battery_later)) }
                        },
                    )
                }
            }
        }
    }

    /** Opens the page in a Chrome Custom Tab (falls back to the default browser). */
    private fun openInCustomTab(url: String) {
        runCatching { androidx.browser.customtabs.CustomTabsIntent.Builder().build().launchUrl(this, Uri.parse(url)) }
            .onFailure { open(url) }
    }

    private fun open(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }
}
