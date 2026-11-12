package com.stocksdetective.app

import com.stocksdetective.app.ai.AIReport
import com.stocksdetective.app.ai.AiStatus
import com.stocksdetective.app.ai.DetectiveAI
import com.stocksdetective.app.ai.Interrogation
import com.stocksdetective.app.ai.OnDeviceModel
import com.stocksdetective.app.background.BackgroundInvestigator
import com.stocksdetective.app.data.HoldingsEditor
import com.stocksdetective.app.data.HoldingsStore
import com.stocksdetective.app.data.MemoryStore
import com.stocksdetective.app.data.Settings
import com.stocksdetective.app.ui.formatShares
import com.stocksdetective.app.ui.labelIndices
import com.stocksdetective.app.ui.niceTicks
import com.stocksdetective.kit.CaseBuilder
import com.stocksdetective.kit.DemoMarketData
import com.stocksdetective.kit.HoldingInput
import com.stocksdetective.kit.MoveCharacter
import com.stocksdetective.kit.MovementFacts
import com.stocksdetective.kit.PortfolioImporter
import com.stocksdetective.kit.ReportAuthor
import com.stocksdetective.kit.RuleBasedDetective
import com.stocksdetective.kit.Sector
import com.stocksdetective.kit.Verdict
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Scripted on-device model: replies with [reply], or is unavailable. */
class FakeModel(var available: Boolean = true, var reply: (String) -> String = { "" }) : OnDeviceModel {
    val prompts = mutableListOf<String>()
    override suspend fun status() = if (available) AiStatus.Available else AiStatus.Unavailable("off")
    override fun download(): Flow<AiStatus> = flowOf(AiStatus.Available)
    override suspend fun warmup() {}
    override suspend fun generate(instructions: String, prompt: String, temperature: Float, maxOutputTokens: Int): String {
        prompts += prompt
        return reply(prompt)
    }
}

private fun deps(model: FakeModel = FakeModel(available = false), demo: Boolean = true): Triple<AppModel, HoldingsStore, Settings> {
    val kv = MemoryStore()
    val settings = Settings(kv).also { it.demoMode = demo }
    val ai = DetectiveAI(model)
    runBlocking { ai.refreshStatus() }
    val app = AppModel(settings, ai, CoroutineScope(SupervisorJob() + Dispatchers.Default)) { DemoMarketData() }
    return Triple(app, HoldingsStore(kv), settings)
}

class AppModelTests {
    @Test fun refreshBuildsCasesAndRuleBasedReports() = runBlocking {
        val (model, store, _) = deps()
        HoldingsEditor.seedDemo(store, model)
        model.refresh(store.current.map { it.input })
        assertEquals(5, model.cases.size)
        assertEquals(5, model.reports.size)
        assertTrue(model.reports.values.all { it.author == ReportAuthor.RULE_BASED })
        assertFalse(model.isRefreshing)
        assertNotNull(model.lastRefresh)
        assertEquals(5, model.snapshot(store.current.map { it.input }).lines.size)
    }

    @Test fun aiReInvestigatesTheBiggestSurprises() = runBlocking {
        val fake = FakeModel { """```json
            {"headline":"Nvidia jumps on AI capex","whatHappened":"Nvidia rose 12.6% on hyperscaler spending.","causes":[{"title":"AI capex","detail":"Big buyers raised budgets.","evidence":""}],
             "verdict":"justified","verdictReasoning":"Strong news.","watchNext":["Capex follow-through"],"confidence":80}
            ```""" }
        val (model, store, _) = deps(fake)
        HoldingsEditor.seedDemo(store, model)
        model.refresh(store.current.map { it.input })
        val ai = model.reports.values.filter { it.author == ReportAuthor.ON_DEVICE_AI }
        assertEquals("top 3 significant moves only", 3, ai.size)
        assertEquals(3, fake.prompts.size)
        assertTrue(fake.prompts.first().contains("STOCK: NVDA"))
    }

    @Test fun switchingSourceDropsCaches() = runBlocking {
        val (model, store, settings) = deps()
        HoldingsEditor.seedDemo(store, model)
        model.refresh(store.current.map { it.input })
        val gen = model.generation
        model.useDemoData(false)
        assertTrue(model.cases.isEmpty())
        assertEquals(gen + 1, model.generation)
        assertFalse(settings.demoMode)
    }

    @Test fun briefingFallsBackToRules() = runBlocking {
        val (model, store, _) = deps()
        HoldingsEditor.seedDemo(store, model)
        model.writeBriefing(store.current.map { it.input })
        val b = model.briefing!!
        assertEquals(ReportAuthor.RULE_BASED, b.author)
        assertTrue(b.headline.startsWith("Portfolio "))
        assertEquals(3, b.keyFindings.size)
        assertEquals("no repeated to-dos", b.watchNext.distinct(), b.watchNext)
    }
}

class HoldingsEditorTests {
    @Test fun parseAmount() {
        assertEquals(12.5, HoldingsEditor.parseAmount(" 12,5 ")!!, 0.0)
        assertNull(HoldingsEditor.parseAmount("0"))
        assertNull(HoldingsEditor.parseAmount("-3"))
        assertNull(HoldingsEditor.parseAmount(""))
        assertNull(HoldingsEditor.parseAmount("abc"))
    }

