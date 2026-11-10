package com.fastgame.vpn.ui

import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fastgame.vpn.R
import com.fastgame.vpn.data.Account
import com.fastgame.vpn.data.VpnServerInfo
import com.fastgame.vpn.util.formatDate
import com.fastgame.vpn.util.formatDuration
import com.fastgame.vpn.vpn.ConnectionState
import com.fastgame.vpn.vpn.Notice
import com.fastgame.vpn.vpn.Phase
import java.util.Locale

class HomeActions(
    val onToggle: () -> Unit,
    val onSelectServer: (Int) -> Unit,
    val onRetry: () -> Unit,
    val onCopyId: (String) -> Unit,
    val onCopyText: (String) -> Unit,
    val onBuyVip: () -> Unit,
    val onSupport: () -> Unit,
    val onUpdate: () -> Unit,
    val onPrivacy: () -> Unit,
    val onToggleLanguage: () -> Unit,
    val onDismissNotice: () -> Unit,
    val onAcceptDisclosure: () -> Unit,
    val onDeclineDisclosure: () -> Unit,
)

@Composable
fun HomeScreen(ui: HomeUiState, conn: ConnectionState, actions: HomeActions) {
    Web3Background {
        when {
            ui.loading -> Box(Modifier.fillMaxSize()) {
                FastLoader(Modifier.height(140.dp).align(Alignment.Center))
                Text(
                    stringResource(R.string.loading),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 64.dp),
                )
            }
            ui.error != null -> ErrorView(ui.error, actions)
            else -> Content(ui, conn, actions)
        }
        if (ui.showDisclosure) DisclosureDialog(actions)
    }
}

@Composable
private fun ErrorView(error: LoadError, actions: HomeActions) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(if (error == LoadError.UPDATE_REQUIRED) R.string.update_required else R.string.load_error),
            textAlign = TextAlign.Center,
        )
        if (error == LoadError.NETWORK) {
            Spacer(Modifier.height(16.dp))
            Button(onClick = actions.onRetry) { Text(stringResource(R.string.retry)) }
        }
    }
}

@Composable
private fun Content(ui: HomeUiState, conn: ConnectionState, actions: HomeActions) {
    val account = ui.account
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(28.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = actions.onToggleLanguage) { Text("فارسی / EN") }
        }
        Spacer(Modifier.height(16.dp))

        NoticeBanner(conn.notice, actions.onDismissNotice, actions.onCopyText)

        Spacer(Modifier.height(8.dp))
        ConnectButton(conn.phase, actions.onToggle)
        Spacer(Modifier.height(2.dp))
        Text(statusText(conn.phase), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(10.dp))

        InfoRow(ui.selectedServer, ui.servers, conn.phase == Phase.IDLE, actions.onSelectServer, account, conn)
        Spacer(Modifier.height(12.dp))
        if (account != null) IdCard(account.userCode, actions)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (account?.isVip != true) {
                GlassButton(stringResource(R.string.buy_vip), actions.onBuyVip, Modifier.weight(1f))
            }
            // Support only while the app is up to date; otherwise the same slot offers the update.
            val outdated = ui.config?.let { it.latestVersionCode > com.fastgame.vpn.BuildConfig.APP_VERSION_CODE && it.updateUrl.isNotBlank() } == true
            if (outdated) {
                GlassButton("\u26A0\uFE0F " + stringResource(R.string.update_app), actions.onUpdate, Modifier.weight(1f))
            } else {
                GlassButton(stringResource(R.string.support), actions.onSupport, Modifier.weight(1f))
            }
        }
        var showLicenses by remember { mutableStateOf(false) }
        Row(horizontalArrangement = Arrangement.Center) {
            TextButton(onClick = actions.onPrivacy) { Text(stringResource(R.string.privacy_policy), maxLines = 1, softWrap = false) }
            TextButton(onClick = { showLicenses = true }) { Text(stringResource(R.string.licenses), maxLines = 1, softWrap = false) }
        }
        if (showLicenses) AlertDialog(
            onDismissRequest = { showLicenses = false },
            title = { Text(stringResource(R.string.licenses)) },
            text = { Text(stringResource(R.string.licenses_body), modifier = Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { showLicenses = false }) { Text(stringResource(android.R.string.ok)) } },
        )
        Text("v${com.fastgame.vpn.BuildConfig.APP_VERSION_CODE} · build ${com.fastgame.vpn.BuildConfig.BUILD_ID}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun statusText(phase: Phase): String = stringResource(
    when (phase) {
        Phase.IDLE -> R.string.status_disconnected
        Phase.STARTING -> R.string.status_connecting
        Phase.CONNECTING -> R.string.status_connecting
        Phase.CONNECTED -> R.string.status_connected
        Phase.DISCONNECTING -> R.string.status_disconnecting
    },
)

