package com.stocksdetective.kit

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** What kind of move this is, based on statistics alone (before reading any news). */
enum class MoveCharacter(val label: String) {
    /** Inside the stock's normal daily range. Probably nothing to investigate. */
    NOISE("Normal noise"),
    /** Mostly explained by the sector / market moving the same way. */
    MARKET_DRIVEN("Market-driven"),
    /** The stock moved on its own: something company-specific happened. */
    STOCK_SPECIFIC("Stock-specific"),
    /** A mix: the market moved, but the stock moved much more (or less). */
    AMPLIFIED("Amplified market move"),
}

/** Hard numbers about a move. Everything the detective later explains is grounded in this. */
data class MovementFacts(
    val symbol: String,
    val benchmark: String,
    val dayChange: Double,           // %
    val weekChange: Double,          // % over ~5 sessions
    val monthChange: Double,         // % over ~21 sessions
    val benchmarkDayChange: Double,  // %
    val marketDayChange: Double,     // % (SPY)
    val beta: Double,                // vs benchmark, 60 sessions
    val expectedMove: Double,        // beta * benchmark move, %
    val excessMove: Double,          // dayChange - expectedMove, %
    val typicalDailyMove: Double,    // stdev of daily returns, %
    val zScore: Double,              // dayChange / typicalDailyMove
    val volumeRatio: Double,         // latest volume / 20-day average
    val distanceFrom52wHigh: Double, // % below the high in the data window (<= 0)
    val character: MoveCharacter,
) {
    /** Share of the move explained by the benchmark, 0..1. */
    val explainedByMarket: Double
        get() {
            if (abs(dayChange) <= 0.0001) return 1.0
            return (expectedMove / dayChange).coerceIn(0.0, 1.0)
        }

    val isSignificant: Boolean get() = abs(zScore) >= 1.5 || abs(dayChange) >= 3
}

object MovementAnalyzer {
    /** Daily simple returns in percent. */
    fun returns(closes: List<Double>): List<Double> {
        if (closes.size < 2) return emptyList()
        val out = ArrayList<Double>(closes.size - 1)
        for (i in 1 until closes.size) {
            if (closes[i - 1] > 0) out.add((closes[i] / closes[i - 1] - 1) * 100)
        }
        return out
    }

    fun mean(xs: List<Double>): Double = if (xs.isEmpty()) 0.0 else xs.sum() / xs.size

    fun stdev(xs: List<Double>): Double {
        if (xs.size < 2) return 0.0
        val m = mean(xs)
        return sqrt(xs.sumOf { (it - m) * (it - m) } / (xs.size - 1))
    }

    /** OLS slope of stock returns against benchmark returns. */
    fun beta(stock: List<Double>, benchmark: List<Double>): Double {
        val n = min(stock.size, benchmark.size)
        if (n <= 5) return 1.0
        val s = stock.takeLast(n)
        val b = benchmark.takeLast(n)
        val ms = mean(s)
        val mb = mean(b)
        var cov = 0.0
        var varB = 0.0
        for (i in 0 until n) {
            cov += (s[i] - ms) * (b[i] - mb)
            varB += (b[i] - mb) * (b[i] - mb)
        }
        if (varB <= 0) return 1.0
        // Keep betas in a sane range so one weird window doesn't dominate.
        return (cov / varB).coerceIn(-1.0, 4.0)
    }

    internal fun change(closes: List<Double>, last: Double, sessions: Int): Double {
        if (closes.size <= sessions) {
            val first = closes.firstOrNull() ?: return 0.0
            return if (first > 0) (last / first - 1) * 100 else 0.0
        }
        val base = closes[closes.size - 1 - sessions]
        return if (base > 0) (last / base - 1) * 100 else 0.0
    }

    fun analyze(stock: PriceSeries, benchmark: PriceSeries, market: PriceSeries): MovementFacts {
        val closes = stock.points.map { it.close }
        // History excludes today's move so "typical" isn't inflated by the event we're explaining.
        val history = returns(closes).dropLast(1).takeLast(60)
        val benchHistory = returns(benchmark.points.map { it.close }).dropLast(1).takeLast(60)

        val day = stock.dayChangePercent
        val benchDay = benchmark.dayChangePercent
        val marketDay = market.dayChangePercent
        val b = beta(history, benchHistory)
        val expected = b * benchDay
        val excess = day - expected
        val typical = max(stdev(history), 0.25)
        val z = day / typical

        val volumes = stock.points.map { it.volume }
        val avgVol = mean(volumes.dropLast(1).takeLast(20))
        val volRatio = if (avgVol > 0) (volumes.lastOrNull() ?: 0.0) / avgVol else 1.0

        val high = closes.maxOrNull() ?: stock.lastPrice
        val fromHigh = if (high > 0) min((stock.lastPrice / high - 1) * 100, 0.0) else 0.0

        return MovementFacts(
            symbol = stock.symbol,
            benchmark = benchmark.symbol,
            dayChange = day,
            weekChange = change(closes, stock.lastPrice, 5),
            monthChange = change(closes, stock.lastPrice, 21),
            benchmarkDayChange = benchDay,
            marketDayChange = marketDay,
            beta = b,
            expectedMove = expected,
            excessMove = excess,
            typicalDailyMove = typical,
            zScore = z,
            volumeRatio = volRatio,
            distanceFrom52wHigh = fromHigh,
            character = classify(day, excess, expected, typical),
        )
    }

    internal fun classify(day: Double, excess: Double, expected: Double, typical: Double): MoveCharacter {
        if (abs(day) < typical && abs(excess) < typical) return MoveCharacter.NOISE
        val excessZ = abs(excess) / typical
        if (excessZ < 1) return MoveCharacter.MARKET_DRIVEN
        // Same direction as the market and the market explains a meaningful part.
        if (expected * day > 0 && abs(expected) >= 0.35 * abs(day)) return MoveCharacter.AMPLIFIED
        return MoveCharacter.STOCK_SPECIFIC
    }
}
