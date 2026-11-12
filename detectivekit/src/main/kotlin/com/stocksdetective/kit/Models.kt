package com.stocksdetective.kit

import java.time.Instant
import java.util.Locale

/** One daily bar of price history. */
data class PricePoint(val date: Instant, val close: Double, val volume: Double)

/** Price history plus the latest live price for one symbol. */
data class PriceSeries(
    val symbol: String,
    val currency: String = "USD",
    val points: List<PricePoint>,
    /** Latest trade price (may be intraday, newer than the last daily close). */
    val lastPrice: Double,
    /** Previous session's close, used for the "today" move. */
    val previousClose: Double,
) {
    val dayChangePercent: Double
        get() = if (previousClose > 0) (lastPrice / previousClose - 1) * 100 else 0.0
}

data class NewsArticle(
    val title: String,
    val summary: String = "",
    val source: String = "",
    val link: String = "",
    val published: Instant? = null,
) {
    val id: String get() = link.ifEmpty { title }
}

/** Sector benchmarks we compare a stock against. */
enum class Sector(val etf: String, val displayName: String) {
    TECHNOLOGY("XLK", "Technology"),
    COMMUNICATION("XLC", "Communication"),
    CONSUMER_DISCRETIONARY("XLY", "Consumer Discretionary"),
    CONSUMER_STAPLES("XLP", "Consumer Staples"),
    ENERGY("XLE", "Energy"),
    FINANCIALS("XLF", "Financials"),
    HEALTH_CARE("XLV", "Health Care"),
    INDUSTRIALS("XLI", "Industrials"),
    MATERIALS("XLB", "Materials"),
    REAL_ESTATE("XLRE", "Real Estate"),
    UTILITIES("XLU", "Utilities"),
    BROAD_MARKET("SPY", "Broad Market / Other");

    companion object {
        private val map: Map<Sector, Set<String>> = mapOf(
            TECHNOLOGY to setOf("AAPL", "MSFT", "NVDA", "AMD", "INTC", "AVGO", "ORCL", "CRM", "ADBE", "QCOM", "TSM", "ASML", "MU", "PLTR", "SNOW", "NOW", "SHOP", "ARM"),
            COMMUNICATION to setOf("GOOGL", "GOOG", "META", "NFLX", "DIS", "T", "VZ", "TMUS", "SPOT", "RDDT"),
            CONSUMER_DISCRETIONARY to setOf("AMZN", "TSLA", "HD", "NKE", "MCD", "SBUX", "LOW", "BKNG", "ABNB", "UBER", "RIVN"),
            CONSUMER_STAPLES to setOf("WMT", "COST", "PG", "KO", "PEP", "PM", "TGT"),
            ENERGY to setOf("XOM", "CVX", "COP", "OXY", "SLB"),
            FINANCIALS to setOf("JPM", "BAC", "GS", "MS", "WFC", "V", "MA", "BRK-B", "PYPL", "COIN", "HOOD", "SQ", "XYZ"),
            HEALTH_CARE to setOf("UNH", "JNJ", "LLY", "PFE", "MRK", "ABBV", "NVO", "MRNA", "TMO"),
            INDUSTRIALS to setOf("BA", "CAT", "GE", "HON", "UPS", "LMT", "RTX", "DE"),
            MATERIALS to setOf("LIN", "FCX", "NEM", "DOW"),
            REAL_ESTATE to setOf("PLD", "AMT", "O", "SPG"),
            UTILITIES to setOf("NEE", "DUK", "SO"),
        )

        /** A small built-in guess so most popular tickers get a sensible benchmark without asking. */
        fun guess(symbol: String): Sector {
            val s = symbol.uppercase()
            return map.entries.firstOrNull { s in it.value }?.key ?: BROAD_MARKET
        }

        fun fromName(name: String?): Sector = entries.firstOrNull { it.name == name } ?: BROAD_MARKET
    }
}

/** "+1.2" style number used in model prompts and rule-based text. */
internal fun fmt(v: Double, digits: Int = 1): String {
    val s = String.format(Locale.US, "%.${digits}f", v)
    return if (v > 0 && !s.startsWith("+")) "+$s" else s
}

internal fun f(v: Double, digits: Int): String = String.format(Locale.US, "%.${digits}f", v)