@Composable
private fun ConnectButton(phase: Phase, onClick: () -> Unit) {
    val connected = phase == Phase.CONNECTED
    val busy = phase == Phase.STARTING || phase == Phase.CONNECTING || phase == Phase.DISCONNECTING
    val label = stringResource(if (connected) R.string.hint_tap_to_disconnect else R.string.hint_tap_to_connect)
    PowerButton(connected, busy, phase != Phase.DISCONNECTING, label, onClick)
}

@Composable
private fun NoticeBanner(notice: Notice?, onDismiss: () -> Unit, onCopy: (String) -> Unit) {
    if (notice == null) return
    val text = when (notice) {
        is Notice.QuotaExhausted -> stringResource(R.string.notice_quota_exhausted)
        Notice.Blocked -> stringResource(R.string.notice_blocked)
        Notice.SessionEnded -> stringResource(R.string.notice_session_ended)
        Notice.ConnectionLost -> stringResource(R.string.notice_connection_lost)
        Notice.NetworkLost -> stringResource(R.string.notice_network_lost)
        Notice.ServerUnavailable -> stringResource(R.string.notice_server_unavailable)
        is Notice.EngineFailed -> stringResource(R.string.notice_engine_failed, notice.reason)
        is Notice.ServiceError -> stringResource(R.string.notice_service_error, notice.message)
    }
    Box(
        Modifier
            .fillMaxWidth()
            .glass(14.dp)
            .border(1.dp, Warning.copy(alpha = 0.8f), RoundedCornerShape(14.dp)),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(text, color = Warning)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { onCopy(text) }) { Text(stringResource(R.string.copy_id)) }
                TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) }
            }
        }
    }
}

@Composable
private fun SquareCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.aspectRatio(1f).liquidGlass().padding(14.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        content = content,
    )
}

@Composable
private fun InfoRow(server: VpnServerInfo?, servers: List<VpnServerInfo>, enabled: Boolean, onSelect: (Int) -> Unit, account: Account?, conn: ConnectionState) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ServerSquare(server, servers, enabled, onSelect, Modifier.fillMaxWidth())
        TimeCountdown(account, conn, Modifier.fillMaxWidth())
    }
}

