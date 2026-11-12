package com.stocksdetective.app.ui

import com.stocksdetective.app.Deps
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stocksdetective.app.data.HoldingsEditor
import com.stocksdetective.kit.PortfolioImporter
import com.stocksdetective.kit.Sector
import com.stocksdetective.kit.SymbolMatch
import kotlinx.coroutines.delay
import java.util.Locale

/** A full-screen form with Cancel / confirm, like the iOS sheets. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FormScaffold(title: String, confirm: String, confirmEnabled: Boolean, onCancel: () -> Unit, onConfirm: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { IconButton(onClick = onCancel) { Icon(Icons.Filled.Close, "Cancel") } },
                actions = { TextButton(onClick = onConfirm, enabled = confirmEnabled) { Text(confirm, fontWeight = FontWeight.SemiBold) } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
    }
}

@Composable
private fun Group(header: String? = null, footer: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        header?.let { Text(it.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp)) }
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(12.dp)) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
        }
        footer?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddHoldingScreen(deps: Deps, onDone: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SymbolMatch>>(emptyList()) }
    var selected by remember { mutableStateOf<SymbolMatch?>(null) }
    var shares by rememberSaveable { mutableStateOf("") }
    var cost by rememberSaveable { mutableStateOf("") }
    var sector by remember { mutableStateOf(Sector.BROAD_MARKET) }
    var sectorMenu by remember { mutableStateOf(false) }

    LaunchedEffect(query) {
        if (query.isEmpty()) { results = emptyList(); return@LaunchedEffect }
        delay(300) // debounce typing
        results = runCatching { deps.model.provider.search(query) }.getOrDefault(emptyList())
    }

    fun select(m: SymbolMatch) { selected = m; sector = Sector.guess(m.symbol) }

    val sharesValue = HoldingsEditor.parseAmount(shares)
    FormScaffold("Add holding", "Add", selected != null && sharesValue != null, onDone, onConfirm = {
        val s = selected ?: return@FormScaffold
        val n = sharesValue ?: return@FormScaffold
        HoldingsEditor.upsert(deps.holdings, s.symbol, s.name, n, HoldingsEditor.parseAmount(cost), sector)
        onDone()
    }) {
        val sel = selected
        if (sel != null) {
            Group("Stock") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(sel.symbol, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(10.dp))
                    Text(sel.name, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TextButton(onClick = { selected = null }) { Text("Change") }
                }
            }
            Group("Position") {
                OutlinedTextField(shares, { shares = it }, Modifier.fillMaxWidth(), label = { Text("Shares") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(cost, { cost = it }, Modifier.fillMaxWidth(), label = { Text("Average cost per share (optional)") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            }
            Group(footer = "The detective compares each move with this sector ETF to tell market moves apart from company news.") {
                ExposedDropdownMenuBox(sectorMenu, { sectorMenu = it }) {
                    OutlinedTextField(
                        "${sector.displayName} (${sector.etf})", {}, readOnly = true, label = { Text("Compare against") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(sectorMenu) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                    )
                    ExposedDropdownMenu(sectorMenu, { sectorMenu = false }) {
                        for (s in Sector.entries) {
                            DropdownMenuItem(text = { Text("${s.displayName} (${s.etf})") }, onClick = { sector = s; sectorMenu = false })
                        }
                    }
                }
            }
        } else {
            OutlinedTextField(
                query, { query = it }, Modifier.fillMaxWidth(), label = { Text("Search ticker or company") }, singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
            )
            if (results.isNotEmpty() || query.isNotEmpty()) {
                Group {
                    results.forEachIndexed { i, r ->
                        Row(Modifier.fillMaxWidth().clickable { select(r) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(r.symbol, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.width(8.dp))
                            Text(r.name, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(r.exchange, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                        }
                        if (i != results.lastIndex) HorizontalDivider()
                    }
                    if (query.isNotEmpty()) {
                        val q = query.trim().uppercase()
                        TextButton(onClick = { select(SymbolMatch(q, q)) }) { Text("Use “$q”") }
                    }
                }
            }
        }
    }
}

/** Paste a holdings table from a broker app, spreadsheet or notes and import it in one go. */
@Composable
fun ImportScreen(deps: Deps, onDone: () -> Unit) {
    val context = LocalContext.current
    val existing by deps.holdings.holdings.collectAsStateWithLifecycle()
    var text by rememberSaveable { mutableStateOf("") }
    // Swap out the sample stocks by default.
    var replace by rememberSaveable { mutableStateOf(deps.model.demoMode) }
    val result = remember(text) { PortfolioImporter.parse(text) }

    FormScaffold("Import portfolio", "Import", result.rows.isNotEmpty(), onDone, onConfirm = {
        HoldingsEditor.importRows(result.rows, replace, deps.holdings, deps.model)
        onDone()
    }) {
        Group(
            "Paste your holdings",
            "Copy the positions table from your broker, a spreadsheet or notes. Each line needs a ticker followed by the number of shares; other columns are ignored.",
        ) {
            OutlinedButton(onClick = {
                val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clip.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.let { text = it.toString() }
            }, shape = CircleShape) {
                Icon(Icons.Filled.ContentPaste, null)
                Spacer(Modifier.width(6.dp))
                Text("Paste")
            }
            val mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = MaterialTheme.typography.bodySmall.fontSize)
            OutlinedTextField(
                text, { text = it }, Modifier.fillMaxWidth().heightIn(min = 140.dp), textStyle = mono,
                placeholder = { Text("SYM   QTY   PRICE\nVOO   238   675.99\nNVDA  210   186.64", style = mono) },
            )
        }

        if (result.rows.isNotEmpty()) {
            Group("${result.rows.size} holdings found") {
                result.rows.forEach { row ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(row.symbol, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        if (existing.any { it.symbol == row.symbol }) {
                            Spacer(Modifier.width(6.dp))
                            Box(Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape).padding(horizontal = 5.dp)) {
                                Text("update", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        Text("${formatShares(row.shares)} shares")
                        row.price?.let {
                            Text(" @ ${String.format(Locale.US, "%.2f", it)}", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            Group(footer = if (replace) "Holdings not in the pasted list will be removed." else "Pasted holdings are added; tickers you already have get the new share count.") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Replace my current portfolio", Modifier.weight(1f))
                    Switch(replace, { replace = it })
                }
            }
        }

        if (result.skipped.isNotEmpty()) {
            Group("Couldn't read these lines") {
                result.skipped.forEach {
                    Text(it, style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

internal fun formatShares(v: Double): String =
    if (v == Math.floor(v) && v < 1e12) String.format(Locale.US, "%,d", v.toLong()) else String.format(Locale.US, "%,.4f", v).trimEnd('0').trimEnd('.')
