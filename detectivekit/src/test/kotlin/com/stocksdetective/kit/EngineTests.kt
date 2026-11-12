package com.stocksdetective.kit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.math.abs

class StatisticsTests {
    @Test fun statistics() {
        val r = MovementAnalyzer.returns(listOf(100.0, 110.0, 99.0))
        assertEquals(2, r.size)
        assertEquals(10.0, r[0], 1e-9)
        assertEquals(-10.0, r[1], 1e-9)
        assertEquals(2.138, MovementAnalyzer.stdev(listOf(2.0, 4.0, 4.0, 4.0, 5.0, 5.0, 7.0, 9.0)), 0.001)
        val b = listOf(1.0, -1.0, 2.0, -2.0, 0.5, -0.5, 1.5)
        assertEquals(2.0, MovementAnalyzer.beta(b.map { it * 2 }, b), 1e-9)
    }

    private fun walk(n: Int, up: Double, down: Double, start: Double, noise: (Int) -> Double = { 0.0 }): List<Double> {
        val out = mutableListOf(start)
        for (i in 0 until n) out += out.last() * (1 + (if (i % 2 == 0) up else down) / 100 + noise(i))
        return out
    }

    @Test fun marketDrivenMove() {
        // Stock tracks benchmark 1:1 with tiny noise; today both drop 3%.
        val bench = walk(60, 1.0, -0.9, 100.0)
        val stock = walk(60, 1.0, -0.9, 50.0) { i -> if (i % 3 == 0) 0.0005 else -0.0005 }
        val b = makeSeries("XLK", bench, last = bench.last() * 0.97)
        val s = makeSeries("AAA", stock, last = stock.last() * 0.97)
        val facts = MovementAnalyzer.analyze(s, b, b)
        assertEquals(MoveCharacter.MARKET_DRIVEN, facts.character)
        assertTrue(facts.explainedByMarket > 0.8)
    }

    @Test fun stockSpecificMove() {
        val bench = walk(60, 0.8, -0.7, 100.0)
        val stock = walk(60, 0.8, -0.7, 50.0)
        val b = makeSeries("XLK", bench, last = bench.last() * 1.001)
        val s = makeSeries("AAA", stock, last = stock.last() * 0.92)
        val facts = MovementAnalyzer.analyze(s, b, b)
        assertEquals(MoveCharacter.STOCK_SPECIFIC, facts.character)
        assertTrue(facts.excessMove < -7)
    }

    @Test fun minimalHistory() {
        val s = makeSeries("A", listOf(10.0, 11.0), last = 12.0, volumes = listOf(0.0, 0.0))
        val f = MovementAnalyzer.analyze(s, s, s)
        assertEquals("floor when there's no history", 0.25, f.typicalDailyMove, 0.0)
        assertEquals("no volume data → neutral", 1.0, f.volumeRatio, 0.0)
        assertEquals(1.0, f.beta, 0.0)
    }

    @Test fun classify() {
        assertEquals(MoveCharacter.NOISE, MovementAnalyzer.classify(0.5, 0.5, 0.0, 1.0))
        assertEquals(MoveCharacter.MARKET_DRIVEN, MovementAnalyzer.classify(-3.0, -0.5, -2.5, 1.0))
        assertEquals(MoveCharacter.AMPLIFIED, MovementAnalyzer.classify(-6.0, -3.0, -3.0, 1.0))
        assertEquals(MoveCharacter.STOCK_SPECIFIC, MovementAnalyzer.classify(-6.0, -6.0, 0.0, 1.0))
        assertEquals(1.0, makeFacts(day = 0.0).explainedByMarket, 0.0)
        assertEquals(0.5, makeFacts(day = 4.0, expected = 2.0).explainedByMarket, 1e-9)
    }
}

class NewsScorerTests {
    @Test fun sentimentAndCatalysts() {
        val s = NewsScorer.score(NewsArticle("Acme misses earnings estimates, cuts guidance", published = Instant.now()), "ACME", "Acme Corp")
        assertTrue(s.sentiment < 0)
        assertTrue("earnings" in s.catalysts)
        assertTrue("guidance" in s.catalysts)
        assertTrue(s.relevance > 0.6)
        assertTrue("macro" in NewsScorer.score(NewsArticle("Fed holds rates steady"), "X", null).catalysts)
        assertTrue("multi-word keyword", "management" in NewsScorer.score(NewsArticle("CEO steps down at Acme"), "X", null).catalysts)
    }

