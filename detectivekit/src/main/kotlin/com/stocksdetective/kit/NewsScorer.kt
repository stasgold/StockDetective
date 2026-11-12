package com.stocksdetective.kit

import java.time.Duration
import java.time.Instant
import kotlin.math.min

/** The detective's read on a single headline. */
data class ScoredArticle(
    val article: NewsArticle,
    /** -1 (very negative) .. +1 (very positive) */
    val sentiment: Double,
    /** 0..1, how likely this headline is about a price-moving event. */
    val relevance: Double,
    /** Short tags such as "earnings", "guidance", "analyst". */
    val catalysts: List<String>,
) {
    val id: String get() = article.id
}

/**
 * Fast, deterministic headline triage. Runs before the on-device model so the model only
 * sees the handful of headlines that matter (its context window is small).
 */
object NewsScorer {
    internal val positive = setOf(
        "beat", "beats", "surge", "soar", "jump", "rally", "record", "upgrade", "raises", "raised",
        "strong", "growth", "outperform", "buyback", "approval", "approved", "wins", "partnership",
        "tops", "exceeds", "bullish", "higher", "gain", "boost", "expands", "breakthrough",
    )
    internal val negative = setOf(
        "miss", "misses", "plunge", "tumble", "slump", "fall", "falls", "drop", "drops", "sink",
        "downgrade", "cut", "cuts", "lawsuit", "probe", "investigation", "recall", "weak", "warns",
        "warning", "layoffs", "lower", "loss", "bearish", "decline", "slows", "delay", "fine",
        "antitrust", "tariff", "ban", "halt", "fraud", "resign", "underperform", "sell-off", "selloff",
    )
    internal val catalystKeywords: List<Pair<String, List<String>>> = listOf(
        "earnings" to listOf("earnings", "quarter", "q1", "q2", "q3", "q4", "eps", "revenue", "results"),
        "guidance" to listOf("guidance", "outlook", "forecast", "raises", "cuts forecast"),
        "analyst" to listOf("upgrade", "downgrade", "price target", "analyst", "rating", "initiates"),
        "legal/regulatory" to listOf("lawsuit", "probe", "sec", "ftc", "doj", "antitrust", "regulator", "court", "fine", "ban"),
        "product" to listOf("launch", "unveil", "product", "release", "approval", "fda", "trial"),
        "deal" to listOf("acquire", "acquisition", "merger", "deal", "stake", "buyout", "partnership"),
        "management" to listOf("ceo", "cfo", "resign", "steps down", "appoint", "executive"),
        "macro" to listOf("fed", "rates", "inflation", "jobs report", "cpi", "tariff", "recession", "treasury", "yields"),
        "capital" to listOf("buyback", "dividend", "offering", "split", "debt"),
    )

    private val nonWord = Regex("[^\\p{L}\\p{N}-]+")

    internal fun words(text: String): List<String> =
        text.lowercase().split(nonWord).filter { it.isNotEmpty() }

    fun score(article: NewsArticle, symbol: String, companyName: String?, now: Instant = Instant.now()): ScoredArticle {
        val text = "${article.title} ${article.summary}"
        val lower = text.lowercase()
        val tokens = words(text)
        val tokenSet = tokens.toHashSet()
        val pos = tokens.count { it in positive }
        val neg = tokens.count { it in negative }
        val total = pos + neg
        val sentiment = if (total == 0) 0.0 else (pos - neg).toDouble() / total

        val tags = catalystKeywords.filter { (_, words) ->
            words.any { w -> if (' ' in w) lower.contains(w) else w in tokenSet }
        }.map { it.first }

        var relevance = 0.2
        if (lower.contains(symbol.lowercase())) relevance += 0.25
        val first = companyName?.lowercase()?.takeIf { it.isNotEmpty() }?.split(" ")?.firstOrNull { it.isNotEmpty() }
        if (first != null && lower.contains(first)) relevance += 0.25
        relevance += min(tags.size * 0.15, 0.3)
        if (total > 0) relevance += 0.1
        article.published?.let { published ->
            val hours = Duration.between(published, now).toMillis() / 3_600_000.0
            if (hours > 72) relevance -= 0.25 else if (hours < 24) relevance += 0.1
        }
        return ScoredArticle(article, sentiment, relevance.coerceIn(0.0, 1.0), tags)
    }

    fun rank(articles: List<NewsArticle>, symbol: String, companyName: String?, limit: Int = 6, now: Instant = Instant.now()): List<ScoredArticle> {
        val seen = HashSet<String>()
        return articles.filter { seen.add(it.title.lowercase()) }
            .map { score(it, symbol, companyName, now) }
            .sortedByDescending { it.relevance } // stable, like Swift's sort for equal keys in practice
            .take(limit)
    }
}
