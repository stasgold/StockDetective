package com.stocksdetective.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stocksdetective.app.AppModel
import com.stocksdetective.kit.CaseFile
import com.stocksdetective.kit.InvestigationReport
import com.stocksdetective.kit.MovementFacts
import com.stocksdetective.kit.ScoredArticle
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.util.Locale
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaseDetailScreen(symbol: String, model: AppModel, onBack: () -> Unit, onInterrogate: () -> Unit) {
    val scope = rememberCoroutineScope()
    val file = model.cases[symbol]
    val report = model.reports[symbol]
    val investigating = model.isInvestigating(symbol)

    // Opening a case always gets the full AI investigation.
    LaunchedEffect(symbol, file != null, model.ai.isAvailable) {
        if (file != null && model.reports[symbol]?.author?.isAI != true && model.ai.isAvailable) model.investigate(symbol)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(symbol) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    if (model.ai.isAvailable) {
                        IconButton(onClick = { scope.launch { model.investigate(symbol) } }, enabled = !investigating) {
                            Icon(Icons.Filled.Refresh, "Re-investigate")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                file != null -> Column(
                    Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Header(file, model)
                    if (report != null) {
                        VerdictCard(report, investigating)
                        Card("What happened", Icons.Filled.Search) { Text(report.whatHappened) }
                        MoveBreakdownCard(file.facts)
                        CausesCard(report)
                        WatchCard(report)
                    }
                    EvidenceCard(file)
                    Button(onClick = onInterrogate, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                        Icon(Icons.Filled.Forum, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Interrogate the detective")
                    }
                    Disclaimer()
                }
                model.failures[symbol] != null -> Column(
                    Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(Icons.Filled.Warning, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Case went cold", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(model.failures[symbol] ?: "", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text("Gathering evidence…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun Header(file: CaseFile, model: AppModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(file.companyName ?: file.symbol, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(file.series.lastPrice.currency, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
            ChangePill(file.facts.dayChange)
        }
        StockChart(file.symbol, model)
        Row {
            Stat("5 days", file.facts.weekChange, Modifier.weight(1f))
            Stat("1 month", file.facts.monthChange, Modifier.weight(1f))
            Stat("vs high", file.facts.distanceFrom52wHigh, Modifier.weight(1f))
        }
    }
}

@Composable
private fun Stat(label: String, v: Double, modifier: Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(v.signedPercent, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = Palette.change(v))
    }
}

@Composable
private fun VerdictCard(r: InvestigationReport, investigating: Boolean) {
    Card("The verdict", Icons.Filled.Gavel, accentColor = Palette.verdict(r.verdict)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            VerdictBadge(r.verdict)
            Spacer(Modifier.weight(1f))
            ConfidenceGauge(r.confidence)
        }
        Text(r.headline, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(r.verdictReasoning)
        Row(verticalAlignment = Alignment.CenterVertically) {
            AuthorBadge(r.author)
            Spacer(Modifier.weight(1f))
            if (investigating) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(6.dp))
                Text("Gemini Nano is investigating…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun CausesCard(r: InvestigationReport) {
    Card("Suspects", Icons.Filled.Groups) {
        r.causes.forEachIndexed { i, c ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NumberBadge(i + 1)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(c.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(c.detail, style = MaterialTheme.typography.bodyMedium)
                    c.evidence?.let {
                        Text("“$it”", style = MaterialTheme.typography.labelMedium, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun WatchCard(r: InvestigationReport) {
    Card("What to watch next", Icons.Filled.Visibility) {
        for (item in r.watchNext) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Filled.Visibility, null, Modifier.size(16.dp).padding(top = 2.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(item, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun EvidenceCard(file: CaseFile) {
    Card("Evidence locker", Icons.Filled.Archive) {
        if (file.news.isEmpty()) {
            Text(
                "No recent headlines found. A big move with no news is itself a clue: it can mean flows, rumors, or news that hasn't hit the wires yet.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        file.news.forEachIndexed { i, n ->
            EvidenceRow(n)
            if (i != file.news.lastIndex) HorizontalDivider()
        }
    }
}

internal fun relativeTime(d: Instant, now: Instant = Instant.now()): String {
    val m = Duration.between(d, now).toMinutes()
    return when {
        m < 1 -> "just now"
        m < 60 -> "$m min ago"
        m < 48 * 60 -> "${m / 60} hr ago"
        else -> "${m / 1440} days ago"
    }
}

@Composable
private fun EvidenceRow(item: ScoredArticle) {
    val uri = LocalUriHandler.current
    val link = item.article.link
    val dot = when {
        item.sentiment > 0.15 -> Palette.up
        item.sentiment < -0.15 -> Palette.down
        else -> Palette.gray
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
            .then(if (link.isNotEmpty()) Modifier.clickable { runCatching { uri.openUri(link) } } else Modifier),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.padding(top = 6.dp).size(8.dp).background(dot, CircleShape))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(item.article.title, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                val meta = MaterialTheme.typography.labelSmall
                val secondary = MaterialTheme.colorScheme.onSurfaceVariant
                if (item.article.source.isNotEmpty()) Text(item.article.source, style = meta, color = secondary)
                item.article.published?.let { Text(relativeTime(it), style = meta, color = secondary) }
                for (t in item.catalysts.take(2)) {
                    Text(t, style = meta, color = secondary,
                        modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape).padding(horizontal = 5.dp))
                }
            }
        }
    }
}

/** Splits today's move into "the market did it" vs "the company did it". */
@Composable
private fun MoveBreakdownCard(facts: MovementFacts) {
    Card("Market or company?", Icons.Filled.BarChart) {
        Text(facts.character.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        val share = facts.explainedByMarket.toFloat()
        Row(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(6.dp)), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            if (share > 0) Box(Modifier.weight(share.coerceAtLeast(0.02f)).fillMaxSize().background(Palette.gray.copy(alpha = 0.6f)))
            if (share < 1) Box(Modifier.weight((1 - share).coerceAtLeast(0.02f)).fillMaxSize().background(Palette.accent))
        }
        Row {
            Legend("Sector/market ${(facts.explainedByMarket * 100).toInt()}%", Palette.gray)
            Spacer(Modifier.weight(1f))
            Legend("Company-specific ${((1 - facts.explainedByMarket) * 100).toInt()}%", Palette.accent)
        }
        MetricRow("${facts.benchmark} today" to facts.benchmarkDayChange.signedPercent, "S&P 500 today" to facts.marketDayChange.signedPercent)
        MetricRow("Beta to sector" to String.format(Locale.US, "%.2f", facts.beta), "Expected from sector" to facts.expectedMove.signedPercent)
        MetricRow(
            "Size vs normal day" to String.format(Locale.US, "%.1f×", abs(facts.zScore)),
            "Volume vs average" to String.format(Locale.US, "%.1f×", facts.volumeRatio),
        )
    }
}

@Composable
private fun Legend(text: String, color: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(Icons.Filled.Circle, null, Modifier.size(8.dp), tint = color)
        Text(text, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

@Composable
private fun MetricRow(a: Pair<String, String>, b: Pair<String, String>) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        for ((label, value) in listOf(a, b)) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