    @Test fun relevanceFactors() {
        val now = Instant.now()
        assertEquals(0.2, NewsScorer.score(NewsArticle("Nothing here"), "ZZZ", null, now).relevance, 1e-9)
        assertEquals(0.45, NewsScorer.score(NewsArticle("About zzz today"), "ZZZ", null, now).relevance, 1e-9)
        assertEquals(0.45, NewsScorer.score(NewsArticle("Widgets inc today"), "ZZZ", "Widgets Inc", now).relevance, 1e-9)
        assertEquals(0.3, NewsScorer.score(NewsArticle("Nothing here", published = now.minusSeconds(3600)), "ZZZ", null, now).relevance, 1e-9)
        assertEquals(0.0, NewsScorer.score(NewsArticle("Nothing here", published = now.minusSeconds(100 * 3600)), "ZZZ", null, now).relevance, 1e-9)
    }

    @Test fun rankDedupesSortsAndLimits() {
        val articles = listOf(
            NewsArticle("Unrelated story"),
            NewsArticle("ACME beats earnings"),
            NewsArticle("acme beats earnings"),
            NewsArticle("ACME CEO interview"),
        )
        val ranked = NewsScorer.rank(articles, "ACME", null, limit = 2)
        assertEquals(2, ranked.size)
        assertEquals("ACME beats earnings", ranked[0].article.title)
        assertTrue(ranked[0].relevance >= ranked[1].relevance)
        assertEquals("duplicate title removed", 3, NewsScorer.rank(articles, "ACME", null).size)
    }
}

class RSSParserTests {
    @Test fun googleStyleTitleSourceSplit() {
        val xml = """<rss><channel><item><title>Apple beats estimates - Reuters</title><link>https://x.y/1</link>
            <pubDate>Fri, 06 Nov 2026 14:00:00 GMT</pubDate><description><![CDATA[<p>Strong &amp; steady</p>]]></description></item></channel></rss>"""
        val items = RSSParser.parse(xml.encodeToByteArray())
        assertEquals(1, items.size)
        assertEquals("Apple beats estimates", items[0].title)
        assertEquals("Reuters", items[0].source)
        assertEquals("Strong & steady", items[0].summary)
        assertEquals("https://x.y/1", items[0].link)
        assertNotNull(items[0].published)
    }

    @Test fun sourceElementAndTitleSuffix() {
        val items = RSSParser.parse(Fixtures.rss(listOf("Chips rally - Bloomberg" to "Bloomberg", "Plain title" to "Reuters")).encodeToByteArray())
        assertEquals("Chips rally", items[0].title)
        assertEquals("Bloomberg", items[0].source)
        assertEquals("Plain title", items[1].title)
        assertEquals("Reuters", items[1].source)
    }

    @Test fun skipsEmptyTitlesBadDatesAndJunk() {
        val xml = "<rss><channel><title>Feed</title><item><title></title></item><item><title>Kept</title><pubDate>yesterday</pubDate></item></channel></rss>"
        val items = RSSParser.parse(xml.encodeToByteArray())
        assertEquals(listOf("Kept"), items.map { it.title })
        assertNull(items[0].published)
        assertEquals(0, RSSParser.parse("not xml".encodeToByteArray()).size)
    }

    @Test fun yahooDateFormat() {
        assertNotNull(RSSParser.parseDate("Thu, 12 Nov 2026 14:00:00 +0000"))
        assertNotNull(RSSParser.parseDate("Thu, 5 Nov 2026 09:03:00 GMT"))
    }

    @Test fun stripHTML() {
        assertEquals("A \"B\" 'C' <D> E", RSSParser.stripHTML("<b>A</b>&nbsp;&quot;B&quot; &#39;C&apos; &lt;D&gt;  \n E"))
        assertEquals(280, RSSParser.stripHTML("x".repeat(500)).length)
    }
}

