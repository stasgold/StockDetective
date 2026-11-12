package com.stocksdetective.kit

import kotlin.math.abs

/**
 * Sanity checks for AI-written text. The on-device model occasionally gets the direction or
 * size of a move wrong ("falls 7.4%" for a stock that rose 5%); we never show that.
 */
object ReportValidator {
    // Word stems, so every tense counts: slump/slumps/slumped/slumping.
    internal val downStems = listOf(
        "fall", "fell", "drop", "plung", "sink", "sank", "sunk", "slid", "slump", "tumbl", "declin",
        "lower", "slip", "sell-off", "selloff", "lose", "losing", "lost", "crash", "plummet", "retreat", "skid",
    )
    internal val upStems = listOf(
        "rise", "rising", "rose", "risen", "jump", "surg", "soar", "rall", "climb", "gain", "higher",
        "rebound", "advanc", "spik", "rocket", "leap", "lept",
    )
    // Short words matched exactly (so "update" isn't "up" and "downgrade" isn't "down").
    internal val downExact = setOf("down", "dip", "dips", "dipped")
    internal val upExact = setOf("up", "pop", "pops", "popped")

    private fun says(tokens: Set<String>, stems: List<String>, exact: Set<String>): Boolean =
        tokens.any { it in exact } || tokens.any { t -> stems.any { t.startsWith(it) } }

    private val nonLetter = Regex("[^\\p{L}-]+")
    private val percent = Regex("""(\d+(?:\.\d+)?)\s?%""")

    /** True if the text contradicts the facts about today's move. */
    fun contradicts(text: String, facts: MovementFacts): Boolean {
        val tokens = text.lowercase().split(nonLetter).filter { it.isNotEmpty() }.toSet()
        val saysDown = says(tokens, downStems, downExact)
        val saysUp = says(tokens, upStems, upExact)
        if (facts.dayChange >= 0.5 && saysDown && !saysUp) return true
        if (facts.dayChange <= -0.5 && saysUp && !saysDown) return true

        // Every percentage quoted must match one of the numbers the model was given.
        val known = listOf(
            facts.dayChange, facts.weekChange, facts.monthChange, facts.benchmarkDayChange,
            facts.marketDayChange, facts.expectedMove, facts.excessMove, facts.typicalDailyMove,
            facts.distanceFrom52wHigh,
        ).map(::abs)
        return percentages(text).any { pct -> known.none { abs(it - pct) <= 0.15 + it * 0.03 } }
    }

    internal fun percentages(text: String): List<Double> =
        percent.findAll(text).mapNotNull { it.groupValues[1].toDoubleOrNull() }.toList()
}
