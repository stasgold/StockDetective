package com.stocksdetective.kit

/**
 * Turns pasted text from a broker, spreadsheet or notes into holdings.
 *
 * Handles column layouts like
 *     SYM   QTY   PRICE($)   DAY%
 *     VOO   238   675.99     +0.30%
 * as well as CSV ("AAPL,10,150.5"), tab-separated, and "AAPL 10" lines.
 * The first token on a line must look like a ticker and the next number is the share count.
 * Header and summary lines are skipped.
 */
object PortfolioImporter {
    data class Row(
        val symbol: String,
        val shares: Double,
        /** Price shown in the pasted text, if any (for preview only; not a cost basis). */
        val price: Double?,
    )

    data class Result(
        val rows: List<Row>,
        /** Lines that looked like data but couldn't be read. */
        val skipped: List<String>,
    )

    internal val headerWords = setOf(
        "SYM", "SYMBOL", "TICKER", "STOCK", "NAME", "QTY", "QUANTITY", "SHARES", "TOTAL", "CASH",
        "HOLDINGS", "PORTFOLIO", "PRICE", "VALUE", "USD", "ACCOUNT", "POSITIONS", "SUMMARY",
    )

    private val tickerRegex = Regex("^[A-Z][A-Z0-9]{0,5}([.\\-][A-Z]{1,2})?$")
    private val thousands = Regex("^[+-]?\\d{1,3}(,\\d{3})+(\\.\\d+)?$")
    private val plainNumber = Regex("^[+-]?(\\d+\\.?\\d*|\\.\\d+)$")

    internal fun isTicker(s: String): Boolean = s !in headerWords && tickerRegex.matches(s)

    /**
     * Parses "1,534", "675.99", "$12.5", "+6.33%". Returns null for percentages so they
     * are never mistaken for quantities.
     */
    internal fun number(raw: String): Double? {
        var s = raw.trim()
        if (s.endsWith("%")) return null
        s = s.replace("$", "").replace("₪", "").replace("€", "").replace("£", "")
        // A comma followed by exactly 3 digits is a thousands separator; otherwise a decimal comma.
        s = if (thousands.matches(s)) s.replace(",", "") else s.replace(",", ".")
        // Like Swift's Double(String): no hex, exponents or "NaN" sneaking through.
        return if (plainNumber.matches(s)) s.toDoubleOrNull() else null
    }

    private val trimChars = charArrayOf('"', '\'', ' ', '“', '”')

    internal fun tokens(line: String): List<String> {
        var parts = line.split('\t', ';', '|').flatMap { it.split(' ') }.filter { it.isNotEmpty() }
        // Plain CSV: "AAPL,10,150.5" arrives as one token.
        if (parts.size == 1 && ',' in parts[0]) {
            parts = parts[0].split(',').filter { it.isNotEmpty() }
        }
        return parts.map { it.trim(*trimChars) }.filter { it.isNotEmpty() }
    }

    fun parse(text: String): Result {
        val rows = LinkedHashMap<String, Row>()
        val skipped = mutableListOf<String>()

        for (rawLine in text.lines()) {
            val line = rawLine.trim()
            val parts = tokens(line)
            val first = parts.firstOrNull()?.uppercase()?.replace("$", "") ?: continue
            if (!isTicker(first)) continue
            val numbers = parts.drop(1).mapNotNull(::number)
            val shares = numbers.firstOrNull()
            if (shares == null || shares <= 0) {
                if (parts.size > 1 && parts[1].uppercase() !in headerWords) skipped += line
                continue
            }
            val row = Row(first, shares, numbers.getOrNull(1))
            val existing = rows[first]
            // Same ticker in several accounts: add the shares up.
            rows[first] = if (existing != null) Row(first, existing.shares + shares, existing.price ?: row.price) else row
        }
        return Result(rows.values.toList(), skipped)
    }
}
