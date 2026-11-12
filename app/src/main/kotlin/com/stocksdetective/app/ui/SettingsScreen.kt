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
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stocksdetective.app.ai.AIEngine
import com.stocksdetective.app.ai.AiStatus
import com.stocksdetective.app.data.Settings
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
                "AI model",
                if (model.ai.engine == AIEngine.ON_DEVICE) {
                    "On-device: Gemini Nano runs entirely on this phone through Android AICore. Private, offline and fast; reads the top 5 headlines per stock. Phones without it get the built-in rule-based analyst."
                } else {
                    "Gemini cloud: Google's larger models through the Gemini API, billed to your own API key (it has a free tier). Reads up to 10 headlines per stock and your whole portfolio for the briefing. Your holdings and headlines are sent to Google. Google doesn't let apps use a Gemini app subscription, so a key is needed. Falls back to on-device automatically."
                },
            ) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    AIEngine.entries.forEachIndexed { i, e ->
                        SegmentedButton(
                            selected = model.ai.engine == e,
                            onClick = {
                                if (model.ai.engine != e) {
                                    model.ai.engine = e
                                    settings.aiEngine = e
                                    model.clearReports() // re-investigate with the new model
                                }
                            },
                            shape = SegmentedButtonDefaults.itemShape(i, AIEngine.entries.size),
                        ) { Text(e.label) }
                    }
                }
                when (model.ai.engine) {
                    AIEngine.ON_DEVICE -> OnDeviceStatus(model.ai.status) { scope.launch { model.ai.download(); model.clearReports() } }
                    AIEngine.CLOUD -> CloudSettings(deps)
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

@Composable
private fun OnDeviceStatus(status: AiStatus, onDownload: () -> Unit) {
    when (status) {
        AiStatus.Available -> Status(Icons.Filled.PhoneAndroid, "Gemini Nano is ready. Runs entirely on this phone. Works offline.")
        AiStatus.Checking -> Status(Icons.Filled.PhoneAndroid, "Checking for on-device AI…")
        AiStatus.Downloadable -> {
            Status(Icons.Filled.Download, "This phone supports Gemini Nano. Download the model (about 1 GB, over Wi-Fi) to get AI investigations.")
            FilledTonalButton(onClick = onDownload) { Text("Download Gemini Nano") }
        }
        is AiStatus.Downloading -> Status(
            Icons.Filled.Download,
            "Downloading Gemini Nano… ${if (status.bytes > 0) "${status.bytes / 1_000_000} MB" else ""}. Using the rule-based analyst for now.",
        )
        is AiStatus.Unavailable -> Status(Icons.Filled.Warning, status.reason, secondary = true)
    }
}

/** API key and model for Gemini in the cloud, with a connection test. */
@Composable
private fun CloudSettings(deps: Deps) {
    val ai = deps.model.ai
    val settings = deps.settings
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    var key by rememberSaveable { mutableStateOf(settings.geminiApiKey) }
    var modelName by rememberSaveable { mutableStateOf(settings.geminiModel) }
    var showKey by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }

    fun save() {
        val changed = key.trim() != settings.geminiApiKey || modelName.trim() != settings.geminiModel
        settings.geminiApiKey = key
        settings.geminiModel = modelName
        ai.cloudConfig = settings.cloudConfig
        if (changed) { testResult = null; deps.model.clearReports() }
    }

    OutlinedTextField(
        key, { key = it }, Modifier.fillMaxWidth(), label = { Text("Gemini API key") }, singleLine = true,
        visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { save() }),
        trailingIcon = {
            IconButton(onClick = { showKey = !showKey }) {
                Icon(if (showKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, if (showKey) "Hide key" else "Show key")
            }
        },
    )
    OutlinedTextField(
        modelName, { modelName = it }, Modifier.fillMaxWidth(), label = { Text("Model") }, singleLine = true,
        supportingText = { Text("Default ${Settings.DEFAULT_GEMINI_MODEL}. Any Gemini API model name works.") },
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { save() }),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        FilledTonalButton(enabled = key.isNotBlank() && !testing, onClick = {
            save()
            val config = settings.cloudConfig ?: return@FilledTonalButton
            testing = true
            scope.launch {
                testResult = ai.testCloud(config)?.let { "✗ $it" } ?: "✓ Connected to ${config.model}."
                testing = false
            }
        }) { Text(if (testing) "Testing…" else "Save & test") }
        TextButton(onClick = { uri.openUri("https://aistudio.google.com/apikey") }) { Text("Get a free key") }
    }
    when {
        testResult != null -> Text(testResult!!, color = if (testResult!!.startsWith("✓")) Palette.up else Palette.down,
            style = MaterialTheme.typography.bodyMedium)
        key.isBlank() -> Status(Icons.Filled.Key, "Add your Gemini API key to use the cloud model. Until then the app uses on-device AI.", secondary = true)
        ai.lastCloudError != null -> Status(Icons.Filled.CloudOff, "Last request failed: ${ai.lastCloudError} Using on-device AI meanwhile.", secondary = true)
        else -> Status(Icons.Filled.Cloud, "Gemini cloud is set up with ${settings.geminiModel}.")
    }
}