@Composable
private fun ServerSquare(selected: VpnServerInfo?, servers: List<VpnServerInfo>, enabled: Boolean, onSelect: (Int) -> Unit, modifier: Modifier) {
    var open by remember { mutableStateOf(false) }
    val fa = Locale.getDefault().language == "fa"
    val canPick = enabled && servers.size > 1
    Box(modifier.modernCard(16.dp).clickable(enabled = canPick, role = Role.Button) { open = true }.padding(horizontal = 18.dp, vertical = 14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Text(flagEmoji(selected?.countryCode), fontSize = 36.sp)
            Spacer(Modifier.width(12.dp))
            val country = selected?.let { if (fa) it.countryNameFa else it.countryNameEn }
            val number = selected?.let { Regex("(\\d+)\\s*$").find(it.name)?.value }
            Text(
                listOfNotNull(country, number).joinToString(" ").ifBlank { stringResource(R.string.choose_server) } + if (canPick) " \u25BE" else "",
                style = MaterialTheme.typography.titleMedium, maxLines = 2, textAlign = TextAlign.Center,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            servers.forEach { s ->
                DropdownMenuItem(
                    text = { Text("${flagEmoji(s.countryCode)}  ${if (fa) s.countryNameFa else s.countryNameEn} · ${s.name}") },
                    onClick = { open = false; onSelect(s.id) },
                )
            }
        }
    }
}

/** Regional-indicator pair for a two-letter country code (renders as a flag); empty when unknown. */
private fun flagEmoji(code: String?): String {
    val c = code?.uppercase()?.takeIf { it.length == 2 && it.all { ch -> ch in 'A'..'Z' } } ?: return "\uD83C\uDFF3\uFE0F"
    return String(Character.toChars(0x1F1E6 + (c[0] - 'A'))) + String(Character.toChars(0x1F1E6 + (c[1] - 'A')))
}

private const val HOUR_MS = 3_600_000L
private const val DAY_MS = 24 * HOUR_MS

/**
 * Remaining time as four glass tiles (days, hours, minutes, seconds) whose numbers slide and blur
 * when they change. The seconds tick locally every second; the server stays the source of truth
 * (the base value is refreshed on every heartbeat / account refresh).
 */
@Composable
private fun TimeCountdown(account: Account?, conn: ConnectionState, modifier: Modifier) {
    if (account == null) return
    val until = account.vipExpiresAtMillis
    val unlimited = account.isVip && (account.vipLifetime || until == null)
    val live = conn.phase != Phase.IDLE && conn.remainingSeconds != null
    val freeLeft = if (live) conn.remainingSeconds!! else account.quota.remainingSeconds

    // Base value + the moment it arrived; the tick below only moves the display between refreshes.
    val base = if (account.isVip && !unlimited) ((until!! - System.currentTimeMillis()) / 1000).coerceAtLeast(0) else freeLeft
    val ticking = account.isVip || live
    var left by remember(base, ticking) { mutableStateOf(base.coerceAtLeast(0)) }
    androidx.compose.runtime.LaunchedEffect(base, ticking) {
        if (!ticking) return@LaunchedEffect
        val start = System.nanoTime()
        while (true) {
            kotlinx.coroutines.delay(1000)
            left = (base - (System.nanoTime() - start) / 1_000_000_000L).coerceAtLeast(0)
        }
    }

    val locale = Locale.getDefault()
    val labels = listOf(R.string.unit_days, R.string.unit_hours, R.string.unit_minutes, R.string.unit_seconds)
    val values = if (unlimited) null else listOf(left / 86400, (left % 86400) / 3600, (left % 3600) / 60, left % 60)
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Ltr) {
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (i in 0..3) {
                Column(
                    Modifier.weight(1f).modernCard(16.dp).padding(vertical = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (values == null) {
                        Text("\u221E", style = TimeTextStyle, color = Accent)
                    } else {
                        AnimatedNumber(String.format(locale, "%02d", values[i]))
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(labels[i]),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

private val TimeTextStyle = androidx.compose.ui.text.TextStyle(
    fontSize = 26.sp,
    lineHeight = 32.sp,
    fontWeight = FontWeight.Bold,
    fontFeatureSettings = "tnum",
)

@Composable
private fun AnimatedNumber(text: String) {
    androidx.compose.animation.AnimatedContent(
        targetState = text,
        transitionSpec = {
            (androidx.compose.animation.slideInVertically { it / 2 } + androidx.compose.animation.fadeIn()).togetherWith(
                androidx.compose.animation.slideOutVertically { -it / 2 } + androidx.compose.animation.fadeOut(),
            )
        },
        label = "time-number",
    ) { value -> Text(value, style = TimeTextStyle, maxLines = 1, softWrap = false) }
}

@Composable
private fun IdCard(code: String, actions: HomeActions) {
    Row(
        Modifier.fillMaxWidth().modernCard(16.dp).padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.foundation.layout.Box(Modifier.weight(1f)) {
            Text(
                "ID:$code",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.semantics { contentDescription = "ID $code" },
            )
        }
        CopyPill(onCopy = { actions.onCopyId(code) })
    }
}

@Composable
private fun DisclosureDialog(actions: HomeActions) {
    AlertDialog(
        onDismissRequest = actions.onDeclineDisclosure,
        title = { Text(stringResource(R.string.disclosure_title)) },
        text = { Text(stringResource(R.string.disclosure_body)) },
        confirmButton = { TextButton(onClick = actions.onAcceptDisclosure) { Text(stringResource(R.string.disclosure_accept)) } },
        dismissButton = { TextButton(onClick = actions.onDeclineDisclosure) { Text(stringResource(R.string.disclosure_decline)) } },
    )
}