class YahooParserTests {
    @Test fun parseSeriesSameDay() {
        val (series, name) = YahooChartParser.parse(Fixtures.chartJSON(closes = listOf(100.0, null, 200.0, 190.0)).encodeToByteArray(), "AAPL")
        assertEquals("Apple Inc.", name)
        assertEquals("null closes skipped", 3, series.points.size)
        assertEquals(200.0, series.previousClose, 0.0)
        assertEquals(190.0, series.lastPrice, 0.0)
        assertEquals(-5.0, series.dayChangePercent, 1e-9)
    }

    @Test fun parseSeriesMarketClosed() {
        val (series, name) = YahooChartParser.parse(Fixtures.chartJSON(closes = listOf(100.0, 200.0), sameDay = false, name = null).encodeToByteArray(), "AAPL")
        assertEquals("no bar for today yet → last bar is the previous close", 200.0, series.previousClose, 0.0)
        assertNull(name)
    }

    @Test fun missingFields() {
        val json = """{"chart":{"result":[{"meta":{"symbol":"X"},"timestamp":[1,2,3],"indicators":{"quote":[{"close":[1,2,4]}]}}]}}"""
        val (s, name) = YahooChartParser.parse(json.encodeToByteArray(), "X")
        assertEquals("USD", s.currency)
        assertEquals(4.0, s.lastPrice, 0.0)
        assertEquals(4.0, s.previousClose, 0.0)
        assertEquals(listOf(0.0, 0.0, 0.0), s.points.map { it.volume })
        assertNull(name)
        val c = YahooChartParser.parseChart(json.encodeToByteArray(), "X", ChartRange.MONTH)
        assertNull(c.bars[0].open)
    }

    @Test fun parseErrors() {
        for (bad in listOf("""{"chart":{"result":null}}""", Fixtures.chartJSON(closes = listOf(100.0)), "garbage")) {
            val failed = runCatching { YahooChartParser.parse(bad.encodeToByteArray(), "X") }.exceptionOrNull()
            assertTrue(bad, failed is MarketDataException.NoData)
        }
    }

    @Test fun parseChart() {
        val json = """{"chart":{"result":[{"meta":{"currency":"USD","symbol":"TSM","chartPreviousClose":353.1,"previousClose":353.1},
            "timestamp":[1,2,3],"indicators":{"quote":[{"open":[380,null,376],"high":[390,null,380],"low":[370,null,372],"close":[381,null,375.44],"volume":[100,null,300]}]}}]}}"""
        val c = YahooChartParser.parseChart(json.encodeToByteArray(), "TSM", ChartRange.DAY)
        assertEquals(2, c.bars.size)
        assertEquals(380.0, c.bars[1].high!!, 0.0)
        assertEquals((375.44 / 353.1 - 1) * 100, c.changePercent!!, 1e-9)
    }

    @Test fun parseSearch() {
        val json = """{"quotes":[{"symbol":"AAPL","longname":"Apple Inc.","exchDisp":"NASDAQ","quoteType":"EQUITY"},
            {"symbol":"AAPL250101C","quoteType":"OPTION"},{"symbol":"VOO","shortname":"Vanguard S&P 500","quoteType":"ETF"},{"symbol":"X"}]}"""
        val r = YahooSearchParser.parse(json.encodeToByteArray())
        assertEquals(listOf("AAPL", "VOO", "X"), r.map { it.symbol })
        assertEquals("NASDAQ", r[0].exchange)
        assertEquals("Vanguard S&P 500", r[1].name)
        assertEquals(emptyList<SymbolMatch>(), YahooSearchParser.parse("{}".encodeToByteArray()))
    }
}

class ChartTests {
    private fun bars(closes: List<Double>, stepSeconds: Long = 300) =
        closes.mapIndexed { i, c -> ChartBar(Instant.ofEpochSecond(1_000_000 + i * stepSeconds), null, null, null, c, 0.0) }

    @Test fun rangeProperties() {
        assertEquals(listOf("1D", "5D", "1M", "6M", "YTD", "1Y", "5Y", "All"), ChartRange.entries.map { it.label })
        assertEquals(listOf("1d", "5d", "1mo", "6mo", "ytd", "1y", "5y", "max"), ChartRange.entries.map { it.yahooRange })
        assertEquals(listOf(ChartRange.DAY, ChartRange.FIVE_DAYS), ChartRange.entries.filter { it.isIntraday })
    }

