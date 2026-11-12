package com.stocksdetective.kit

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId

data class SymbolMatch(val symbol: String, val name: String, val exchange: String = "") {
    val id: String get() = symbol
}

/** Where the detective gets prices and headlines. Live (Yahoo) or scripted (demo). */
interface MarketDataProvider {
    /** Six months of daily prices plus the company name, if known. */
    suspend fun priceSeries(symbol: String): Pair<PriceSeries, String?>
    suspend fun news(symbol: String): List<NewsArticle>
    suspend fun marketNews(): List<NewsArticle>
    suspend fun search(query: String): List<SymbolMatch>
    suspend fun chart(symbol: String, range: ChartRange): ChartData
}

sealed class MarketDataException(message: String) : IOException(message) {
    class BadResponse(what: String) : MarketDataException("Market data request failed for $what.")
    class NoData(symbol: String) : MarketDataException("No price data for $symbol. Check the ticker.")
}

/** Fetches a URL's body. Swappable so network code is tested without the internet. */
fun interface HttpClient {
    suspend fun get(url: String): ByteArray
}

/** Plain [HttpURLConnection] client: no extra dependencies, works on the JVM and Android. */
object DefaultHttpClient : HttpClient {
    override suspend fun get(url: String): ByteArray = withContext(Dispatchers.IO) {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36")
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            val code = conn.responseCode
            if (code !in 200..299) throw MarketDataException.BadResponse(conn.url.host ?: url)
            conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }
}

