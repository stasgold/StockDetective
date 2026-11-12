package com.stocksdetective.app.ai

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.stocksdetective.kit.CaseFile
import com.stocksdetective.kit.Cause
import com.stocksdetective.kit.InvestigationReport
import com.stocksdetective.kit.MovementFacts
import com.stocksdetective.kit.PortfolioSnapshot
import com.stocksdetective.kit.ReportAuthor
import com.stocksdetective.kit.ReportValidator
import com.stocksdetective.kit.RuleBasedDetective
import com.stocksdetective.kit.Verdict
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.Locale

// MARK: - The JSON the model is asked to produce (the Android stand-in for iOS @Generable types)

@Serializable
data class AICause(val title: String = "", val detail: String = "", val evidence: String = "")

@Serializable
data class AIReport(
    val headline: String = "",
    val whatHappened: String = "",
    val causes: List<AICause> = emptyList(),
    val verdict: String = "",
    val verdictReasoning: String = "",
    val watchNext: List<String> = emptyList(),
    val confidence: Int = 50,
)

@Serializable
data class AIBriefing(
    val headline: String = "",
    val summary: String = "",
    val keyFindings: List<String> = emptyList(),
    val watchNext: List<String> = emptyList(),
)

data class PortfolioBriefing(
    val headline: String,
    val summary: String,
    val keyFindings: List<String>,
    val watchNext: List<String>,
    val author: ReportAuthor,
    val createdAt: Instant = Instant.now(),
)

/**
 * Wraps on-device Gemini Nano. Falls back to the rule-based detective whenever on-device AI
 * isn't available or returns something unusable, so the app always explains moves.
 */
class DetectiveAI(private val model: OnDeviceModel = GeminiNanoModel()) {
    var status: AiStatus by mutableStateOf(AiStatus.Checking)
        private set

    val isAvailable: Boolean get() = status == AiStatus.Available

    /** The on-device model handles one request at a time. */
    private val lock = Mutex()

    suspend fun refreshStatus(): AiStatus = model.status().also { status = it }

    suspend fun download() {
        model.download().collect { status = it }
        refreshStatus()
    }

    suspend fun prewarm() {
        if (refreshStatus() == AiStatus.Available) model.warmup()
    }

    /** Runs a prompt, or returns null when no model could answer. */
    suspend fun ask(prompt: String, instructions: String = INSTRUCTIONS, temperature: Float = 0.3f, maxOutputTokens: Int = 512): String? {
        if (!isAvailable) return null
        return lock.withLock {
            runCatching { model.generate(instructions, prompt, temperature, maxOutputTokens) }.getOrNull()?.takeIf { it.isNotBlank() }
        }
    }

    suspend fun investigate(file: CaseFile): InvestigationReport {
        val fallback = RuleBasedDetective.investigate(file)
        val prompt = """
            Investigate this move. The statistical pre-read suggests '${fallback.verdict.key}'; overrule it only if the headlines clearly say otherwise.

            ${file.promptBrief()}

            $REPORT_FORMAT
        """.trimIndent()
        val raw = ask(prompt, temperature = 0.3f, maxOutputTokens = 700) ?: return fallback
        val parsed = parseReport(raw) ?: return fallback
        return makeReport(file.symbol, parsed, file.facts, fallback)
    }

    suspend fun brief(snapshot: PortfolioSnapshot, cases: List<CaseFile>, reports: Map<String, InvestigationReport>): PortfolioBriefing {
        val fallback = ruleBasedBriefing(snapshot, reports)
        if (cases.isEmpty()) return fallback
        val prompt = """
            Write today's portfolio briefing. Focus on the holdings that moved the portfolio most and explain why.

            ${snapshot.promptBrief(cases, holdings = 6)}

            $BRIEFING_FORMAT
        """.trimIndent()
        val raw = ask(prompt, temperature = 0.4f) ?: return fallback
        val b = parseBriefing(raw) ?: return fallback
        return PortfolioBriefing(b.headline, b.summary, b.keyFindings.take(4), b.watchNext.take(3), ReportAuthor.ON_DEVICE_AI)
    }

