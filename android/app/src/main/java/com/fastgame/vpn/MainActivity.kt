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

class MainActivity : AppCompatActivity() {

    private val container get() = (application as FastGameApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                    onPrivacy = { ui.config?.let { open(it.privacyPolicyUrl) } },
                    onToggleLanguage = {
                        val next = if (Locale.getDefault().language == "fa") "en" else "fa"
                        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(next))
                    },
                    onDismissNotice = vm::dismissNotice,
                    onAcceptDisclosure = { if (vm.acceptDisclosure()) beginConnect() },
                    onDeclineDisclosure = vm::declineDisclosure,
                )
                HomeScreen(ui, conn, actions)
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
