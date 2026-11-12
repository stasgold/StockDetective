package com.stocksdetective.kit

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Offline, deterministic data so the app (and previews/tests) work with no network.
 * Each symbol gets a scripted "story" so every verdict type shows up.
 */
class DemoMarketData(private val clock: () -> Instant = Instant::now) : MarketDataProvider {

    internal data class Story(
        val name: String,
        val todayMove: Double,
        val volatility: Double,
        val volumeSpike: Double,
        val headlines: List<String>,
    )

    /** Small deterministic PRNG so demo charts look the same every launch. */
    internal class SeededRandom(private var state: ULong) {
        fun next(): Double {
            state = state * 6364136223846793005uL + 1442695040888963407uL
            return (state shr 11).toDouble() / (1uL shl 53).toDouble()
        }

        fun normal(): Double {
            val u1 = max(next(), 1e-9)
            val u2 = next()
            return sqrt(-2 * ln(u1)) * cos(2 * PI * u2)
        }
    }

    companion object {
        internal val stories: Map<String, Story> = linkedMapOf(
            "AAPL" to Story("Apple Inc.", -3.4, 1.4, 2.6, listOf(
                "Apple shares fall after report says iPhone demand in China slows",
                "Analyst cuts Apple price target on weaker services growth outlook",
                "Apple to unveil new Apple Intelligence features next month")),
            "NVDA" to Story("NVIDIA Corporation", 12.6, 2.6, 1.1, listOf(
                "Nvidia jumps as hyperscalers raise AI capex guidance",
                "Nvidia supplier TSMC reports record quarterly revenue")),
            "MSFT" to Story("Microsoft Corporation", -1.4, 1.3, 1.0, listOf(
                "Microsoft expands Azure AI partnership with OpenAI")),
            "TSLA" to Story("Tesla, Inc.", -6.8, 2.4, 1.9, emptyList()),
            "JPM" to Story("JPMorgan Chase & Co.", 3.9, 1.2, 1.7, listOf(
                "JPMorgan beats earnings estimates as trading revenue surges",
                "Banks rally as Treasury yields climb")),
        )

        internal fun story(s: String): Story =
            stories[s.uppercase()] ?: Story(s.uppercase(), 0.4, 1.5, 1.0, emptyList())

        internal fun benchmarkToday(s: String): Double? = when (s) {
            "SPY" -> -0.6
            "XLK" -> -0.9
            "XLF" -> 0.9
            "XLY" -> -0.7
            else -> null
        }

        private fun seed(s: String): ULong = abs(stableHash(s)).toULong() or 1uL
    }

    override suspend fun priceSeries(symbol: String): Pair<PriceSeries, String?> {
        val s = symbol.uppercase()
        val story = story(s)
        val isBenchmark = benchmarkToday(s) != null
        val today = benchmarkToday(s) ?: story.todayMove
        val vol = if (isBenchmark) 0.9 else story.volatility
        // One shared market path for every symbol, plus per-symbol noise, so betas look real.
        val marketRng = SeededRandom(42uL)
        val marketPath = List(125) { marketRng.normal() }
        val rng = SeededRandom(seed(s))
        var price = 100.0 + abs(stableHash(s)) % 300
        val points = mutableListOf<PricePoint>()
        val now = clock()
        val start = now.minus(125, ChronoUnit.DAYS)
        for (i in 0 until 124) {
            // Blend a shared "market" path with idiosyncratic noise so beta is meaningful.
            val r = (if (isBenchmark) marketPath[i] else 0.75 * marketPath[i] + 0.65 * rng.normal()) * vol / 100
            price *= 1 + r + 0.0004
            points += PricePoint(start.plus(i.toLong(), ChronoUnit.DAYS), price, 1_000_000 * (0.8 + rng.next() * 0.4))
        }
        val prev = price
        val last = prev * (1 + today / 100)
        points += PricePoint(now, last, 1_000_000 * story.volumeSpike)
        return PriceSeries(s, points = points, lastPrice = last, previousClose = prev) to story.name
    }

