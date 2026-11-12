package com.stocksdetective.kit

import kotlin.math.abs

/**
 * A deterministic analyst used when on-device AI isn't available
 * (older device, model still downloading) and as a sanity anchor.
 */
object RuleBasedDetective {
    fun investigate(file: CaseFile): InvestigationReport {
        val f = file.facts
        val dir = if (f.dayChange >= 0) "rose" else "fell"
        val move = "${f(abs(f.dayChange), 1)}%"
        val top = file.news.firstOrNull { it.relevance >= 0.5 }
        val sentiment = file.newsSentiment

        val causes = mutableListOf<Cause>()
        if (f.character == MoveCharacter.MARKET_DRIVEN || f.character == MoveCharacter.AMPLIFIED) {
            causes += Cause(
                "Sector & market move",
                "${f.benchmark} moved ${fmt(f.benchmarkDayChange)}%. With a beta of ${f(f.beta, 2)}, that alone predicts about ${fmt(f.expectedMove)}% for ${file.symbol}.",
                file.marketNews.firstOrNull()?.article?.title,
            )
        }
        if (top != null && f.character != MoveCharacter.MARKET_DRIVEN) {
            val tag = top.catalysts.firstOrNull()?.let(::capitalizeWords) ?: "Company news"
            causes += Cause(tag, "The most relevant headline points to a company-specific catalyst.", top.article.title)
        }
        if (f.volumeRatio >= 1.8) {
            causes += Cause("Heavy trading", "Volume was ${f(f.volumeRatio, 1)}x normal, so this move had real conviction behind it.")
        }
        if (causes.isEmpty()) {
            causes += Cause("No clear catalyst", "Neither the market nor the headlines explain this move well.")
        }

        val verdict: Verdict
        val reasoning: String
        when (f.character) {
            MoveCharacter.NOISE -> {
                verdict = Verdict.NOISE
                reasoning = "A $move move is within ${file.symbol}'s typical daily range of ±${f(f.typicalDailyMove, 1)}%. Nothing to act on."
            }
            MoveCharacter.MARKET_DRIVEN -> {
                verdict = Verdict.JUSTIFIED
                reasoning = "The stock is moving in line with its sector. This is about the market, not the company."
            }
            MoveCharacter.AMPLIFIED, MoveCharacter.STOCK_SPECIFIC -> {
                val newsAgrees = top != null && sentiment * f.dayChange > 0
                val newsDisagrees = top != null && sentiment * f.dayChange < 0 && abs(sentiment) > 0.2
                when {
                    top == null -> {
                        verdict = Verdict.UNEXPLAINED
                        reasoning = "A ${f(abs(f.zScore), 1)}x-typical move with no clear headline. Could be flows, a rumor, or news not yet reported. Treat with caution."
                    }
                    newsDisagrees -> {
                        verdict = Verdict.UNEXPLAINED
                        reasoning = "Headlines lean the opposite way to the price. The market may be reacting to something else, or pricing in a second-order effect."
                    }
                    newsAgrees && abs(f.zScore) > 3.5 && f.volumeRatio < 1.3 -> {
                        verdict = Verdict.OVERREACTION
                        reasoning = "The news supports the direction, but a ${f(abs(f.zScore), 1)}x-typical move on light volume looks stretched."
                    }
                    newsAgrees && abs(f.zScore) < 1.5 && f.volumeRatio > 2 -> {
                        verdict = Verdict.UNDERREACTION
                        reasoning = "Heavy volume and supportive news, but the price has barely moved. The market may still be digesting it."
                    }
                    else -> {
                        verdict = Verdict.JUSTIFIED
                        reasoning = "The move lines up with the news, its size is plausible for this stock, and volume confirms it."
                    }
                }
            }
        }

        val watch = mutableListOf<String>()
        top?.catalysts?.firstOrNull()?.let { tag ->
            watch += when (tag) {
                "earnings", "guidance" -> "Follow-up analyst estimate revisions over the next few days"
                "analyst" -> "Whether other analysts follow with similar rating changes"
                "legal/regulatory" -> "Official statements from regulators or the company"
                "macro" -> "Next macro data release and Fed commentary"
                else -> "Company response or follow-up coverage of: ${top.article.title}"
            }
        }
        if (f.volumeRatio >= 1.8) watch += "Whether volume stays elevated tomorrow (conviction) or fades (one-off)"
        if (f.distanceFrom52wHigh < -20) watch += "Support levels: the stock is ${f(abs(f.distanceFrom52wHigh), 0)}% below its recent high"
        watch += "How ${f.benchmark} trades next session versus ${file.symbol}"

        var confidence = 45
        if (top != null) confidence += 20
        if (f.character == MoveCharacter.MARKET_DRIVEN || f.character == MoveCharacter.NOISE) confidence += 15
        if (verdict == Verdict.UNEXPLAINED) confidence -= 15

        return InvestigationReport(
            symbol = file.symbol,
            headline = "${file.symbol} $dir $move: ${f.character.label.lowercase()}",
            whatHappened = "${file.symbol} $dir $move while ${f.benchmark} moved ${fmt(f.benchmarkDayChange)}% and the S&P 500 moved ${fmt(f.marketDayChange)}%. " +
                "About ${(f.explainedByMarket * 100).toInt()}% of the move is explained by the sector; the rest (${fmt(f.excessMove)}%) is specific to ${file.symbol}.",
            causes = causes,
            verdict = verdict,
            verdictReasoning = reasoning,
            watchNext = watch.take(3),
            confidence = confidence,
            author = ReportAuthor.RULE_BASED,
        )
    }

    /** Swift's `capitalized`: "legal/regulatory" → "Legal/Regulatory". */
    internal fun capitalizeWords(s: String): String =
        s.split(Regex("(?<=[\\s/-])|(?=[\\s/-])")).joinToString("") { w -> w.replaceFirstChar { it.uppercaseChar() } }
}