    @Test fun baselineAndChange() {
        val withPrev = ChartData("A", ChartRange.DAY, "USD", bars(listOf(105.0, 110.0)), 100.0)
        assertEquals(10.0, withPrev.change!!, 1e-9)
        assertEquals(10.0, withPrev.changePercent!!, 1e-9)
        val noPrev = ChartData("A", ChartRange.MONTH, "USD", bars(listOf(50.0, 40.0)), null)
        assertEquals(-20.0, noPrev.changePercent!!, 1e-9)
        assertEquals(50.0, ChartData("A", ChartRange.MONTH, "USD", bars(listOf(50.0, 40.0)), 0.0).baseline!!, 0.0)
        val empty = ChartData("A", ChartRange.MONTH, "USD", emptyList(), null)
        assertNull(empty.baseline); assertNull(empty.changePercent)
        assertTrue(empty.priceDomain.start < empty.priceDomain.endInclusive)
    }

    @Test fun xSlotsAndDomain() {
        assertEquals(79, ChartData("A", ChartRange.DAY, "USD", bars(listOf(1.0, 2.0, 3.0)), null).xSlots)
        assertEquals(100, ChartData("A", ChartRange.DAY, "USD", bars(List(100) { 1.0 }), null).xSlots)
        assertEquals(3, ChartData("A", ChartRange.MONTH, "USD", bars(listOf(1.0, 2.0, 3.0)), null).xSlots)
        val near = ChartData("A", ChartRange.DAY, "USD", bars(listOf(100.0, 110.0)), 95.0).priceDomain
        assertTrue(near.start < 95)
        val far = ChartData("A", ChartRange.ALL, "USD", bars(listOf(100.0, 110.0)), 10.0).priceDomain
        assertTrue(far.start > 50)
    }
}

class ReportValidatorTests {
    private fun facts(day: Double) = MovementFacts(
        "ASML", "XLK", day, -9.01, -19.5, 1.5, 0.3, 1.12, 1.7, day - 1.7, 2.6, day / 2.6, 1.8, -28.38, MoveCharacter.STOCK_SPECIFIC,
    )

    @Test fun catchesWrongDirectionAndNumbers() {
        assertTrue(ReportValidator.contradicts("ASML Stocks Fall 7.36% as Investors Question the Next Spending Cycle", facts(5.02)))
        assertTrue(ReportValidator.contradicts("ASML jumps 12% on guidance", facts(5.02)))
        assertFalse(ReportValidator.contradicts("ASML rebounds 5% after a 19.5% monthly slide", facts(5.02)))
        assertFalse(ReportValidator.contradicts("ASML surges 5.0% on heavy volume", facts(5.02)))
        assertTrue(ReportValidator.contradicts("ASML rallies on chip demand", facts(-3.0)))
        assertFalse("'update' isn't 'up'", ReportValidator.contradicts("An update on the downgrade", facts(-3.0)))
    }
}

class RuleBasedDetectiveTests {
    @Test fun noise() {
        val r = RuleBasedDetective.investigate(makeCase(makeFacts(day = 0.3, bench = 0.2, character = MoveCharacter.NOISE)))
        assertEquals(Verdict.NOISE, r.verdict)
        assertEquals(ReportAuthor.RULE_BASED, r.author)
        assertEquals("No clear catalyst", r.causes.first().title)
        assertEquals(60, r.confidence)
        assertTrue(r.headline, r.headline.contains("rose 0.3%"))
    }

    @Test fun unexplainedWithoutNews() {
        val r = RuleBasedDetective.investigate(makeCase(makeFacts(day = -6.8, typical = 2.0, character = MoveCharacter.STOCK_SPECIFIC)))
        assertEquals(Verdict.UNEXPLAINED, r.verdict)
        assertEquals(30, r.confidence)
    }

    @Test fun justifiedWithSupportingNews() {
        val news = listOf(scored("ACME beats earnings", sentiment = 1.0, relevance = 0.9, catalysts = listOf("earnings")))
        val r = RuleBasedDetective.investigate(makeCase(makeFacts(day = 5.0, typical = 2.0, volume = 2.0, character = MoveCharacter.STOCK_SPECIFIC), news))
        assertEquals(Verdict.JUSTIFIED, r.verdict)
        assertEquals(listOf("Earnings", "Heavy trading"), r.causes.map { it.title })
        assertTrue(r.watchNext.first().contains("estimate revisions"))
        assertTrue(r.watchNext.size <= 3)
    }