    companion object {
        val INSTRUCTIONS = """
            You are Stocks Detective, a sharp, calm financial analyst investigating why a stock moved.
            Rules:
            - Use ONLY the numbers and headlines in the brief. Never invent news, numbers or events.
            - First decide if the move is market-wide (compare with the benchmark and beta) or company-specific.
            - Match the move to the headlines. If a stock-specific move has no supporting headline, say so and use verdict 'unexplained'.
            - 'noise' means the move is within the stock's typical daily range.
            - 'overreaction' means the move is larger than the evidence supports; 'underreaction' means smaller.
            - Write for a smart non-expert. Be concrete and brief.
            - Do not tell the user to buy or sell.
        """.trimIndent()

        val REPORT_FORMAT = """
            Answer with ONLY a JSON object, no other text:
            {"headline": "one punchy sentence of at most 12 words naming the main reason for the move",
             "whatHappened": "2-3 plain-English sentences explaining what happened, citing the key numbers",
             "causes": [{"title": "short name of the cause, 2-5 words", "detail": "one sentence on how this cause moved the stock", "evidence": "the exact headline from the brief that supports it, or an empty string"}],
             "verdict": "justified | overreaction | underreaction | unexplained | noise",
             "verdictReasoning": "2 sentences explaining the verdict, comparing move size, volume and news",
             "watchNext": ["3 specific, checkable things to watch in the coming days"],
             "confidence": 0-100}
            Give at most 3 causes, most important first.
        """.trimIndent()

        val BRIEFING_FORMAT = """
            Answer with ONLY a JSON object, no other text:
            {"headline": "one sentence headline for the whole portfolio today",
             "summary": "3-4 sentences: what drove the portfolio today and whether it is a market or stock story",
             "keyFindings": ["one per notable holding, at most 4"],
             "watchNext": ["3 things to watch across the portfolio next"]}
        """.trimIndent()

        private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

        /** The JSON object in a model reply, tolerating ```json fences and chatter around it. */
        internal fun extractJSON(raw: String): String? {
            val start = raw.indexOf('{')
            val end = raw.lastIndexOf('}')
            return if (start in 0 until end) raw.substring(start, end + 1) else null
        }

        internal fun parseReport(raw: String): AIReport? {
            val r = runCatching { json.decodeFromString(AIReport.serializer(), extractJSON(raw) ?: return null) }.getOrNull() ?: return null
            return r.takeIf { it.headline.isNotBlank() && it.whatHappened.isNotBlank() }
        }

        internal fun parseBriefing(raw: String): AIBriefing? {
            val b = runCatching { json.decodeFromString(AIBriefing.serializer(), extractJSON(raw) ?: return null) }.getOrNull() ?: return null
            return b.takeIf { it.headline.isNotBlank() && it.summary.isNotBlank() }
        }

        /**
         * Assembles a report from the model's fields, replacing any headline or summary that
         * misstates the move with the rule-based version.
         */
        internal fun makeReport(symbol: String, r: AIReport, facts: MovementFacts, fallback: InvestigationReport): InvestigationReport {
            val causes = r.causes.filter { it.title.isNotBlank() }.take(3)
                .map { Cause(it.title, it.detail, it.evidence.ifBlank { null }) }
            val watch = r.watchNext.filter { it.isNotBlank() }.take(3)
            return InvestigationReport(
                symbol = symbol,
                headline = if (ReportValidator.contradicts(r.headline, facts)) fallback.headline else r.headline,
                whatHappened = if (ReportValidator.contradicts(r.whatHappened, facts)) fallback.whatHappened else r.whatHappened,
                causes = causes.ifEmpty { fallback.causes },
                verdict = Verdict.fromKey(r.verdict) ?: fallback.verdict,
                verdictReasoning = r.verdictReasoning.ifBlank { fallback.verdictReasoning },
                watchNext = watch.ifEmpty { fallback.watchNext },
                confidence = r.confidence,
                author = ReportAuthor.ON_DEVICE_AI,
            )
        }

        fun ruleBasedBriefing(snapshot: PortfolioSnapshot, reports: Map<String, InvestigationReport>): PortfolioBriefing {
            val pct = String.format(Locale.US, "%+.2f%%", snapshot.dayChangePercent)
            val top = snapshot.topContributors.take(3)
            val names = top.joinToString(", ") { it.symbol }
            val marketDriven = snapshot.lines.count {
                it.character == com.stocksdetective.kit.MoveCharacter.MARKET_DRIVEN || it.character == com.stocksdetective.kit.MoveCharacter.NOISE
            }
            val story = if (marketDriven * 2 >= snapshot.lines.size) "mostly a market story" else "driven by company-specific news"
            return PortfolioBriefing(
                headline = "Portfolio $pct today, led by ${names.ifEmpty { "no single holding" }}",
                summary = "Your portfolio moved $pct (${String.format(Locale.US, "%+.0f", snapshot.dayPnL)} USD). Today was $story. " +
                    "${snapshot.suspects.size} holding(s) moved more than usual on their own and are worth a closer look.",
                keyFindings = top.mapNotNull { reports[it.symbol]?.headline },
                // Holdings with the same catalyst share a to-do; list it once.
                watchNext = top.mapNotNull { reports[it.symbol]?.watchNext?.firstOrNull() }.distinct().take(3),
                author = ReportAuthor.RULE_BASED,
            )
        }
    }
}
