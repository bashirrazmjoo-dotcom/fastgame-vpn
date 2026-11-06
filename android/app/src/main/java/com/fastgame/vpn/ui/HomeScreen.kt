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
import androidx.compose.material3.OutlinedButton
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
                    CircularProgressIndicator(color = Accent)
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

        NoticeBanner(conn.notice, actions.onDismissNotice)

        Spacer(Modifier.height(8.dp))
        ConnectButton(conn.phase, actions.onToggle)
        Spacer(Modifier.height(12.dp))
        Text(statusText(conn.phase), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Text(
            stringResource(if (conn.phase == Phase.CONNECTED) R.string.hint_tap_to_disconnect else R.string.hint_tap_to_connect),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
        )
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
                Button(onClick = actions.onBuyVip, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.buy_vip)) }
            }
            OutlinedButton(onClick = actions.onSupport, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.support)) }
        }
        TextButton(onClick = actions.onPrivacy) { Text(stringResource(R.string.privacy_policy)) }
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
    val label = stringResource(if (connected) R.string.hint_tap_to_disconnect else R.string.hint_tap_to_connect)
    val busy = phase == Phase.STARTING || phase == Phase.CONNECTING || phase == Phase.DISCONNECTING
    Box(
        Modifier
            .size(176.dp)
            .background(
                Brush.radialGradient(listOf((if (connected) Accent else AccentViolet).copy(alpha = 0.35f), Color.Transparent)),
                CircleShape,
            )
            .padding(8.dp)
            .clip(CircleShape)
            .background(if (connected) NeonBrush else Brush.linearGradient(listOf(Color(0x33FFFFFF), Color(0x0FFFFFFF))))
            .border(2.dp, NeonBrush, CircleShape)
            .semantics { contentDescription = label }
            .clickable(enabled = phase != Phase.DISCONNECTING, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) {
            CircularProgressIndicator(color = Accent, modifier = Modifier.size(56.dp))
        } else {
            PowerIcon(if (connected) Color(0xFF031A16) else Accent)
        }
    }
}

@Composable
private fun PowerIcon(color: Color) {
    androidx.compose.foundation.Canvas(Modifier.size(64.dp)) {
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = 7.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
        val pad = 8.dp.toPx()
        drawArc(
            color, startAngle = -60f, sweepAngle = 300f, useCenter = false, style = stroke,
            topLeft = androidx.compose.ui.geometry.Offset(pad, pad + 4.dp.toPx()),
            size = androidx.compose.ui.geometry.Size(size.width - 2 * pad, size.height - 2 * pad - 4.dp.toPx()),
        )
        drawLine(color, androidx.compose.ui.geometry.Offset(size.width / 2, 4.dp.toPx()), androidx.compose.ui.geometry.Offset(size.width / 2, size.height * 0.5f), 7.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
    }
}

@Composable
private fun NoticeBanner(notice: Notice?, onDismiss: () -> Unit) {
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
            .border(1.dp, Warning.copy(alpha = 0.8f), RoundedCornerShape(14.dp))
            .clickable(onClick = onDismiss),
    ) {
        Text(text, Modifier.padding(14.dp), color = Warning)
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().glass().padding(16.dp)) { content() }
}

@Composable
private fun SquareCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.aspectRatio(1f).glass().padding(14.dp),
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
        Text(stringResource(R.string.label_server), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // A code badge instead of a flag emoji: emoji must not be used as icons.
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(NeonBrush),
                contentAlignment = Alignment.Center,
            ) { Text(selected?.countryCode?.uppercase() ?: "--", color = Color(0xFF031A16), fontWeight = FontWeight.Bold) }
            Text(
                selected?.let { if (fa) it.countryNameFa else it.countryNameEn } ?: stringResource(R.string.choose_server),
                fontWeight = FontWeight.SemiBold, fontSize = 18.sp, maxLines = 2,
            )
        }
        Text(selected?.name.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, maxLines = 1)
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            servers.forEach { s ->
                DropdownMenuItem(
                    text = { Text("${s.countryCode.uppercase()}  ${if (fa) s.countryNameFa else s.countryNameEn} · ${s.name}") },
                    onClick = { open = false; onSelect(s.id) },
                )
            }
        }
    }
}

@Composable
private fun TimeSquare(account: Account?, conn: ConnectionState, modifier: Modifier) {
    val locale = Locale.getDefault()
    SquareCard(modifier) {
        if (account == null) return@SquareCard
        if (account.isVip) {
            Text(stringResource(R.string.label_vip), color = Accent, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text("∞", fontSize = 44.sp, fontWeight = FontWeight.Bold)
            Text(
                if (account.vipLifetime || account.vipExpiresAtMillis == null) stringResource(R.string.vip_lifetime)
                else stringResource(R.string.vip_until, formatDate(account.vipExpiresAtMillis, locale)),
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
            )
        } else {
            val live = conn.phase != Phase.IDLE && conn.remainingSeconds != null
            val left = if (live) conn.remainingSeconds!! else account.quota.remainingSeconds
            Text(stringResource(R.string.label_time_left), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            Text(formatDuration(left, locale), fontSize = 28.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            val total = (account.quota.totalSeconds / 60).toInt()
            val reset = account.quota.resetsAtMillis
            Text(
                if (reset != null && account.quota.usedSeconds > 0)
                    stringResource(R.string.resets_in, formatDuration(((reset - System.currentTimeMillis()) / 1000).coerceAtLeast(0), locale))
                else stringResource(R.string.free_plan_note, total),
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
            )
        }
    }
}

@Composable
private fun IdCard(code: String, actions: HomeActions) {
    Card {
        Text(stringResource(R.string.label_your_id), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(code, fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            TextButton(onClick = { actions.onCopyId(code) }) { Text(stringResource(R.string.copy_id)) }
        }
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
