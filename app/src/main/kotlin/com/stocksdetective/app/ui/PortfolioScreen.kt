package com.stocksdetective.app.ui

import com.stocksdetective.app.Deps
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ManageSearch
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stocksdetective.app.data.Holding
import com.stocksdetective.app.data.HoldingsEditor
import com.stocksdetective.kit.CaseFile
import com.stocksdetective.kit.InvestigationReport
import com.stocksdetective.kit.PortfolioSnapshot
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PortfolioScreen(deps: Deps, onOpenCase: (String) -> Unit, onAdd: () -> Unit, onImport: () -> Unit) {
    val model = deps.model
    val holdings by deps.holdings.holdings.collectAsStateWithLifecycle()
    val inputs = holdings.map { it.input }
    val scope = rememberCoroutineScope()
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var menu by remember { mutableStateOf(false) }

    // Like the iOS `.task(id:)`: refetch whenever the source or the set of holdings changes.
    LaunchedEffect(model.generation, holdings.joinToString(",") { it.symbol }) {
        model.refresh(inputs)
        HoldingsEditor.fillInNames(deps.holdings, model.cases)
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("Stocks Detective") },
                scrollBehavior = scroll,
                actions = {
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.Add, "Add") }
                        DropdownMenu(menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Add a holding") }, leadingIcon = { Icon(Icons.Filled.Add, null) },
                                onClick = { menu = false; onAdd() })
                            DropdownMenuItem(text = { Text("Import from paste") }, leadingIcon = { Icon(Icons.Filled.ContentPaste, null) },
                                onClick = { menu = false; onImport() })
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (holdings.isEmpty()) {
            EmptyState(Modifier.padding(padding), onAdd, onImport) { HoldingsEditor.seedDemo(deps.holdings, model) }
        } else {
            PullToRefreshBox(
                isRefreshing = model.isRefreshing,
                onRefresh = {
                    scope.launch {
                        model.refresh(inputs)
                        HoldingsEditor.fillInNames(deps.holdings, model.cases)
                    }
                },
                modifier = Modifier.padding(padding),
            ) {
                val sorted = HoldingsEditor.sortedBySurprise(holdings, model.cases)
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 16.dp)) {
                    item {
                        PortfolioHeader(model.snapshot(inputs), model.isRefreshing, model.lastRefresh)
                        Spacer(Modifier.size(20.dp))
                        Text("Open cases", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
                    }
                    items(sorted, key = { it.symbol }) { h ->
                        SwipeToDelete(onDelete = { HoldingsEditor.delete(listOf(h.symbol), deps.holdings, model) }) {
                            HoldingRow(
                                h, model.cases[h.symbol], model.reports[h.symbol], model.isInvestigating(h.symbol), model.failures[h.symbol],
                                Modifier.clickable { onOpenCase(h.symbol) },
                            )
                        }
                        if (h != sorted.last()) HorizontalDivider(Modifier.padding(start = 16.dp))
                    }
                    item { Disclaimer() }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToDelete(onDelete: () -> Unit, content: @Composable () -> Unit) {
    val state = rememberSwipeToDismissBoxState()
    LaunchedEffect(state.currentValue) {
        if (state.currentValue == SwipeToDismissBoxValue.EndToStart) onDelete()
    }
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(Modifier.fillMaxSize().background(Palette.down).padding(end = 20.dp), contentAlignment = Alignment.CenterEnd) {
                Icon(Icons.Filled.Delete, "Delete", tint = Color.White)
            }
        },
    ) {
        Box(Modifier.background(MaterialTheme.colorScheme.background)) { content() }
    }
}

@Composable
private fun EmptyState(modifier: Modifier, onAdd: () -> Unit, onImport: () -> Unit, onDemo: () -> Unit) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Filled.Search, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.size(16.dp))
        Text("No cases yet", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.size(8.dp))
        Text(
            "Add your holdings. The detective will explain every move: what happened, which news caused it, whether it's justified, and what to watch next.",
            textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(24.dp))
        Button(onClick = onAdd) { Text("Add a holding") }
        OutlinedButton(onClick = onImport) { Text("Import from paste") }
        TextButton(onClick = onDemo) { Text("Try the demo portfolio") }
    }
}

private val timeFormat = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault())

@Composable
fun PortfolioHeader(snapshot: PortfolioSnapshot, isRefreshing: Boolean, lastRefresh: Instant?) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Portfolio value", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(snapshot.totalValue.currency, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${snapshot.dayPnL.signedCurrency} (${snapshot.dayChangePercent.signedPercent}) today",
                color = Palette.change(snapshot.dayPnL), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (isRefreshing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            else if (lastRefresh != null) Text(timeFormat.format(lastRefresh), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val n = snapshot.suspects.size
        if (n > 0) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 4.dp)) {
                Icon(Icons.AutoMirrored.Filled.ManageSearch, null, Modifier.size(16.dp), tint = Palette.accent)
                Text("$n unusual move${if (n == 1) "" else "s"} under investigation", style = MaterialTheme.typography.labelLarge, color = Palette.accent)
            }
        }
    }
}

@Composable
fun HoldingRow(holding: Holding, file: CaseFile?, report: InvestigationReport?, isInvestigating: Boolean, failure: String?, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(holding.symbol, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(holding.name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (file != null) {
                DaySparkline(file, Modifier.size(60.dp, 28.dp))
                Spacer(Modifier.width(10.dp))
                ChangePill(file.facts.dayChange)
            } else if (failure == null) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        }
        // The point of the app: the "why", right in the list.
        if (failure != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Warning, null, Modifier.size(14.dp), tint = Palette.down)
                Text(failure, style = MaterialTheme.typography.labelMedium, color = Palette.down)
            }
        } else if (report != null) {
            Text(report.headline, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                VerdictBadge(report.verdict, compact = true)
                if (isInvestigating) {
                    PulsingAI()
                    Text("Investigating…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else if (report.author.isAI) {
                    Icon(Icons.Filled.AutoAwesome, "Written by on-device AI", Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
fun PulsingAI() {
    val transition = rememberInfiniteTransition(label = "pulse")
    val alpha by transition.animateFloat(0.3f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "alpha")
    Icon(Icons.Filled.AutoAwesome, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha))
}
