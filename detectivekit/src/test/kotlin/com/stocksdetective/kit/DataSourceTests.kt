package com.stocksdetective.kit

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class DemoMarketDataTests {
    private val demo = DemoMarketData()

    @Test fun prng() {
        val a = DemoMarketData.SeededRandom(7uL)
        val b = DemoMarketData.SeededRandom(7uL)
        assertEquals(a.next(), b.next(), 0.0)
        repeat(100) { val x = a.next(); assertTrue(x >= 0 && x < 1) }
        assertTrue(a.normal().isFinite())
    }

    @Test fun storiesAndBenchmarks() {
        assertEquals("Apple Inc.", DemoMarketData.story("aapl").name)
        assertEquals("ZZZ", DemoMarketData.story("ZZZ").name)
        assertEquals(-0.6, DemoMarketData.benchmarkToday("SPY")!!, 0.0)
        assertNull(DemoMarketData.benchmarkToday("AAPL"))
    }

    @Test fun priceSeriesIsDeterministicAndScripted() = runBlocking {
        val (a, name) = demo.priceSeries("AAPL")
        val (b, _) = demo.priceSeries("aapl")
        assertEquals(a.lastPrice, b.lastPrice, 1e-9)
        assertEquals(-3.4, a.dayChangePercent, 1e-9)
        assertEquals(125, a.points.size)
        assertEquals("Apple Inc.", name)
        assertEquals(-0.6, demo.priceSeries("SPY").first.dayChangePercent, 1e-9)
    }

    @Test fun newsMarketNewsSearch() = runBlocking {
        assertEquals(3, demo.news("AAPL").size)
        assertTrue("TSLA's demo story has no news on purpose", demo.news("TSLA").isEmpty())
        assertEquals(2, demo.marketNews().size)
        assertEquals(listOf("AAPL"), demo.search("ap").map { it.symbol })
        assertEquals(listOf("MSFT", "NVDA"), demo.search("corp").map { it.symbol })
    }

    @Test fun chartForEveryRange() = runBlocking {
        for (r in ChartRange.entries) {
            val c = demo.chart("AAPL", r)
            assertTrue("$r", c.bars.size > 1)
            assertEquals(r, c.range)
            assertEquals(r == ChartRange.DAY, c.previousClose != null)
            assertNotNull(c.changePercent)
            assertTrue("$r bars in time order", c.bars.zipWithNext().all { (x, y) -> x.date < y.date })
        }
        assertEquals("1D change matches the case's day move", -3.4, demo.chart("AAPL", ChartRange.DAY).changePercent!!, 0.01)
    }
}

class CaseBuilderTests {
    @Test fun buildsCompleteCase() = runBlocking {
        val file = CaseBuilder(DemoMarketData()).buildCase(HoldingInput("aapl", Sector.TECHNOLOGY, 1.0))
        assertEquals("AAPL", file.symbol)
        assertEquals("Apple Inc.", file.companyName)
        assertEquals("XLK", file.facts.benchmark)
        assertEquals(3, file.news.size)
        assertEquals(2, file.marketNews.size)
        assertNotNull(file.intraday)
        assertEquals("row sparkline matches the % pill", file.facts.dayChange, file.intraday!!.changePercent!!, 0.01)
    }

    @Test fun stockFailureThrowsAndNothingElseIsRequested() = runBlocking {
        val p = CountingProvider(setOf("AAPL"))
        val error = runCatching { CaseBuilder(p).buildCase(HoldingInput("AAPL", Sector.TECHNOLOGY, 1.0)) }.exceptionOrNull()
        assertTrue(error is MarketDataException.NoData)
        assertEquals(0, p.count("news:AAPL") + p.count("series:XLK"))
    }

    @Test fun optionalPartsMayFail() = runBlocking {
        val file = CaseBuilder(CountingProvider(setOf("XLK", "SPY", "news", "marketNews", "chart")))
            .buildCase(HoldingInput("AAPL", Sector.TECHNOLOGY, 1.0))
        assertEquals("falls back to the stock itself", "AAPL", file.facts.benchmark)
        assertTrue(file.news.isEmpty())
        assertTrue(file.marketNews.isEmpty())
        assertNull(file.intraday)
    }

    @Test fun cachesSeriesAndMarketNews() = runBlocking {
        val p = CountingProvider()
        val builder = CaseBuilder(p)
        builder.buildCase(HoldingInput("AAPL", Sector.TECHNOLOGY, 1.0))
        builder.buildCase(HoldingInput("MSFT", Sector.TECHNOLOGY, 1.0))
        builder.buildCase(HoldingInput("AAPL", Sector.TECHNOLOGY, 1.0))
        assertEquals(1, p.count("series:SPY"))
        assertEquals(1, p.count("series:XLK"))
        assertEquals(1, p.count("series:AAPL"))
        assertEquals(1, p.count("marketNews"))
    }

    @Test fun demoPortfolioProducesEveryKindOfCase() = runBlocking {
        val builder = CaseBuilder(DemoMarketData())
        val holdings = listOf("AAPL", "NVDA", "MSFT", "TSLA", "JPM").map { HoldingInput(it, Sector.guess(it), 10.0) }
        val cases = holdings.map { builder.buildCase(it) }
        val reports = cases.map(RuleBasedDetective::investigate)
        for (r in reports) {
            assertFalse(r.whatHappened.isEmpty())
            assertFalse(r.watchNext.isEmpty())
        }
        assertTrue("several verdict types show up", reports.map { it.verdict }.toSet().size >= 3)
        val brief = cases[0].promptBrief()
        assertTrue(brief.contains("AAPL"))
        assertTrue("keep model prompts small", brief.length < 2000)
        val snap = PortfolioSnapshot(cases, holdings)
        assertEquals(5, snap.lines.size)
        assertEquals(5, snap.topContributors.size)
    }
}