    @Test fun upsertAddsThenUpdates() {
        val (_, store, _) = deps()
        HoldingsEditor.upsert(store, "aapl", "Apple", 5.0, null, Sector.TECHNOLOGY)
        HoldingsEditor.upsert(store, "AAPL", "Apple", 7.0, 150.0, Sector.BROAD_MARKET)
        assertEquals(1, store.current.size)
        assertEquals(7.0, store.current[0].shares, 0.0)
        assertEquals(Sector.BROAD_MARKET, store.current[0].sector)
    }

    @Test fun importAddsUpdatesReplacesAndLeavesDemo() {
        val (model, store, _) = deps()
        HoldingsEditor.seedDemo(store, model)
        assertTrue(model.demoMode)
        val rows = PortfolioImporter.parse("AAPL 3\nVOO 10").rows
        HoldingsEditor.importRows(rows, replace = true, store = store, model = model)
        assertEquals(listOf("AAPL", "VOO"), store.current.map { it.symbol })
        assertEquals(3.0, store.current[0].shares, 0.0)
        assertEquals("ticker until market data fills the name", "VOO", store.current[1].name)
        assertFalse("real holdings need live data", model.demoMode)

        HoldingsEditor.importRows(PortfolioImporter.parse("MSFT 1").rows, replace = false, store = store, model = model)
        assertEquals(listOf("AAPL", "VOO", "MSFT"), store.current.map { it.symbol })
    }

    @Test fun persistsAcrossReload() {
        val kv = MemoryStore()
        HoldingsEditor.upsert(HoldingsStore(kv), "TSLA", "Tesla", 2.0, null, Sector.CONSUMER_DISCRETIONARY)
        assertEquals(listOf("TSLA"), HoldingsStore(kv).current.map { it.symbol })
    }

    @Test fun seedOnlyIntoEmptyStoreAndDeleteAll() {
        val (model, store, _) = deps(demo = false)
        HoldingsEditor.upsert(store, "AAPL", "Apple", 1.0, null, Sector.TECHNOLOGY)
        HoldingsEditor.seedDemo(store, model)
        assertEquals(1, store.current.size)
        assertFalse(model.demoMode)
        HoldingsEditor.deleteAll(store, model)
        assertTrue(store.current.isEmpty())
    }

    @Test fun fillInNamesAndSortBySurprise() = runBlocking {
        val (model, store, _) = deps()
        HoldingsEditor.importRows(PortfolioImporter.parse("AAPL 1\nNVDA 1\nMSFT 1").rows, false, store, model)
        model.useDemoData(true)
        model.refresh(store.current.map { it.input })
        HoldingsEditor.fillInNames(store, model.cases)
        assertEquals("Apple Inc.", store.current.first { it.symbol == "AAPL" }.name)
        assertEquals("NVDA", HoldingsEditor.sortedBySurprise(store.current, model.cases).first().symbol)
    }
}

class DetectiveAITests {
    private val facts = MovementFacts("NVDA", "XLK", 12.6, 10.0, 15.0, -0.9, -0.6, 1.5, -1.35, 13.95, 2.6, 4.8, 1.1, -2.0, MoveCharacter.STOCK_SPECIFIC)

    @Test fun parsesFencedJSONAndRejectsJunk() {
        assertNotNull(DetectiveAI.parseReport("Sure! ```json\n{\"headline\":\"H\",\"whatHappened\":\"W\"}\n```"))
        assertNull(DetectiveAI.parseReport("no json here"))
        assertNull("needs headline and summary", DetectiveAI.parseReport("{\"headline\":\"\"}"))
        assertNotNull(DetectiveAI.parseBriefing("{\"headline\":\"H\",\"summary\":\"S\",\"extra\":1}"))
    }

    @Test fun makeReportReplacesContradictionsAndClamps() {
        val file = runBlocking { CaseBuilder(DemoMarketData()).buildCase(HoldingInput("NVDA", Sector.TECHNOLOGY, 1.0)) }
        val fallback = RuleBasedDetective.investigate(file)
        val ai = AIReport(
            headline = "Nvidia falls 7% on weak demand", whatHappened = "Shares rose 12.6% today.",
            causes = List(5) { com.stocksdetective.app.ai.AICause("C$it", "d") }, verdict = "Overreaction",
            verdictReasoning = "r", watchNext = listOf("a", "b", "c", "d"), confidence = 140,
        )
        val r = DetectiveAI.makeReport("NVDA", ai, facts, fallback)
        assertEquals("wrong direction is replaced", fallback.headline, r.headline)
        assertEquals("Shares rose 12.6% today.", r.whatHappened)
        assertEquals(3, r.causes.size)
        assertEquals(3, r.watchNext.size)
        assertEquals(Verdict.OVERREACTION, r.verdict)
        assertEquals(100, r.confidence)
        assertEquals(Verdict.JUSTIFIED.let { fallback.verdict }, DetectiveAI.makeReport("NVDA", ai.copy(verdict = "bullish"), facts, fallback).verdict)
    }

