package com.stocksdetective.app.ui

import com.stocksdetective.app.Deps
import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stocksdetective.app.ai.AiStatus
import com.stocksdetective.app.background.BackgroundInvestigator
import com.stocksdetective.app.data.HoldingsEditor
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(deps: Deps) {
    val model = deps.model
    val settings = deps.settings
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var alerts by remember { mutableStateOf(settings.alertsEnabled) }
    var sensitivity by remember { mutableFloatStateOf(settings.alertSensitivity.toFloat()) }
    var confirmReset by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { model.ai.refreshStatus() }

    fun enableAlerts(on: Boolean) {
        alerts = on
        settings.alertsEnabled = on
        if (on) BackgroundInvestigator.schedule(context) else BackgroundInvestigator.cancel(context)
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> enableAlerts(granted) }

    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }) }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Section(
                "On-device AI",
                "Gemini Nano runs entirely on this phone through Android AICore: private, offline and fast. It reads the top 5 headlines per stock. Phones without it get the built-in rule-based analyst.",
            ) {
                when (val s = model.ai.status) {
                    AiStatus.Available -> Status(Icons.Filled.PhoneAndroid, "Gemini Nano is ready. Runs entirely on this phone. Works offline.")
                    AiStatus.Checking -> Status(Icons.Filled.PhoneAndroid, "Checking for on-device AI…")
                    AiStatus.Downloadable -> {
                        Status(Icons.Filled.Download, "This phone supports Gemini Nano. Download the model (about 1 GB, over Wi-Fi) to get AI investigations.")
                        FilledTonalButton(onClick = { scope.launch { model.ai.download(); model.clearReports() } }) { Text("Download Gemini Nano") }
                    }
                    is AiStatus.Downloading -> Status(
                        Icons.Filled.Download,
                        "Downloading Gemini Nano… ${if (s.bytes > 0) "${s.bytes / 1_000_000} MB" else ""}. Using the rule-based analyst for now.",
                    )
                    is AiStatus.Unavailable -> Status(Icons.Filled.Warning, s.reason, secondary = true)
                }
            }

            Section(
                "Continuous investigation",
                "While the app is closed, the detective checks your portfolio in the background (about every 30 minutes) and notifies you only about stock-specific moves, not ordinary market swings.",
            ) {
                ToggleRow("Alert me about unusual moves", alerts) { on ->
                    if (on && Build.VERSION.SDK_INT >= 33 && !BackgroundInvestigator.canNotify(context)) {
                        permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        enableAlerts(on)
                    }
                }
                if (alerts) {
                    Text("Alert when a move is ${String.format(Locale.US, "%.1f", sensitivity)}× a normal day")
                    Slider(
                        value = sensitivity,
                        onValueChange = { sensitivity = (Math.round(it * 2) / 2f) },
                        onValueChangeFinished = { settings.alertSensitivity = sensitivity.toDouble() },
                        valueRange = 1.5f..4f,
                        steps = 4,
                    )
                }
            }

            Section(
                null,
                "Use built-in sample prices and headlines instead of live market data. Live data comes from Yahoo Finance and Google News and may be delayed.",
            ) {
                ToggleRow("Demo data", model.demoMode) { model.useDemoData(it) }
            }

            Section(null, null) {
                TextButton(onClick = { confirmReset = true }) {
                    Text("Delete all holdings", color = Palette.down)
                }
            }

            Section("About", null) {
                Text(
                    "Stocks Detective doesn't just tell you a stock is down 3%. It explains what happened, which news caused it, whether the move is justified, and what to watch next.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text("Not investment advice.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Delete all holdings?") },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    HoldingsEditor.deleteAll(deps.holdings, model)
                }) { Text("Delete all", color = Palette.down) }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Section(header: String?, footer: String?, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        header?.let {
            Text(it.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp))
        }
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(12.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
        }
        footer?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp))
        }
    }
}

@Composable
private fun Status(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, secondary: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(20.dp), tint = if (secondary) MaterialTheme.colorScheme.onSurfaceVariant else Palette.accent)
        Spacer(Modifier.width(10.dp))
        Text(text, color = if (secondary) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontWeight = FontWeight.Normal)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
