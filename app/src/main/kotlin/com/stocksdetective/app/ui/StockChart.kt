package com.stocksdetective.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stocksdetective.app.AppModel
import com.stocksdetective.kit.ChartData
import com.stocksdetective.kit.ChartRange
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Yahoo Finance–style price chart: range picker, period change, previous-close line,
 * volume bars and press-and-drag inspection of any bar.
 */
@Composable
fun StockChart(symbol: String, model: AppModel) {
    var range by rememberSaveable(symbol) { mutableStateOf(ChartRange.DAY) }
    val charts = remember(symbol, model.generation) { mutableStateMapOf<ChartRange, ChartData>() }
    val failed = remember(symbol, model.generation) { mutableStateMapOf<ChartRange, Boolean>() }
    var selected by remember { mutableStateOf<Int?>(null) }
    val data = charts[range]

    LaunchedEffect(symbol, model.generation, range) {
        if (charts[range] != null) return@LaunchedEffect
        runCatching { model.provider.chart(symbol, range) }
            .onSuccess { charts[range] = it; failed.remove(range) }
            .onFailure { if (it !is kotlinx.coroutines.CancellationException) failed[range] = true }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ChartSummary(data, selected, range)
        Box(Modifier.fillMaxWidth().height(258.dp), contentAlignment = Alignment.Center) {
            when {
                data != null && data.bars.size > 1 -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    PriceCanvas(data, range, selected) { selected = it }
                    VolumeCanvas(data, selected)
                }
                failed[range] == true -> Text(
                    "Chart unavailable\nCouldn't load ${range.label} prices.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
                else -> CircularProgressIndicator()
            }
        }
        RangePicker(range) { selected = null; range = it }
    }
}

private fun fmt2(v: Double) = String.format(Locale.US, "%.2f", v)

private fun compact(v: Double): String = when {
    v >= 1e9 -> String.format(Locale.US, "%.1fB", v / 1e9)
    v >= 1e6 -> String.format(Locale.US, "%.1fM", v / 1e6)
    v >= 1e3 -> String.format(Locale.US, "%.1fK", v / 1e3)
    else -> v.roundToInt().toString()
}

/** Period change, or the inspected bar's details while dragging. */
@Composable
private fun ChartSummary(data: ChartData?, selected: Int?, range: ChartRange) {
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    Box(Modifier.fillMaxWidth().height(48.dp)) {
        val bar = selected?.let { data?.bars?.getOrNull(it) }
        if (data != null && bar != null) {
            val base = data.baseline ?: bar.close
            val style = MaterialTheme.typography.labelMedium
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Column {
                    Text(tooltipDate(bar.date, range), style = style, fontWeight = FontWeight.SemiBold)
                    if (bar.volume > 0) Text("Vol ${compact(bar.volume)}", style = style, color = secondary)
                }
                Column {
                    Text("Close", style = style, color = secondary)
                    Text("Open", style = style, color = secondary)
                    Text("High / Low", style = style, color = secondary)
                }
                Column {
                    Row {
                        Text(fmt2(bar.close), style = style)
                        Text(if (bar.close >= base) " ▲" else " ▼", style = style, color = Palette.change(bar.close - base))
                    }
                    Text(bar.open?.let(::fmt2) ?: "–", style = style)
                    Text("${bar.high?.let(::fmt2) ?: "–"} / ${bar.low?.let(::fmt2) ?: "–"}", style = style)
                }
            }
        } else {
            val pct = data?.changePercent
            val change = data?.change
            if (pct != null && change != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "${if (change >= 0) "+" else ""}${fmt2(change)} (${pct.signedPercent})",
                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = Palette.change(pct),
                    )
                    Text(range.periodLabel, style = MaterialTheme.typography.titleSmall, color = secondary)
                }
            }
        }
    }
}

private const val Y_LABEL_WIDTH_DP = 44

