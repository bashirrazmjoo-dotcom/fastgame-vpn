package com.fastgame.vpn.ui

import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
            ui.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    FastLoader(Modifier.size(120.dp))
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.loading), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
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
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.app_name), fontSize = 22.sp, fontWeight = FontWeight.Bold)
            TextButton(onClick = actions.onToggleLanguage) { Text("فارسی / EN") }
        }
        Spacer(Modifier.height(16.dp))

        NoticeBanner(conn.notice, actions.onDismissNotice, actions.onCopyText)

        Spacer(Modifier.height(8.dp))
        ConnectButton(conn.phase, actions.onToggle)
        Spacer(Modifier.height(12.dp))
        Text(statusText(conn.phase), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        if (conn.phase != Phase.IDLE && conn.phase != Phase.CONNECTED && !conn.detail.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(conn.detail, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(20.dp))

        InfoRow(ui.selectedServer, ui.servers, conn.phase == Phase.IDLE, actions.onSelectServer, account, conn)
        Spacer(Modifier.height(12.dp))
        if (account != null) IdCard(account.userCode, actions)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (account?.isVip != true) {
                GlassButton(stringResource(R.string.buy_vip), actions.onBuyVip, Modifier.weight(1f))
            }
            GlassButton(stringResource(R.string.support), actions.onSupport, Modifier.weight(1f))
        }
        TextButton(onClick = actions.onPrivacy) { Text(stringResource(R.string.privacy_policy)) }
        Text("build ${com.fastgame.vpn.BuildConfig.BUILD_ID}", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), fontSize = 11.sp)
    }
}

@Composable
private fun statusText(phase: Phase): String = stringResource(
    when (phase) {
        Phase.IDLE -> R.string.status_disconnected
        Phase.STARTING -> R.string.status_starting
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
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ServerSquare(server, servers, enabled, onSelect, Modifier.weight(1f))
        TimeSquare(account, conn, Modifier.weight(1f))
    }
}

@Composable
private fun ServerSquare(selected: VpnServerInfo?, servers: List<VpnServerInfo>, enabled: Boolean, onSelect: (Int) -> Unit, modifier: Modifier) {
    var open by remember { mutableStateOf(false) }
    val fa = Locale.getDefault().language == "fa"
    val canPick = enabled && servers.size > 1
    SquareCard(modifier.clickable(enabled = canPick, role = Role.Button) { open = true }) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(flagEmoji(selected?.countryCode), fontSize = 52.sp)
            Spacer(Modifier.height(8.dp))
            val country = selected?.let { if (fa) it.countryNameFa else it.countryNameEn }
            val number = selected?.let { Regex("(\\d+)\\s*$").find(it.name)?.value }
            Text(
                listOfNotNull(country, number).joinToString(" ").ifBlank { stringResource(R.string.choose_server) } + if (canPick) " \u25BE" else "",
                fontWeight = FontWeight.SemiBold, fontSize = 18.sp, maxLines = 2, textAlign = TextAlign.Center,
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

@Composable
private fun TimeSquare(account: Account?, conn: ConnectionState, modifier: Modifier) {
    SquareCard(modifier) {
        if (account == null) return@SquareCard
        if (account.isVip) {
            Text(stringResource(R.string.label_vip), color = Accent, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            val until = account.vipExpiresAtMillis
            if (account.vipLifetime || until == null) {
                Gauge(1f, Modifier.weight(1f).fillMaxWidth()) {
                    Text("\u221E", fontSize = 40.sp, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.vip_lifetime), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, textAlign = TextAlign.Center)
                }
            } else {
                val leftMs = (until - System.currentTimeMillis()).coerceAtLeast(0)
                val days = (leftMs + DAY_MS - 1) / DAY_MS
                val hours = ((leftMs + HOUR_MS - 1) / HOUR_MS).coerceAtLeast(1)
                Gauge((leftMs.toFloat() / (30 * DAY_MS)).coerceIn(0f, 1f), Modifier.weight(1f).fillMaxWidth()) {
                    if (leftMs >= DAY_MS) {
                        Text(java.text.NumberFormat.getInstance(Locale.getDefault()).format(days), fontSize = 30.sp, fontWeight = FontWeight.Bold)
                        Text(stringResource(R.string.unit_days), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                    } else {
                        Text(java.text.NumberFormat.getInstance(Locale.getDefault()).format(hours), fontSize = 30.sp, fontWeight = FontWeight.Bold)
                        Text(stringResource(R.string.unit_hours), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                    }
                }
            }
        } else {
            val live = conn.phase != Phase.IDLE && conn.remainingSeconds != null
            val left = if (live) conn.remainingSeconds!! else account.quota.remainingSeconds
            val total = account.quota.totalSeconds.coerceAtLeast(1)
            val minutes = (left.coerceAtLeast(0) + 59) / 60 // round up: never "0 min" while time is left
            Text(stringResource(R.string.label_time_left), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            Gauge((left.toFloat() / total).coerceIn(0f, 1f), Modifier.weight(1f).fillMaxWidth()) {
                if (minutes >= 60) Text(stringResource(R.string.time_hours, (minutes / 60).toInt()), fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text(
                    stringResource(R.string.time_minutes, (minutes % 60).toInt()),
                    fontSize = if (minutes >= 60) 15.sp else 20.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

private const val HOUR_MS = 3_600_000L
private const val DAY_MS = 24 * HOUR_MS

/** Circular gauge: a faint track and an arc that shrinks with [fraction]; green, then amber, then rose. */
@Composable
private fun Gauge(fraction: Float, modifier: Modifier = Modifier, center: @Composable ColumnScope.() -> Unit) {
    val color = when {
        fraction > 0.66f -> Color(0xFF10B981)
        fraction > 0.33f -> Color(0xFFF59E0B)
        else -> Color(0xFFF43F5E)
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize().padding(2.dp)) {
            val side = minOf(size.width, size.height)
            val stroke = 8.dp.toPx()
            val topLeft = androidx.compose.ui.geometry.Offset((size.width - side) / 2 + stroke / 2, (size.height - side) / 2 + stroke / 2)
            val arcSize = androidx.compose.ui.geometry.Size(side - stroke, side - stroke)
            val cap = androidx.compose.ui.graphics.StrokeCap.Round
            drawArc(Color(0x26FFFFFF), 0f, 360f, false, topLeft, arcSize, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
            if (fraction > 0f) {
                // starts at 12 o'clock and the remaining part is drawn counter-clockwise
                drawArc(color, -90f, -360f * fraction, false, topLeft, arcSize, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = cap))
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center, content = center)
    }
}

@Composable
private fun IdCard(code: String, actions: HomeActions) {
    Row(
        Modifier.fillMaxWidth().liquidGlass(16.dp).padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(stringResource(R.string.label_your_id), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            Text(code, fontSize = 18.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        }
        TextButton(onClick = { actions.onCopyId(code) }) { Text(stringResource(R.string.copy_id)) }
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
