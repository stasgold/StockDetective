package com.stocksdetective.kit

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import kotlin.math.abs

data class HoldingInput(
    val symbol: String,
    val sector: Sector,
    val shares: Double,
    val costBasis: Double? = null,
)

/**
 * Gathers evidence for every holding: prices, benchmark, market and company news.
 * Caches benchmark series and market news so a 20-stock portfolio doesn't fetch SPY 20 times.
 */
class CaseBuilder(
    val provider: MarketDataProvider,
    private val clock: () -> Instant = Instant::now,
) {
    private val ttlMillis = 5 * 60 * 1000L
    private val lock = Mutex()
    private val seriesCache = mutableMapOf<String, Triple<PriceSeries, String?, Instant>>()
    private var marketNewsCache: Pair<List<ScoredArticle>, Instant>? = null

    private fun fresh(at: Instant) = clock().toEpochMilli() - at.toEpochMilli() < ttlMillis

    internal suspend fun series(symbol: String): Pair<PriceSeries, String?> {
        lock.withLock { seriesCache[symbol]?.takeIf { fresh(it.third) }?.let { return it.first to it.second } }
        val (s, name) = provider.priceSeries(symbol)
        lock.withLock { seriesCache[symbol] = Triple(s, name, clock()) }
        return s to name
    }

    internal suspend fun marketNews(): List<ScoredArticle> {
        lock.withLock { marketNewsCache?.takeIf { fresh(it.second) }?.let { return it.first } }
        val raw = runCatching { provider.marketNews() }.getOrDefault(emptyList())
        val ranked = NewsScorer.rank(raw, "market", null, limit = 6)
        lock.withLock { marketNewsCache = ranked to clock() }
        return ranked
    }

    suspend fun buildCase(holding: HoldingInput): CaseFile {
        val symbol = holding.symbol.uppercase()
        // The stock itself is the only request that may fail. Fetch it first, so an error
        // never leaves other requests running.
        val (stock, name) = series(symbol)

        // Everything else is optional and can't fail the case. Holdings are already investigated
        // in parallel by the caller; benchmark/market series are usually served from cache.
        val bench = runCatching { series(holding.sector.etf).first }.getOrNull() ?: stock
        val market = runCatching { series("SPY").first }.getOrNull() ?: bench
        val rawNews = runCatching { provider.news(symbol) }.getOrDefault(emptyList())
        val marketNews = marketNews()
        val intraday = runCatching { provider.chart(symbol, ChartRange.DAY) }.getOrNull()

        val facts = MovementAnalyzer.analyze(stock, bench, market)
        val news = NewsScorer.rank(rawNews, symbol, name, limit = 10)
        return CaseFile(symbol, name, holding.sector, facts, news, marketNews, stock, intraday)
    }
}

/** Portfolio-level numbers for the briefing screen. */
class PortfolioSnapshot(cases: List<CaseFile>, holdings: List<HoldingInput>) {
    data class Line(
        val symbol: String,
        val value: Double,
        val dayPnL: Double,
        val dayChange: Double,
        val totalReturn: Double?,
        val character: MoveCharacter,
        val zScore: Double,
    )

    val lines: List<Line>

    init {
        val bySymbol = LinkedHashMap<String, HoldingInput>()
        for (h in holdings) bySymbol.putIfAbsent(h.symbol.uppercase(), h)
        lines = cases.mapNotNull { c ->
            val h = bySymbol[c.symbol] ?: return@mapNotNull null
            val value = h.shares * c.series.lastPrice
            val pnl = h.shares * (c.series.lastPrice - c.series.previousClose)
            val tr = h.costBasis?.takeIf { it > 0 }?.let { (c.series.lastPrice / it - 1) * 100 }
            Line(c.symbol, value, pnl, c.facts.dayChange, tr, c.facts.character, c.facts.zScore)
        }
    }

    val totalValue: Double get() = lines.sumOf { it.value }
    val dayPnL: Double get() = lines.sumOf { it.dayPnL }
    val dayChangePercent: Double
        get() {
            val prev = totalValue - dayPnL
            return if (prev > 0) dayPnL / prev * 100 else 0.0
        }

    /** Holdings that drove most of today's P&L, biggest absolute impact first. */
    val topContributors: List<Line> get() = lines.sortedByDescending { abs(it.dayPnL) }

    /** Moves worth opening a case on. */
    val suspects: List<Line> get() = lines.filter { abs(it.zScore) >= 1.5 && it.character != MoveCharacter.MARKET_DRIVEN }

    fun promptBrief(cases: List<CaseFile>, holdings: Int = 6): String {
        val out = mutableListOf("PORTFOLIO today: ${fmt(dayChangePercent)}% (${fmt(dayPnL, 0)} USD), value ${totalValue.toLong()} USD")
        val caseBySymbol = LinkedHashMap<String, CaseFile>()
        for (c in cases) caseBySymbol.putIfAbsent(c.symbol, c)
        for (l in topContributors.take(holdings)) {
            val headline = caseBySymbol[l.symbol]?.news?.firstOrNull()?.article?.title ?: "no headline"
            val weight = if (totalValue > 0) l.value / totalValue * 100 else 0.0
            out += "- ${l.symbol}: ${fmt(l.dayChange)}%, P&L ${fmt(l.dayPnL, 0)}, weight ${weight.toInt()}%, ${l.character.label}; top news: $headline"
        }
        cases.firstOrNull()?.facts?.let { out += "S&P 500 today: ${fmt(it.marketDayChange)}%" }
        return out.joinToString("\n")
    }
}
