package com.stocksdetective.kit

import java.time.Duration
import java.time.Instant

enum class Verdict(val label: String) {
    JUSTIFIED("Justified"),
    OVERREACTION("Overreaction"),
    UNDERREACTION("Underreaction"),
    UNEXPLAINED("Unexplained"),
    NOISE("Just noise");

    /** The lower-case word the model is asked to answer with. */
    val key: String get() = name.lowercase()

    companion object {
        fun fromKey(s: String?): Verdict? {
            val k = s?.trim()?.lowercase()?.replace(" ", "")?.replace("_", "") ?: return null
            return entries.firstOrNull { it.key == k } ?: if (k.startsWith("justnoise")) NOISE else null
        }
    }
}

data class Cause(
    val title: String,
    val detail: String,
    /** Headline that supports this cause, if any. */
    val evidence: String? = null,
)

enum class ReportAuthor {
    /** Gemini Nano, running on-device through Android AICore. */
    ON_DEVICE_AI,
    RULE_BASED;

    val isAI: Boolean get() = this != RULE_BASED
}

/** The detective's finished write-up for one holding. */
class InvestigationReport(
    val symbol: String,
    val headline: String,
    val whatHappened: String,
    val causes: List<Cause>,
    val verdict: Verdict,
    val verdictReasoning: String,
    val watchNext: List<String>,
    confidence: Int,
    val author: ReportAuthor,
    val createdAt: Instant = Instant.now(),
) {
    /** 0..100 */
    val confidence: Int = confidence.coerceIn(0, 100)
}

/** Everything gathered about one holding before writing the report. */
data class CaseFile(
    val symbol: String,
    val companyName: String?,
    val sector: Sector,
    val facts: MovementFacts,
    val news: List<ScoredArticle>,
    val marketNews: List<ScoredArticle>,
    val series: PriceSeries,
    /** Last trading day's intraday prices (for the row sparkline), if available. */
    val intraday: ChartData? = null,
) {
    val newsSentiment: Double
        get() {
            val relevant = news.filter { it.relevance >= 0.4 }
            if (relevant.isEmpty()) return 0.0
            return relevant.sumOf { it.sentiment * it.relevance } / relevant.size
        }

    /**
     * Compact, number-dense brief for the on-device model. Kept short on purpose:
     * Gemini Nano has a small context window.
     */
    fun promptBrief(headlines: Int = 5, marketHeadlines: Int = 3, now: Instant = Instant.now()): String {
        val f = facts
        val lines = mutableListOf<String>()
        lines += "STOCK: $symbol${companyName?.let { " ($it)" } ?: ""}, sector benchmark ${f.benchmark}"
        lines += "TODAY: ${fmt(f.dayChange)}%, 5-day ${fmt(f.weekChange)}%, 1-month ${fmt(f.monthChange)}%"
        lines += "BENCHMARK ${f.benchmark} today: ${fmt(f.benchmarkDayChange)}%; S&P 500 (SPY): ${fmt(f.marketDayChange)}%"
        lines += "BETA ${fmt(f.beta, 2)}; expected move from sector ${fmt(f.expectedMove)}%; stock-specific (excess) move ${fmt(f.excessMove)}%"
        lines += "TYPICAL daily move ±${fmt(f.typicalDailyMove)}%; today is ${fmt(kotlin.math.abs(f.zScore), 1)}x typical"
        lines += "VOLUME ${fmt(f.volumeRatio, 1)}x its 20-day average; ${fmt(f.distanceFrom52wHigh)}% from recent high"
        lines += "STATISTICAL READ: ${f.character.label}"
        lines += "COMPANY HEADLINES (most relevant first):"
        if (news.isEmpty()) lines += "- none found"
        for (n in news.take(headlines)) {
            val tags = if (n.catalysts.isEmpty()) "" else " [${n.catalysts.joinToString(", ")}]"
            lines += "- ${n.article.title}${ageText(n.article.published, now)}$tags"
        }
        if (marketNews.isNotEmpty()) {
            lines += "MARKET HEADLINES:"
            for (n in marketNews.take(marketHeadlines)) lines += "- ${n.article.title}"
        }
        return lines.joinToString("\n")
    }

    private fun ageText(date: Instant?, now: Instant): String {
        date ?: return ""
        val h = Duration.between(date, now).toHours().toInt()
        return when {
            h < 1 -> " (just now)"
            h < 48 -> " (${h}h ago)"
            else -> " (${h / 24}d ago)"
        }
    }
}
