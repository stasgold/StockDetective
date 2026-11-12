package com.stocksdetective.app.ai

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.stocksdetective.kit.CaseFile
import com.stocksdetective.kit.InvestigationReport
import com.stocksdetective.kit.MarketDataProvider
import com.stocksdetective.kit.NewsScorer
import java.util.Locale
import java.util.UUID

/** Looks up price history for any ticker (the iOS PriceHistoryTool). */
class PriceHistoryTool(private val provider: MarketDataProvider) {
    suspend fun history(symbol: String, days: Int): String {
        val (series, _) = provider.priceSeries(symbol.uppercase())
        val pts = series.points.takeLast(maxOf(days, 1))
        val first = pts.firstOrNull() ?: return "No data for $symbol."
        val change = (series.lastPrice / first.close - 1) * 100
        val closes = pts.takeLast(10).joinToString(", ") { String.format(Locale.US, "%.2f", it.close) }
        return "${series.symbol}: last ${String.format(Locale.US, "%.2f", series.lastPrice)}, " +
            "today ${String.format(Locale.US, "%+.2f", series.dayChangePercent)}%, " +
            "${String.format(Locale.US, "%+.2f", change)}% over ${pts.size} days. Recent closes: $closes"
    }
}

/** Looks up fresh headlines for any ticker (the iOS NewsLookupTool). */
class NewsLookupTool(private val provider: MarketDataProvider) {
    suspend fun headlines(symbol: String): String {
        val s = symbol.uppercase()
        val ranked = NewsScorer.rank(provider.news(s), s, null, limit = 5)
        if (ranked.isEmpty()) return "No recent headlines found for $s."
        return ranked.joinToString("\n") { "- ${it.article.title}" }
    }
}

data class ChatMessage(val role: Role, val text: String, val id: String = UUID.randomUUID().toString()) {
    enum class Role { USER, DETECTIVE }
}

/**
 * A follow-up conversation about one case. On iOS the model calls tools itself; Gemini Nano's
 * tool calling isn't available on every phone, so here the detective looks up the tickers a
 * question mentions before asking the model, and hands it the results.
 */
class Interrogation(
    private val file: CaseFile,
    report: InvestigationReport?,
    provider: MarketDataProvider,
    private val ai: DetectiveAI,
) {
    val messages = mutableStateListOf<ChatMessage>()
    var isThinking by mutableStateOf(false)
        private set

    private val prices = PriceHistoryTool(provider)
    private val news = NewsLookupTool(provider)

    private val context = """
        ${DetectiveAI.INSTRUCTIONS}
        You are now answering follow-up questions about this case. TOOL RESULTS, when present, hold fresh prices and headlines for other tickers.
        Keep answers under 120 words. Answer in plain text, not JSON.

        CASE BRIEF
        ${file.promptBrief()}
        YOUR EARLIER VERDICT: ${report?.verdict?.label ?: "none"} - ${report?.verdictReasoning ?: ""}
    """.trimIndent()

    val suggestions: List<String> = listOf(
        "Is this a buying opportunity or a warning sign?",
        "How did ${file.facts.benchmark} and peers do today?",
        "What would change your verdict?",
    )

    suspend fun ask(question: String) {
        val q = question.trim()
        if (q.isEmpty() || isThinking) return
        messages += ChatMessage(ChatMessage.Role.USER, q)
        if (!ai.isAvailable) {
            messages += ChatMessage(
                ChatMessage.Role.DETECTIVE,
                "Follow-up questions need on-device AI (Gemini Nano). Check Settings › On-device AI on a supported phone.",
            )
            return
        }
        isThinking = true
        try {
            val tools = lookups(q)
            val transcript = messages.dropLast(1).takeLast(6).joinToString("\n") {
                (if (it.role == ChatMessage.Role.USER) "USER: " else "DETECTIVE: ") + it.text
            }
            val prompt = buildString {
                if (transcript.isNotEmpty()) append("CONVERSATION SO FAR\n$transcript\n\n")
                if (tools.isNotEmpty()) append("TOOL RESULTS\n$tools\n\n")
                append("QUESTION: $q")
            }
            val answer = ai.ask(prompt, instructions = context, temperature = 0.5f, maxOutputTokens = 300)
            messages += ChatMessage(
                ChatMessage.Role.DETECTIVE,
                answer?.trim() ?: "I couldn't finish that line of inquiry. Try rephrasing.",
            )
        } finally {
            isThinking = false
        }
    }

    /** Prices and headlines for tickers the question mentions (plus the benchmark for "peers" questions). */
    private suspend fun lookups(question: String): String {
        val symbols = tickersIn(question, file.symbol, file.facts.benchmark)
        val out = mutableListOf<String>()
        for (s in symbols) {
            runCatching { prices.history(s, 20) }.getOrNull()?.let { out += it }
            runCatching { news.headlines(s) }.getOrNull()?.let { out += "$s headlines:\n$it" }
        }
        return out.joinToString("\n")
    }

    companion object {
        private val notTickers = setOf(
            "I", "A", "AI", "CEO", "CFO", "CTO", "US", "USA", "UK", "EU", "ETF", "EPS", "FED", "SEC", "FTC", "DOJ", "IPO", "GDP",
            "CPI", "OK", "IS", "IT", "THE", "AND", "OR", "VS", "PE", "P", "E", "Q", "YOY", "QOQ", "ATH", "USD", "AM", "PM", "WHY", "WHAT", "HOW",
        )
        private val tickerToken = Regex("""\$?\b[A-Z][A-Z0-9]{0,4}(?:[.\-][A-Z]{1,2})?\b""")
        private val peerWords = listOf("peer", "benchmark", "sector", "market", "s&p", "index")

        /** Up to 3 ticker-looking words, other than the case's own stock. */
        internal fun tickersIn(question: String, own: String, benchmark: String): List<String> {
            val found = LinkedHashSet<String>()
            for (m in tickerToken.findAll(question)) {
                val raw = m.value
                val t = raw.removePrefix("$")
                if ((raw.startsWith("$") || t.length >= 2) && t !in notTickers && t != own) found += t
            }
            val lower = question.lowercase()
            if (peerWords.any { it in lower }) { found += benchmark; found += "SPY" }
            found.remove(own)
            return found.take(3)
        }
    }
}
