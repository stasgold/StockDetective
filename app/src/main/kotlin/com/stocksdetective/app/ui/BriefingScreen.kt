package com.stocksdetective.app.ui

import com.stocksdetective.app.Deps
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Newspaper
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stocksdetective.app.AppModel
import com.stocksdetective.kit.HoldingInput
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.abs

/** The daily "morning meeting" with your analyst: one story for the whole portfolio. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BriefingScreen(deps: Deps, onOpenCase: (String) -> Unit) {
    val model = deps.model
    val holdings by deps.holdings.holdings.collectAsStateWithLifecycle()
    val inputs = holdings.map { it.input }
    val scope = rememberCoroutineScope()

    LaunchedEffect(model.generation, holdings.size) {
        if (model.briefing == null && holdings.isNotEmpty()) model.writeBriefing(inputs)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Daily Briefing") },
                actions = {
                    IconButton(onClick = { scope.launch { model.writeBriefing(inputs) } }, enabled = !model.isBriefing && holdings.isNotEmpty()) {
                        Icon(Icons.Filled.Refresh, "Rewrite")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (holdings.isEmpty()) {
                Column(Modifier.fillMaxWidth().padding(top = 80.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.Newspaper, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("No portfolio yet", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Add holdings on the Portfolio tab to get a daily briefing.", textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                BriefingCard(model)
                ContributorsCard(model, inputs, onOpenCase)
                Disclaimer()
            }
        }
    }
}

private val timeFormat = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault())

@Composable
private fun BriefingCard(model: AppModel) {
    Card("Today's story", Icons.AutoMirrored.Filled.MenuBook) {
        val b = model.briefing
        if (b == null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text("Your analyst is writing today's briefing…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Card
        }
        Text(b.headline, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(b.summary)
        if (b.keyFindings.isNotEmpty()) {
            HorizontalDivider()
            Text("Key findings", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            b.keyFindings.forEach { Bullet(Icons.Filled.Search, it) }
        }
        if (b.watchNext.isNotEmpty()) {
            HorizontalDivider()
            Text("Watch next", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            b.watchNext.forEach { Bullet(Icons.Filled.Visibility, it) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            AuthorBadge(b.author)
            Spacer(Modifier.weight(1f))
            Text(timeFormat.format(b.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Bullet(icon: ImageVector, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, null, Modifier.size(16.dp).padding(top = 2.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ContributorsCard(model: AppModel, inputs: List<HoldingInput>, onOpenCase: (String) -> Unit) {
    val snap = model.snapshot(inputs)
    val maxPnL = snap.lines.maxOfOrNull { abs(it.dayPnL) } ?: 1.0
    Card("Who moved your portfolio", Icons.Filled.BarChart) {
        for (line in snap.topContributors) {
            Column(
                Modifier.fillMaxWidth().clickable { onOpenCase(line.symbol) }.padding(vertical = 2.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(line.symbol, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(line.dayChange.signedPercent, style = MaterialTheme.typography.labelMedium, color = Palette.change(line.dayChange))
                    Spacer(Modifier.weight(1f))
                    Text(line.dayPnL.signedCurrency, style = MaterialTheme.typography.bodyMedium, color = Palette.change(line.dayPnL))
                }
                val fraction = (abs(line.dayPnL) / maxOf(maxPnL, 0.01)).toFloat().coerceIn(0.02f, 1f)
                Box(Modifier.fillMaxWidth(fraction).height(6.dp).background(Palette.change(line.dayPnL).copy(alpha = 0.7f), CircleShape))
                model.reports[line.symbol]?.let {
                    Text(it.headline, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

