package com.stocksdetective.kit

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

fun makeSeries(symbol: String, closes: List<Double>, last: Double? = null, volumes: List<Double>? = null, prev: Double? = null): PriceSeries {
    val now = Instant.now()
    val points = closes.mapIndexed { i, c ->
        PricePoint(now.plusSeconds((i - closes.size) * 86400L), c, volumes?.get(i) ?: 1000.0)
    }
    return PriceSeries(symbol, points = points, lastPrice = last ?: closes.last(), previousClose = prev ?: closes.last())
}

fun makeFacts(
    day: Double = 0.0, week: Double = 0.0, month: Double = 0.0, bench: Double = 0.0, market: Double = 0.0,
    beta: Double = 1.0, expected: Double = 0.0, excess: Double? = null, typical: Double = 1.0, z: Double? = null,
    volume: Double = 1.0, fromHigh: Double = 0.0, character: MoveCharacter = MoveCharacter.NOISE,
) = MovementFacts(
    "TEST", "XLK", day, week, month, bench, market, beta, expected, excess ?: (day - expected), typical,
    z ?: (day / typical), volume, fromHigh, character,
)

fun scored(title: String, sentiment: Double = 0.0, relevance: Double = 0.8, catalysts: List<String> = emptyList(), hoursAgo: Double? = 2.0) =
    ScoredArticle(
        NewsArticle(title, source = "Wire", link = "https://x/${title.hashCode()}",
            published = hoursAgo?.let { Instant.now().minusMillis((it * 3_600_000).toLong()) }),
        sentiment, relevance, catalysts,
    )

fun makeCase(facts: MovementFacts, news: List<ScoredArticle> = emptyList(), marketNews: List<ScoredArticle> = emptyList(), name: String? = "Test Corp") =
    CaseFile(facts.symbol, name, Sector.TECHNOLOGY, facts, news, marketNews, makeSeries(facts.symbol, listOf(100.0, 101.0)))

/** Canned HTTP responses keyed by URL substring. */
class StubHttp(private val routes: List<Pair<String, String>>) : HttpClient {
    val requests = mutableListOf<String>()
    override suspend fun get(url: String): ByteArray {
        synchronized(requests) { requests += url }
        val body = routes.firstOrNull { url.contains(it.first) }?.second ?: throw MarketDataException.BadResponse(url)
        return body.encodeToByteArray()
    }
}

object Fixtures {
    fun chartJSON(
        symbol: String = "AAPL", closes: List<Double?>, volumes: List<Double?>? = null, sameDay: Boolean = true,
        name: String? = "Apple Inc.", prevClose: Double? = null,
    ): String {
        val now = Instant.now().epochSecond.toDouble()
        val ts = closes.indices.map { now - (closes.size - 1 - it) * 86400.0 }
        val marketTime = if (sameDay) now else now + 3 * 86400
        fun list(xs: List<Double?>) = xs.joinToString(",") { it?.toString() ?: "null" }
        val vols = volumes ?: closes.map { 1000.0 }
        val nameField = name?.let { ",\"longName\":\"$it\"" } ?: ""
        val prev = prevClose?.let { ",\"chartPreviousClose\":$it,\"previousClose\":$it" } ?: ""
        return """
        {"chart":{"result":[{"meta":{"currency":"USD","symbol":"$symbol","regularMarketPrice":${closes.last()},"regularMarketTime":$marketTime$nameField$prev},
        "timestamp":[${ts.joinToString(",")}],
        "indicators":{"quote":[{"open":[${list(closes)}],"high":[${list(closes.map { it?.plus(1) })}],"low":[${list(closes.map { it?.minus(1) })}],"close":[${list(closes)}],"volume":[${list(vols)}]}]}}]}}
        """.trimIndent()
    }

    fun rss(items: List<Pair<String, String?>>): String {
        val body = items.joinToString("") { (title, source) ->
            "<item><title>$title</title><link>https://n/${title.length}</link><pubDate>Fri, 06 Nov 2026 14:00:00 GMT</pubDate>" +
                (source?.let { "<source url=\"https://s\">$it</source>" } ?: "") + "<description>d</description></item>"
        }
        return "<rss><channel>$body</channel></rss>"
    }
}

/** Demo data that counts calls and can be told to fail, for cache and fallback tests. */
class CountingProvider(private val failing: Set<String> = emptySet()) : MarketDataProvider {
    private val inner = DemoMarketData()
    private val counts = ConcurrentHashMap<String, AtomicInteger>()
    private fun hit(key: String) = counts.getOrPut(key) { AtomicInteger() }.incrementAndGet()
    fun count(key: String) = counts[key]?.get() ?: 0

    override suspend fun priceSeries(symbol: String): Pair<PriceSeries, String?> {
        hit("series:$symbol")
        if (symbol in failing) throw MarketDataException.NoData(symbol)
        return inner.priceSeries(symbol)
    }
    override suspend fun news(symbol: String): List<NewsArticle> {
        hit("news:$symbol")
        if ("news" in failing) throw MarketDataException.BadResponse("news")
        return inner.news(symbol)
    }
    override suspend fun marketNews(): List<NewsArticle> {
        hit("marketNews")
        if ("marketNews" in failing) throw MarketDataException.BadResponse("market")
        return inner.marketNews()
    }
    override suspend fun search(query: String) = inner.search(query)
    override suspend fun chart(symbol: String, range: ChartRange): ChartData {
        hit("chart:$symbol")
        if ("chart" in failing) throw MarketDataException.NoData(symbol)
        return inner.chart(symbol, range)
    }
}
