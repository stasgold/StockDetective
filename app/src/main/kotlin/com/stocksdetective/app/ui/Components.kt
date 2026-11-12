package com.stocksdetective.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Functions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stocksdetective.kit.CaseFile
import com.stocksdetective.kit.ReportAuthor
import com.stocksdetective.kit.Verdict

@Composable
fun ChangePill(value: Double, modifier: Modifier = Modifier) {
    Text(
        value.signedPercent,
        modifier = modifier
            .background(Palette.change(value), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        color = Color.White,
        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
    )
}

@Composable
fun VerdictBadge(verdict: Verdict, compact: Boolean = false) {
    val color = Palette.verdict(verdict)
    Row(
        Modifier
            .background(color.copy(alpha = 0.15f), CircleShape)
            .padding(horizontal = if (compact) 6.dp else 10.dp, vertical = if (compact) 3.dp else 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(Palette.verdictIcon(verdict), null, tint = color, modifier = Modifier.size(if (compact) 12.dp else 16.dp))
        Text(
            verdict.label,
            color = color,
            style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
fun AuthorBadge(author: ReportAuthor) {
    val (icon, label) = when (author) {
        ReportAuthor.ON_DEVICE_AI -> Icons.Filled.AutoAwesome to "Gemini Nano · on-device"
        ReportAuthor.RULE_BASED -> Icons.Filled.Functions to "Quick read · rule-based"
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(icon, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A rounded section with an amber caption, like the iOS `Card`. */
@Composable
fun Card(title: String, icon: ImageVector, modifier: Modifier = Modifier, accentColor: Color? = null, content: @Composable ColumnScope.() -> Unit) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .drawBehind {
                if (accentColor != null) {
                    val inset = 12.dp.toPx()
                    drawRoundRect(accentColor, Offset(0f, inset), Size(4.dp.toPx(), size.height - 2 * inset), CornerRadius(2.dp.toPx()))
                }
            }
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(icon, null, Modifier.size(14.dp), tint = Palette.accent)
                Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Palette.accent)
            }
            content()
        }
    }
}

@Composable
fun Disclaimer() {
    Text(
        "Stocks Detective explains market moves for education. It is not investment advice. AI can make mistakes; check the evidence.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp, horizontal = 8.dp),
    )
}

@Composable
fun ConfidenceGauge(value: Int) {
    Box(
        Modifier
            .size(44.dp)
            .semantics { contentDescription = "Confidence $value percent" },
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            progress = { value / 100f },
            modifier = Modifier.size(44.dp),
            color = Palette.accent,
            strokeWidth = 4.dp,
            trackColor = Palette.accent.copy(alpha = 0.2f),
            strokeCap = StrokeCap.Round,
        )
        Text("$value", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
    }
}

/** Numbered circle used for the "Suspects" list. */
@Composable
fun NumberBadge(n: Int) {
    Box(
        Modifier
            .size(28.dp)
            .background(Palette.accent.copy(alpha = 0.2f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text("$n", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    }
}

/** Simple line through closes, scaled to fit. */
@Composable
fun Sparkline(closes: List<Double>, modifier: Modifier = Modifier, baseline: Double? = null, slots: Int = closes.size, color: Color? = null) {
    if (closes.size < 2) return
    val lineColor = color ?: Palette.change(closes.last() - closes.first())
    val baseColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    Canvas(modifier) {
        var lo = closes.min()
        var hi = closes.max()
        if (baseline != null) { lo = minOf(lo, baseline); hi = maxOf(hi, baseline) }
        if (hi <= lo) hi = lo + 1
        val pad = (hi - lo) * 0.06
        lo -= pad; hi += pad
        fun y(v: Double) = (size.height * (1 - (v - lo) / (hi - lo))).toFloat()
        val xStep = size.width / (maxOf(slots, closes.size) - 1).coerceAtLeast(1)
        if (baseline != null) {
            drawLine(baseColor, Offset(0f, y(baseline)), Offset(size.width, y(baseline)), 0.8.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 2.dp.toPx())))
        }
        val path = Path()
        closes.forEachIndexed { i, c -> if (i == 0) path.moveTo(0f, y(c)) else path.lineTo(i * xStep, y(c)) }
        drawPath(path, lineColor, style = Stroke(1.4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/**
 * Row sparkline for the last trading day: intraday prices, colored by the day's change,
 * with the previous close dashed (matches the % pill next to it).
 */
@Composable
fun DaySparkline(file: CaseFile, modifier: Modifier = Modifier) {
    val c = file.intraday
    val m = modifier.semantics { contentDescription = "Today's price, ${file.facts.dayChange.signedPercent}" }
    if (c != null && c.bars.size > 1) {
        Sparkline(c.bars.map { it.close }, m, baseline = c.previousClose, slots = c.xSlots, color = Palette.change(file.facts.dayChange))
    } else {
        // No intraday data: last two closes, so the slope still matches today's move.
        Sparkline(listOf(file.series.previousClose, file.series.lastPrice), m)
    }
}