    override suspend fun news(symbol: String): List<NewsArticle> {
        val now = clock()
        return story(symbol).headlines.mapIndexed { i, t ->
            NewsArticle(t, "", "Demo Wire", "", now.minusSeconds((i + 1) * 3L * 3600))
        }
    }

    override suspend fun marketNews(): List<NewsArticle> {
        val now = clock()
        return listOf(
            NewsArticle("Stocks slip as investors weigh hotter-than-expected inflation data", source = "Demo Wire", published = now.minusSeconds(7200)),
            NewsArticle("Tech sector leads declines; financials gain on higher yields", source = "Demo Wire", published = now.minusSeconds(10800)),
        )
    }

    override suspend fun search(query: String): List<SymbolMatch> {
        val q = query.uppercase()
        return stories.filter { (k, v) -> k.startsWith(q) || v.name.uppercase().contains(q) }
            .map { (k, v) -> SymbolMatch(k, v.name, "DEMO") }
            .sortedBy { it.symbol }
    }

    override suspend fun chart(symbol: String, range: ChartRange): ChartData {
        val daily = priceSeries(symbol).first
        val now = clock()
        val rng = SeededRandom(seed(symbol + range.label))

        // Random walk that ends exactly at `end`.
        fun walk(count: Int, end: Double, vol: Double, stepSeconds: Long, until: Instant): List<ChartBar> {
            val prices = mutableListOf(end)
            for (i in 1 until count) prices += prices.last() / (1 + rng.normal() * vol)
            prices.reverse()
            return prices.mapIndexed { i, p ->
                val wiggle = abs(rng.normal()) * vol * p * 0.5
                ChartBar(
                    date = until.minusSeconds((count - 1 - i) * stepSeconds),
                    open = p * (1 + rng.normal() * vol * 0.3),
                    high = p + wiggle,
                    low = p - wiggle,
                    close = p,
                    volume = 1_000_000 * (0.5 + rng.next()),
                )
            }
        }

        fun fromDaily(n: Int): List<ChartBar> =
            daily.points.takeLast(n).map { ChartBar(it.date, null, null, null, it.close, it.volume) }

        val bars = when (range) {
            // 1D ends at today's price; the dashed previous close makes the day's move match the case.
            ChartRange.DAY -> walk(78, daily.lastPrice, 0.0015, 300, now)
            ChartRange.FIVE_DAYS -> walk(130, daily.lastPrice, 0.003, 900, now)
            ChartRange.MONTH -> fromDaily(22)
            ChartRange.SIX_MONTHS -> fromDaily(126)
            ChartRange.YTD -> {
                val zone = ZoneId.systemDefault()
                val start = LocalDate.now(clockZone(now, zone)).withDayOfYear(1).atStartOfDay(zone).toInstant()
                fromDaily(max(daily.points.count { it.date >= start }, 2))
            }
            ChartRange.YEAR -> {
                val first = daily.points.firstOrNull()
                val older = walk(128, first?.close ?: daily.lastPrice, 0.015, 86400, first?.date ?: now)
                older.dropLast(1) + fromDaily(125)
            }
            ChartRange.FIVE_YEARS -> walk(260, daily.lastPrice, 0.035, 7 * 86400L, now)
            ChartRange.ALL -> walk(240, daily.lastPrice, 0.07, 30 * 86400L, now)
        }
        return ChartData(symbol.uppercase(), range, "USD", bars, if (range == ChartRange.DAY) daily.previousClose else null)
    }

    private fun clockZone(now: Instant, zone: ZoneId) = java.time.Clock.fixed(now, zone)
}

/** Stable across launches (djb2), the same value the iOS app computes. */
internal fun stableHash(s: String): Int {
    var h = 5381uL
    for (b in s.encodeToByteArray()) h = h * 33uL + b.toUByte().toULong()
    return (h and 0x7fff_ffffuL).toInt()
}