@Composable
private fun PriceCanvas(data: ChartData, range: ChartRange, selected: Int?, onSelect: (Int?) -> Unit) {
    val up = (data.changePercent ?: 0.0) >= 0
    val color = if (up) Palette.up else Palette.down
    val domain = data.priceDomain
    val grid = MaterialTheme.colorScheme.onSurfaceVariant
    val primary = MaterialTheme.colorScheme.onSurface
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 10.sp, color = grid)
    val haptics = LocalHapticFeedback.current
    val slots = maxOf(data.xSlots - 1, 1)
    val ticks = remember(domain) { niceTicks(domain.start, domain.endInclusive, 5) }
    val labels = remember(data) { labelIndices(data.bars.size) }

    /** Bar under a touch, given the plot's width in pixels (the canvas minus the y labels). */
    fun indexAt(x: Float, plotWidth: Float): Int =
        (x / plotWidth * slots).roundToInt().coerceIn(0, data.bars.size - 1)

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(210.dp)
            .pointerInput(data) {
                val plotW = size.width - Y_LABEL_WIDTH_DP.dp.toPx()
                var last: Int? = null
                fun select(x: Float) {
                    val i = indexAt(x, plotW)
                    if (i != last) { last = i; onSelect(i); haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                }
                detectTapGestures(onPress = { pos ->
                    select(pos.x)
                    tryAwaitRelease()
                    last = null
                    onSelect(null)
                })
            }
            .pointerInput(data) {
                val plotW = size.width - Y_LABEL_WIDTH_DP.dp.toPx()
                var last: Int? = null
                detectHorizontalDragGestures(
                    onDragStart = { pos -> last = indexAt(pos.x, plotW).also(onSelect) },
                    onDragEnd = { last = null; onSelect(null) },
                    onDragCancel = { last = null; onSelect(null) },
                ) { change, _ ->
                    change.consume()
                    val i = indexAt(change.position.x, plotW)
                    if (i != last) { last = i; onSelect(i); haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                }
            }
    ) {
        val plotW = size.width - Y_LABEL_WIDTH_DP.dp.toPx()
        val plotH = size.height - 16.dp.toPx() // room for x labels
        fun x(i: Int) = plotW * i / slots
        fun y(v: Double) = (plotH * (1 - (v - domain.start) / (domain.endInclusive - domain.start))).toFloat()

        // Grid + y labels on the trailing edge.
        for (t in ticks) {
            val ty = y(t)
            if (ty < 0 || ty > plotH) continue
            drawLine(grid.copy(alpha = 0.25f), Offset(0f, ty), Offset(plotW, ty), 0.5.dp.toPx())
            drawLabel(measurer, axisNumber(t, ticks), Offset(plotW + 4.dp.toPx(), ty), labelStyle, centerY = true)
        }
        // X labels.
        for (i in labels) {
            val bar = data.bars.getOrNull(i) ?: continue
            drawLine(grid.copy(alpha = 0.15f), Offset(x(i), 0f), Offset(x(i), plotH), 0.5.dp.toPx())
            drawLabel(measurer, axisDate(bar.date, range), Offset(x(i), plotH + 2.dp.toPx()), labelStyle, centerX = true)
        }

        val line = Path()
        val area = Path()
        data.bars.forEachIndexed { i, b ->
            if (i == 0) { line.moveTo(x(i), y(b.close)); area.moveTo(x(i), plotH); area.lineTo(x(i), y(b.close)) }
            else { line.lineTo(x(i), y(b.close)); area.lineTo(x(i), y(b.close)) }
        }
        area.lineTo(x(data.bars.size - 1), plotH)
        area.close()
        drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = 0.35f), color.copy(alpha = 0.02f)), 0f, plotH))
        drawPath(line, color, style = Stroke(1.6.dp.toPx(), join = StrokeJoin.Round))

        data.baseline?.let { base ->
            val by = y(base)
            if (by in 0f..plotH) {
                drawLine(grid.copy(alpha = 0.7f), Offset(0f, by), Offset(plotW, by), 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())))
            }
        }

        selected?.let { i ->
            val b = data.bars.getOrNull(i) ?: return@let
            drawLine(primary.copy(alpha = 0.35f), Offset(x(i), 0f), Offset(x(i), plotH), 1.dp.toPx())
            drawCircle(primary, 3.5.dp.toPx(), Offset(x(i), y(b.close)))
        }
    }
}