    @Test fun overreactionOnLightVolume() {
        val news = listOf(scored("Acme surges", sentiment = 1.0, relevance = 0.9))
        val r = RuleBasedDetective.investigate(makeCase(makeFacts(day = 9.0, typical = 2.0, volume = 1.0, character = MoveCharacter.STOCK_SPECIFIC), news))
        assertEquals(Verdict.OVERREACTION, r.verdict)
    }

    @Test fun capitalizeLikeSwift() {
        assertEquals("Legal/Regulatory", RuleBasedDetective.capitalizeWords("legal/regulatory"))
        assertEquals("Earnings", RuleBasedDetective.capitalizeWords("earnings"))
    }

    @Test fun reportClampsConfidenceAndVerdictKeys() {
        fun report(c: Int) = InvestigationReport("A", "", "", emptyList(), Verdict.NOISE, "", emptyList(), c, ReportAuthor.RULE_BASED)
        assertEquals(100, report(150).confidence)
        assertEquals(0, report(-5).confidence)
        assertEquals(Verdict.OVERREACTION, Verdict.fromKey(" Overreaction "))
        assertEquals(Verdict.NOISE, Verdict.fromKey("just noise"))
        assertNull(Verdict.fromKey("bullish"))
        assertEquals("+1.2", fmt(1.234)); assertEquals("-1.25", fmt(-1.25, 2)); assertEquals("0.0", fmt(0.0))
    }

    @Test fun promptBrief() {
        val news = (1..8).map { i -> scored("Headline $i", catalysts = if (i == 1) listOf("earnings") else emptyList(), hoursAgo = when (i) { 1 -> 0.1; 2 -> 10.0; else -> 72.0 }) }
        val market = (1..6).map { scored("Market $it") }
        val file = makeCase(makeFacts(day = 4.0, character = MoveCharacter.STOCK_SPECIFIC), news, market)
        val short = file.promptBrief()
        assertTrue(short.contains("STOCK: TEST (Test Corp)"))
        assertTrue(short.contains("Headline 5")); assertFalse(short.contains("Headline 6"))
        assertTrue(short.contains("Market 3")); assertFalse(short.contains("Market 4"))
        assertTrue(short.contains("(just now) [earnings]"))
        assertTrue(short.contains("(10h ago)"))
        assertTrue(short.contains("(3d ago)"))
        assertTrue(short.contains("STATISTICAL READ: Stock-specific"))
        val empty = makeCase(makeFacts(), name = null).promptBrief()
        assertTrue(empty.contains("- none found")); assertFalse(empty.contains("MARKET HEADLINES"))
        assertTrue(empty.contains("STOCK: TEST, sector"))
    }
}

class PortfolioImporterTests {
    @Test fun brokerTable() {
        val text = """
            HOLDINGS
            SYM        QTY    PRICE($)    DAY%  DAY P&L(₪)
            VOO        238      675.99  +0.30%       1,534
            TSM        160      375.44  +6.33%      11,401
            IVV         66      738.68  +0.34%         520
            LLY         34     1354.00  -0.79%      -1,164
            NVDA       210      186.64  +2.78%       3,376
            AMZN       150      259.30  -0.35%        -435
            ASML        25     1424.75  +5.02%       5,430
            MSFT        64      530.61  -0.99%      -1,086
            GOOGL       28      284.78  +0.78%         197
            SPY          9      735.29  +0.31%          66
        """.trimIndent()
        val r = PortfolioImporter.parse(text)
        assertEquals(listOf("VOO", "TSM", "IVV", "LLY", "NVDA", "AMZN", "ASML", "MSFT", "GOOGL", "SPY"), r.rows.map { it.symbol })
        assertEquals(listOf(238.0, 160.0, 66.0, 34.0, 210.0, 150.0, 25.0, 64.0, 28.0, 9.0), r.rows.map { it.shares })
        assertEquals(1354.0, r.rows[3].price!!, 0.0)
        assertTrue(r.skipped.isEmpty())
    }

    @Test fun otherFormats() {
        val r = PortfolioImporter.parse("Ticker,Shares,Cost\nAAPL,10,150.5\nbrk.b\t3\n\$TSLA 1,200\nAAPL 5\nTotal 1000")
        assertEquals(listOf("AAPL", "BRK.B", "TSLA"), r.rows.map { it.symbol })
        assertEquals(listOf(15.0, 3.0, 1200.0), r.rows.map { it.shares })
    }

