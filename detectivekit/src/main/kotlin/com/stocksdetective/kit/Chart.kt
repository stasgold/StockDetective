package com.stocksdetective.kit

import java.time.Duration
import java.time.Instant
import kotlin.math.max
import kotlin.math.min

/** Time ranges offered on the price chart, matching Yahoo Finance. */
enum class ChartRange(val label: String, internal val yahooRange: String, internal val yahooInterval: String, val periodLabel: String) {
    DAY("1D", "1d", "5m", "today"),
    FIVE_DAYS("5D", "5d", "15m", "past 5 days"),
    MONTH("1M", "1mo", "1d", "past month"),
    SIX_MONTHS("6M", "6mo", "1d", "past 6 months"),
    YTD("YTD", "ytd", "1d", "year to date"),
    YEAR("1Y", "1y", "1d", "past year"),
    FIVE_YEARS("5Y", "5y", "1wk", "past 5 years"),
    ALL("All", "max", "1mo", "all time");

    /** Bars are minutes apart (show times, not just dates). */
    val isIntraday: Boolean get() = this == DAY || this == FIVE_DAYS
}

data class ChartBar(
    val date: Instant,
    val open: Double?,
    val high: Double?,
    val low: Double?,
    val close: Double,
    val volume: Double,
)

data class ChartData(
    val symbol: String,
    val range: ChartRange,
    val currency: String,
    val bars: List<ChartBar>,
    /** Previous session's close (drawn as a dashed line on 1D). */
    val previousClose: Double?,
) {
    /**
     * Price the period change is measured from: the close just before the period starts
     * (how Yahoo Finance computes it), or the first bar when that isn't known.
     */
    val baseline: Double?
        get() = previousClose?.takeIf { it > 0 } ?: bars.firstOrNull()?.close

    val change: Double?
        get() {
            val base = baseline ?: return null
            if (base <= 0) return null
            val last = bars.lastOrNull()?.close ?: return null
            return last - base
        }

    val changePercent: Double?
        get() {
            val base = baseline ?: return null
            if (base <= 0) return null
            return (change ?: return null) / base * 100
        }

    /**
     * X-axis length in bars. 1D reserves room for a full US session (6.5 h of 5-minute bars),
     * so a chart drawn mid-day fills from the left like Yahoo's.
     */
    val xSlots: Int
        get() {
            val first = bars.firstOrNull()?.date
            val last = bars.lastOrNull()?.date
            if (range == ChartRange.DAY && first != null && last != null &&
                Duration.between(first, last).seconds < 6.5 * 3600
            ) return max(bars.size, 79)
            return bars.size
        }

    val priceDomain: ClosedFloatingPointRange<Double>
        get() {
            var lo = bars.minOfOrNull { it.low ?: it.close } ?: 0.0
            var hi = bars.maxOfOrNull { it.high ?: it.close } ?: 1.0
            // Keep the dashed baseline on screen when it's near the prices (not for 5Y/All).
            baseline?.let { p ->
                if (p >= lo * 0.85 && p <= hi * 1.15) { lo = min(lo, p); hi = max(hi, p) }
            }
            if (hi <= lo) hi = lo + 1
            val pad = (hi - lo) * 0.06
            return (lo - pad)..(hi + pad)
        }
}