internal fun encodeQuery(s: String): String = URLEncoder.encode(s, "UTF-8")
internal fun encodePath(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

/** Free, keyless data: Yahoo Finance chart API for prices, Yahoo + Google News RSS for headlines. */
class YahooMarketData(private val http: HttpClient = DefaultHttpClient) : MarketDataProvider {

    override suspend fun priceSeries(symbol: String): Pair<PriceSeries, String?> {
        val url = "https://query1.finance.yahoo.com/v8/finance/chart/${encodePath(symbol)}?range=6mo&interval=1d"
        return YahooChartParser.parse(http.get(url), symbol)
    }

    override suspend fun news(symbol: String): List<NewsArticle> = coroutineScope {
        val q = encodeQuery(symbol)
        val feeds = listOf(
            "https://feeds.finance.yahoo.com/rss/2.0/headline?s=$q&region=US&lang=en-US" to "Yahoo Finance",
            "https://news.google.com/rss/search?q=$q+stock&hl=en-US&gl=US&ceid=US:en" to "Google News",
        )
        // Both feeds in parallel; either may fail without failing the case.
        val jobs = feeds.map { (url, source) -> source to async { runCatching { http.get(url) }.getOrNull() } }
        val articles = mutableListOf<NewsArticle>()
        for ((defaultSource, job) in jobs) {
            val data = job.await() ?: continue
            articles += withContext(Dispatchers.Default) { RSSParser.parse(data) }.map { a ->
                if (a.source.isEmpty()) a.copy(source = defaultSource) else a
            }
        }
        articles.sortedByDescending { it.published ?: Instant.MIN }
    }

    override suspend fun marketNews(): List<NewsArticle> =
        RSSParser.parse(http.get("https://news.google.com/rss/search?q=stock+market+today&hl=en-US&gl=US&ceid=US:en"))

    override suspend fun search(query: String): List<SymbolMatch> {
        val url = "https://query1.finance.yahoo.com/v1/finance/search?q=${encodeQuery(query)}&quotesCount=8&newsCount=0"
        return YahooSearchParser.parse(http.get(url))
    }

    override suspend fun chart(symbol: String, range: ChartRange): ChartData {
        val url = "https://query1.finance.yahoo.com/v8/finance/chart/${encodePath(symbol)}" +
            "?range=${range.yahooRange}&interval=${range.yahooInterval}&includePrePost=false"
        return YahooChartParser.parseChart(http.get(url), symbol, range)
    }
}

internal val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

internal fun JsonElement?.obj(): JsonObject? = this as? JsonObject
internal fun JsonElement?.arr(): JsonArray? = this as? JsonArray
internal fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
internal fun JsonElement?.num(): Double? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.doubleOrNull
internal fun JsonArray?.numAt(i: Int): Double? = this?.getOrNull(i).num()

internal object YahooChartParser {
    private class Raw(
        val meta: JsonObject,
        val timestamps: List<Double>,
        val quote: JsonObject,
        val closes: JsonArray,
    )

    private fun raw(data: ByteArray, symbol: String): Raw {
        val root = try {
            lenientJson.parseToJsonElement(data.decodeToString()).jsonObject
        } catch (e: Exception) {
            throw MarketDataException.NoData(symbol)
        }
        val r = root["chart"].obj()?.get("result").arr()?.firstOrNull().obj() ?: throw MarketDataException.NoData(symbol)
        val meta = r["meta"].obj() ?: throw MarketDataException.NoData(symbol)
        val ts = r["timestamp"].arr()?.mapNotNull { it.num() } ?: throw MarketDataException.NoData(symbol)
        val q = r["indicators"].obj()?.get("quote").arr()?.firstOrNull().obj() ?: throw MarketDataException.NoData(symbol)
        val closes = q["close"].arr() ?: throw MarketDataException.NoData(symbol)
        return Raw(meta, ts, q, closes)
    }

    fun parse(data: ByteArray, symbol: String): Pair<PriceSeries, String?> {
        val r = raw(data, symbol)
        val vols = r.quote["volume"].arr()
        val points = mutableListOf<PricePoint>()
        r.timestamps.forEachIndexed { i, t ->
            if (i >= r.closes.size) return@forEachIndexed
            val c = r.closes.numAt(i) ?: return@forEachIndexed
            points.add(PricePoint(Instant.ofEpochMilli((t * 1000).toLong()), c, vols.numAt(i) ?: 0.0))
        }
        if (points.size < 2) throw MarketDataException.NoData(symbol)
        val last = r.meta["regularMarketPrice"].num() ?: points.last().close
        // If the latest bar is today's session, yesterday's close is the bar before it.
        var prev = points.last().close
        r.meta["regularMarketTime"].num()?.let { mt ->
            val zone = ZoneId.systemDefault()
            val lastDay = points.last().date.atZone(zone).toLocalDate()
            val marketDay = Instant.ofEpochMilli((mt * 1000).toLong()).atZone(zone).toLocalDate()
            if (lastDay == marketDay) prev = points[points.size - 2].close
        }
        val series = PriceSeries(
            symbol = r.meta["symbol"].str() ?: symbol,
            currency = r.meta["currency"].str() ?: "USD",
            points = points,
            lastPrice = last,
            previousClose = prev,
        )
        return series to (r.meta["longName"].str() ?: r.meta["shortName"].str())
    }

    fun parseChart(data: ByteArray, symbol: String, range: ChartRange): ChartData {
        val r = raw(data, symbol)
        val q = r.quote
        val bars = mutableListOf<ChartBar>()
        r.timestamps.forEachIndexed { i, t ->
            if (i >= r.closes.size) return@forEachIndexed
            val c = r.closes.numAt(i) ?: return@forEachIndexed
            bars.add(
                ChartBar(
                    date = Instant.ofEpochMilli((t * 1000).toLong()),
                    open = q["open"].arr().numAt(i),
                    high = q["high"].arr().numAt(i),
                    low = q["low"].arr().numAt(i),
                    close = c,
                    volume = q["volume"].arr().numAt(i) ?: 0.0,
                )
            )
        }
        if (bars.isEmpty()) throw MarketDataException.NoData(symbol)
        val chartPrev = r.meta["chartPreviousClose"].num()
        val prev = if (range == ChartRange.DAY) r.meta["previousClose"].num() ?: chartPrev else chartPrev
        return ChartData(r.meta["symbol"].str() ?: symbol, range, r.meta["currency"].str() ?: "USD", bars, prev)
    }
}

internal object YahooSearchParser {
    fun parse(data: ByteArray): List<SymbolMatch> {
        val root = lenientJson.parseToJsonElement(data.decodeToString()).obj() ?: return emptyList()
        return (root["quotes"].arr() ?: return emptyList()).mapNotNull { it.obj() }
            .filter { (it["quoteType"].str() ?: "EQUITY") in setOf("EQUITY", "ETF") }
            .mapNotNull { q ->
                val symbol = q["symbol"].str() ?: return@mapNotNull null
                SymbolMatch(symbol, q["longname"].str() ?: q["shortname"].str() ?: symbol, q["exchDisp"].str() ?: "")
            }
    }
}