    @Test fun pasteFromCrashReport() {
        val text = "Add import portfolio that will populate the stocks on paste of “\nHOLDINGS\nSYM        QTY    PRICE($)    DAY%  DAY P&L(₪)\nVOO        238      675.99  +0.30%       1,534\nTSM        160      375.44  +6.33%      11,401\nIVV         66      738.68  +0.34%         520\nLLY         34     1354.00  -0.79%      -1,164\nNVDA       210      186.64  +2.78%       3,376\nAMZN       150      259.30  -0.35%        -435\nASML        25     1424.75  +5.02%       5,430\nMSFT        64      530.61  -0.99%      -1,086\nGOOGL       28      284.78  +0.78%         197\nSPY          9      735.29  +0.31%          66”"
        val r = PortfolioImporter.parse(text)
        assertEquals(10, r.rows.size)
        assertEquals(9.0, r.rows.last().shares, 0.0)
    }

    @Test fun helpers() {
        for (t in listOf("AAPL", "BRK.B", "BRK-B", "A", "GOOGL", "TSM")) assertTrue(t, PortfolioImporter.isTicker(t))
        for (t in listOf("SYM", "QTY", "HOLDINGS", "TOTAL", "aapl", "1ABC", "TOOLONGX", "", "AB.CDE")) assertFalse(t, PortfolioImporter.isTicker(t))
        assertEquals(1534.0, PortfolioImporter.number("1,534")!!, 0.0)
        assertEquals(-1164.0, PortfolioImporter.number("-1,164")!!, 0.0)
        assertEquals(1234567.5, PortfolioImporter.number("1,234,567.5")!!, 0.0)
        assertEquals(12.5, PortfolioImporter.number("12,5")!!, 0.0)
        assertEquals(7.0, PortfolioImporter.number("₪7")!!, 0.0)
        assertNull(PortfolioImporter.number("+6.33%"))
        assertNull(PortfolioImporter.number("abc"))
        assertNull(PortfolioImporter.number("NaN"))
        assertEquals(listOf("AAPL", "10", "12", "x"), PortfolioImporter.tokens("AAPL\t10;12|x"))
        assertEquals(listOf("MSFT", "5"), PortfolioImporter.tokens("\"MSFT\" '5'"))
        assertEquals(emptyList<String>(), PortfolioImporter.tokens("   "))
    }

    @Test fun edgeCases() {
        val r = PortfolioImporter.parse("NVDA 2 100\nnvda 3\nAAPL 0\nMSFT abc")
        assertEquals(listOf("NVDA"), r.rows.map { it.symbol })
        assertEquals(5.0, r.rows[0].shares, 0.0)
        assertEquals(100.0, r.rows[0].price!!, 0.0)
        assertEquals(listOf("AAPL 0", "MSFT abc"), r.skipped)
        val headers = PortfolioImporter.parse("SYMBOL SHARES\nAAPL\nMSFT QTY")
        assertTrue(headers.rows.isEmpty()); assertTrue(headers.skipped.isEmpty())
    }
}

class SectorTests {
    @Test fun guess() {
        assertEquals(Sector.TECHNOLOGY, Sector.guess("aapl"))
        assertEquals(Sector.FINANCIALS, Sector.guess("JPM"))
        assertEquals(Sector.BROAD_MARKET, Sector.guess("VOO"))
        assertEquals(12, Sector.entries.map { it.etf }.toSet().size)
        assertEquals(Sector.ENERGY, Sector.fromName("ENERGY"))
        assertEquals(Sector.BROAD_MARKET, Sector.fromName("nope"))
    }

    @Test fun stableHashMatchesIOS() {
        // djb2 & 0x7fffffff, the same function the Swift app uses for demo seeds.
        var h = 5381L
        for (b in "AAPL".toByteArray()) h = (h * 33 + b) and 0xffffffffffffL
        assertEquals((h and 0x7fffffff).toInt(), stableHash("AAPL"))
        assertTrue(stableHash("AAPL") != stableHash("MSFT"))
        assertTrue(abs(stableHash("anything")) >= 0)
    }
}