    @Test fun unavailableOrFailingModelFallsBack() = runBlocking {
        val file = CaseBuilder(DemoMarketData()).buildCase(HoldingInput("AAPL", Sector.TECHNOLOGY, 1.0))
        val off = DetectiveAI(FakeModel(available = false)).also { it.refreshStatus() }
        assertEquals(ReportAuthor.RULE_BASED, off.investigate(file).author)
        val broken = DetectiveAI(FakeModel { throw IllegalStateException("BACKGROUND_USE_BLOCKED") }).also { it.refreshStatus() }
        assertEquals(ReportAuthor.RULE_BASED, broken.investigate(file).author)
        val chatty = DetectiveAI(FakeModel { "I think it fell." }).also { it.refreshStatus() }
        assertEquals(ReportAuthor.RULE_BASED, chatty.investigate(file).author)
    }
}

class InterrogationTests {
    @Test fun findsTickersInQuestions() {
        assertEquals(listOf("AMD", "INTC"), Interrogation.tickersIn("How did AMD and INTC do? Is the CEO worried?", "NVDA", "XLK"))
        assertEquals(listOf("XLK", "SPY"), Interrogation.tickersIn("How did peers do today?", "NVDA", "XLK"))
        assertEquals(listOf("T"), Interrogation.tickersIn("compare with \$T", "NVDA", "XLK"))
        assertTrue(Interrogation.tickersIn("Why did NVDA move?", "NVDA", "XLK").isEmpty())
    }

    @Test fun answersWithToolResults() = runBlocking {
        val fake = FakeModel { "AMD rose too, so this was sector-wide." }
        val ai = DetectiveAI(fake).also { it.refreshStatus() }
        val file = CaseBuilder(DemoMarketData()).buildCase(HoldingInput("NVDA", Sector.TECHNOLOGY, 1.0))
        val chat = Interrogation(file, null, DemoMarketData(), ai)
        chat.ask("How did MSFT do?")
        assertEquals(2, chat.messages.size)
        assertTrue(fake.prompts.single().contains("TOOL RESULTS"))
        assertTrue(fake.prompts.single().contains("MSFT: last"))
        assertEquals("AMD rose too, so this was sector-wide.", chat.messages.last().text)
    }

    @Test fun explainsWhenAIIsOff() = runBlocking {
        val ai = DetectiveAI(FakeModel(available = false)).also { it.refreshStatus() }
        val file = CaseBuilder(DemoMarketData()).buildCase(HoldingInput("NVDA", Sector.TECHNOLOGY, 1.0))
        val chat = Interrogation(file, null, DemoMarketData(), ai)
        chat.ask("Why?")
        assertTrue(chat.messages.last().text.contains("Gemini Nano"))
    }
}

class BackgroundTests {
    private fun facts(z: Double, c: MoveCharacter) = MovementFacts("A", "XLK", z, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, z, 1.0, z, 1.0, 0.0, c)

    @Test fun alertsOnlyOnUnusualStockSpecificMoves() {
        assertTrue(BackgroundInvestigator.shouldAlert(facts(-3.0, MoveCharacter.STOCK_SPECIFIC), 2.0))
        assertFalse(BackgroundInvestigator.shouldAlert(facts(-3.0, MoveCharacter.MARKET_DRIVEN), 2.0))
        assertFalse(BackgroundInvestigator.shouldAlert(facts(1.0, MoveCharacter.STOCK_SPECIFIC), 2.0))
        assertEquals("alerted-AAPL-2026-11-12", BackgroundInvestigator.alertKey("AAPL", LocalDate.of(2026, 11, 12)))
    }

    @Test fun onePerSymbolPerDay() = runBlocking {
        val settings = Settings(MemoryStore())
        val builder = CaseBuilder(DemoMarketData())
        val holdings = listOf("AAPL", "NVDA", "MSFT", "TSLA", "JPM").map { HoldingInput(it, Sector.guess(it), 1.0) }
        val first = BackgroundInvestigator.casesToAlert(holdings, builder, 2.0, settings).map { it.symbol }
        assertTrue(first.isNotEmpty())
        assertFalse("MSFT's move is normal", "MSFT" in first)
        assertTrue("already alerted today", BackgroundInvestigator.casesToAlert(holdings, builder, 2.0, settings).isEmpty())
        val report = RuleBasedDetective.investigate(builder.buildCase(holdings[0]))
        assertTrue(BackgroundInvestigator.notificationTitle(report, builder.buildCase(holdings[0]).facts).startsWith("AAPL -3.4%"))
    }
}

class ChartHelperTests {
    @Test fun ticksAndLabels() {
        assertEquals(listOf(100.0, 105.0, 110.0, 115.0, 120.0), niceTicks(98.0, 121.0, 5))
        assertEquals(listOf(0, 1, 2), labelIndices(3))
        assertEquals(listOf(10, 31, 52, 73), labelIndices(84))
        assertEquals("1,200", formatShares(1200.0))
        assertEquals("2.5", formatShares(2.5))
    }
}
