package com.fastgame.vpn.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.fastgame.vpn.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private class AppEntry(val pkg: String, val label: String)

/** Menu glyph: four small rounded squares. */
@Composable
fun AppsGlyph(color: Color) {
    Canvas(Modifier.size(20.dp)) {
        val gap = 3.dp.toPx()
        val s = (size.width - gap) / 2
        for (i in 0..1) for (j in 0..1) {
            drawRoundRect(color, Offset(i * (s + gap), j * (s + gap)), Size(s, s), CornerRadius(2.5.dp.toPx()))
        }
    }
}

/** Pick which apps go through the VPN. Off = all apps (as before). */
@Composable
fun AppFilterDialog(
    initialEnabled: Boolean,
    initialApps: Set<String>,
    onSave: (Boolean, Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(initialEnabled) }
    val chosen = remember { mutableStateListOf<String>().apply { addAll(initialApps) } }
    var query by remember { mutableStateOf("") }

    val apps by produceState<List<AppEntry>?>(null) {
        value = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(launcher, 0)
                .map { AppEntry(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
                .filter { it.pkg != context.packageName }
                .distinctBy { it.pkg }
                .sortedBy { it.label.lowercase() }
        }
    }

    fun finish() {
        onSave(enabled, chosen.toSet())
        onDismiss()
    }

    Dialog(onDismissRequest = ::finish, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.86f),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.split_title), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.split_switch), Modifier.weight(1f))
                    Switch(enabled, { enabled = it })
                }
                Text(
                    stringResource(if (enabled && chosen.isEmpty()) R.string.split_none else R.string.split_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                if (enabled) {
                    OutlinedTextField(
                        query, { query = it },
                        Modifier.fillMaxWidth(), singleLine = true,
                        placeholder = { Text(stringResource(R.string.split_search)) },
                    )
                    Spacer(Modifier.height(6.dp))
                    val list = apps
                    val shown = list?.filter { query.isBlank() || it.label.contains(query, true) }.orEmpty()
                    LazyColumn(Modifier.weight(1f)) {
                        items(shown, key = { it.pkg }) { app ->
                            val on = app.pkg in chosen
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable { if (on) chosen.remove(app.pkg) else chosen.add(app.pkg) }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                AppIcon(app.pkg)
                                Text(app.label, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Checkbox(on, { if (it) chosen.add(app.pkg) else chosen.remove(app.pkg) })
                            }
                        }
                    }
                } else {
                    Box(Modifier.weight(1f).fillMaxWidth())
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(::finish) { Text(stringResource(R.string.split_done)) }
                }
            }
        }
    }
}

@Composable
private fun AppIcon(pkg: String) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(null, pkg) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val d = context.packageManager.getApplicationIcon(pkg)
                val b = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
                d.setBounds(0, 0, 96, 96)
                d.draw(AndroidCanvas(b))
                b
            }.getOrNull()
        }
    }
    Box(Modifier.size(40.dp)) {
        bitmap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize()) }
    }
}