class PortfolioSnapshotTests {
    @Test fun totalsContributorsSuspects() {
        fun case(symbol: String, prev: Double, last: Double, z: Double, character: MoveCharacter, headline: String? = null) = CaseFile(
            symbol, null, Sector.TECHNOLOGY,
            makeFacts(day = (last / prev - 1) * 100, market = 0.3, z = z, character = character).copy(symbol = symbol),
            headline?.let { listOf(scored(it)) } ?: emptyList(), emptyList(),
            makeSeries(symbol, listOf(prev), last = last, prev = prev),
        )
        val cases = listOf(
            case("UP", 100.0, 110.0, 3.0, MoveCharacter.STOCK_SPECIFIC, "UP news"),
            case("DN", 50.0, 49.0, -2.0, MoveCharacter.MARKET_DRIVEN),
            case("MKT", 10.0, 9.0, -0.5, MoveCharacter.NOISE),
        )
        val holdings = listOf(
            HoldingInput("UP", Sector.TECHNOLOGY, 10.0, 55.0),
            HoldingInput("DN", Sector.TECHNOLOGY, 100.0),
            HoldingInput("MKT", Sector.TECHNOLOGY, 5.0, 0.0),
        )
        val s = PortfolioSnapshot(cases, holdings)
        assertEquals(1100 + 4900 + 45.0, s.totalValue, 1e-9)
        assertEquals(100 - 100 - 5.0, s.dayPnL, 1e-9)
        assertEquals(-5.0 / (6045 + 5) * 100, s.dayChangePercent, 1e-9)
        assertEquals(listOf("DN", "UP"), s.topContributors.map { it.symbol }.take(2).sorted())
        assertEquals(listOf("UP"), s.suspects.map { it.symbol })
        assertEquals(100.0, s.lines.first { it.symbol == "UP" }.totalReturn!!, 1e-9)
        assertNull(s.lines.first { it.symbol == "MKT" }.totalReturn)
        val brief = s.promptBrief(cases, holdings = 2)
        assertTrue(brief.startsWith("PORTFOLIO today:"))
        assertEquals(2, brief.lines().count { it.startsWith("- ") })
        assertTrue(brief.contains("UP news"))
        assertTrue(brief.contains("S&P 500 today: +0.3%"))
        assertFalse(PortfolioSnapshot(emptyList(), emptyList()).promptBrief(emptyList()).contains("S&P"))
    }
}

class YahooMarketDataTests {
    @Test fun priceSeriesRequest() = runBlocking {
        val http = StubHttp(listOf("chart/BRK-B" to Fixtures.chartJSON(symbol = "BRK-B", closes = listOf(1.0, 2.0))))
        val (series, _) = YahooMarketData(http).priceSeries("BRK-B")
        assertEquals("BRK-B", series.symbol)
        assertTrue(http.requests[0].contains("range=6mo&interval=1d"))
    }

    @Test fun newsMergesFeedsLabelsSourcesAndSorts() = runBlocking {
        val yahoo = "<rss><channel><item><title>Yahoo story</title><pubDate>Thu, 05 Nov 2026 10:00:00 GMT</pubDate></item></channel></rss>"
        val google = "<rss><channel><item><title>Google story - CNBC</title><pubDate>Fri, 06 Nov 2026 10:00:00 GMT</pubDate></item></channel></rss>"
        val news = YahooMarketData(StubHttp(listOf("feeds.finance.yahoo.com" to yahoo, "news.google.com" to google))).news("AAPL")
        assertEquals(listOf("Google story", "Yahoo story"), news.map { it.title })
        assertEquals(listOf("CNBC", "Yahoo Finance"), news.map { it.source })
    }

    @Test fun newsSurvivesOneFeedFailing() = runBlocking {
        val google = "<rss><channel><item><title>Only Google - AP</title></item></channel></rss>"
        assertEquals(listOf("Only Google"), YahooMarketData(StubHttp(listOf("news.google.com" to google))).news("AAPL").map { it.title })
    }

    @Test fun searchAndChartURLs() = runBlocking {
        val http = StubHttp(listOf(
            "search" to """{"quotes":[{"symbol":"AAPL","quoteType":"EQUITY"}]}""",
            "chart/" to Fixtures.chartJSON(closes = listOf(1.0, 2.0)),
        ))
        val y = YahooMarketData(http)
        assertEquals(listOf("AAPL"), y.search("apple inc").map { it.symbol })
        assertTrue(http.requests.last().contains("q=apple+inc"))
        for (r in ChartRange.entries) {
            y.chart("AAPL", r)
            assertTrue(http.requests.last().contains("range=${r.yahooRange}&interval=${r.yahooInterval}"))
        }
    }
}

/** Real network. Run with ./gradlew :detectivekit:test -PliveTests */
class LiveDataTests {
    @Test fun liveCase() = runBlocking {
        assumeTrue(System.getProperty("liveTests") == "true")
        val file = CaseBuilder(YahooMarketData()).buildCase(HoldingInput("AAPL", Sector.TECHNOLOGY, 1.0))
        assertTrue(file.series.points.size > 20)
        println(file.promptBrief())
        println(RuleBasedDetective.investigate(file).headline)
    }
}