@Composable
private fun VolumeCanvas(data: ChartData, selected: Int?) {
    val gray = Palette.gray.copy(alpha = 0.55f)
    val highlight = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
    val slots = maxOf(data.xSlots - 1, 1)
    val maxVol = data.bars.maxOf { it.volume }.takeIf { it > 0 } ?: return Spacer(Modifier.height(44.dp))
    Canvas(Modifier.fillMaxWidth().height(44.dp)) {
        val plotW = size.width - Y_LABEL_WIDTH_DP.dp.toPx()
        val barW = maxOf(plotW / (slots + 1) * 0.7f, 1f)
        data.bars.forEachIndexed { i, b ->
            val h = (b.volume / maxVol * size.height).toFloat()
            val cx = plotW * i / slots
            drawRect(if (i == selected) highlight else gray, Offset(cx - barW / 2, size.height - h), Size(barW, h))
        }
    }
}

@Composable
private fun RangePicker(range: ChartRange, onPick: (ChartRange) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(10.dp))
            .padding(3.dp)
    ) {
        for (r in ChartRange.entries) {
            val isOn = r == range
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 32.dp)
                    .background(if (isOn) Palette.accent.copy(alpha = 0.9f) else Color.Transparent, RoundedCornerShape(8.dp))
                    .clickable(role = Role.Tab) { onPick(r) }
                    .semantics { this.selected = isOn },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    r.label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (isOn) FontWeight.Bold else FontWeight.Medium,
                    color = if (isOn) Color.White else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawLabel(
    measurer: TextMeasurer, text: String, at: Offset, style: TextStyle, centerX: Boolean = false, centerY: Boolean = false,
) {
    val layout = measurer.measure(text, style)
    var x = at.x - if (centerX) layout.size.width / 2f else 0f
    x = x.coerceIn(0f, size.width - layout.size.width)
    val y = at.y - if (centerY) layout.size.height / 2f else 0f
    drawText(layout, topLeft = Offset(x, y))
}

/** Four evenly spaced x-axis label positions (same as the iOS chart). */
internal fun labelIndices(count: Int): List<Int> {
    if (count <= 4) return (0 until count).toList()
    val step = (count - 1) / 4.0
    return (0 until 4).map { (it * step + step / 2).roundToInt() }
}

/** "Nice" round tick values covering lo..hi. */
internal fun niceTicks(lo: Double, hi: Double, desired: Int): List<Double> {
    val span = hi - lo
    if (span <= 0) return listOf(lo)
    val raw = span / desired
    val mag = 10.0.pow(floor(log10(raw)))
    val step = listOf(1.0, 2.0, 2.5, 5.0, 10.0).map { it * mag }.first { it >= raw }
    val start = ceil(lo / step) * step
    return generateSequence(start) { it + step }.takeWhile { it <= hi + step * 1e-9 }.toList()
}

private fun axisNumber(v: Double, ticks: List<Double>): String {
    val step = if (ticks.size > 1) abs(ticks[1] - ticks[0]) else 1.0
    return when {
        step >= 1 -> String.format(Locale.US, "%,.0f", v)
        step >= 0.1 -> String.format(Locale.US, "%.1f", v)
        else -> String.format(Locale.US, "%.2f", v)
    }
}

private fun pattern(p: String) = DateTimeFormatter.ofPattern(p, Locale.getDefault()).withZone(ZoneId.systemDefault())
private val hourMinute = DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT).withZone(ZoneId.systemDefault())
private val weekday = pattern("EEE")
private val monthDay = pattern("MMM d")
private val month = pattern("MMM")
private val year = pattern("yyyy")
private val monthDayTime = pattern("MMM d, HH:mm")
private val monthDayYear = pattern("MMM d, yyyy")

internal fun axisDate(d: Instant, range: ChartRange): String = when (range) {
    ChartRange.DAY -> hourMinute.format(d)
    ChartRange.FIVE_DAYS -> weekday.format(d)
    ChartRange.MONTH, ChartRange.SIX_MONTHS, ChartRange.YTD -> monthDay.format(d)
    ChartRange.YEAR -> month.format(d)
    ChartRange.FIVE_YEARS, ChartRange.ALL -> year.format(d)
}

internal fun tooltipDate(d: Instant, range: ChartRange): String =
    if (range.isIntraday) monthDayTime.format(d) else monthDayYear.format(d)
