package com.stocksdetective.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.stocksdetective.app.ai.DetectiveAI
import com.stocksdetective.app.ai.PortfolioBriefing
import com.stocksdetective.app.data.Settings
import com.stocksdetective.kit.CaseBuilder
import com.stocksdetective.kit.CaseFile
import com.stocksdetective.kit.DemoMarketData
import com.stocksdetective.kit.HoldingInput
import com.stocksdetective.kit.InvestigationReport
import com.stocksdetective.kit.MarketDataProvider
import com.stocksdetective.kit.PortfolioSnapshot
import com.stocksdetective.kit.RuleBasedDetective
import com.stocksdetective.kit.YahooMarketData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import java.time.Duration
import java.time.Instant
import kotlin.math.abs

/**
 * App-wide state shared by every screen (the iOS `@Observable AppModel`). Fields are Compose
 * snapshot state, so screens recompose when they change. Call from the main thread.
 */
class AppModel(
    private val settings: Settings,
    val ai: DetectiveAI,
    private val scope: CoroutineScope,
    /** Builds the data provider for the current mode. Tests can swap this out. */
    private val makeProvider: (demo: Boolean) -> MarketDataProvider = { if (it) DemoMarketData() else YahooMarketData() },
) {
    val cases = mutableStateMapOf<String, CaseFile>()
    val reports = mutableStateMapOf<String, InvestigationReport>()
    val failures = mutableStateMapOf<String, String>()
    val investigating = mutableStateMapOf<String, Boolean>()
    var briefing by mutableStateOf<PortfolioBriefing?>(null)
    var isBriefing by mutableStateOf(false)
        private set
    var isRefreshing by mutableStateOf(false)
        private set
    var lastRefresh by mutableStateOf<Instant?>(null)
        private set

    /**
     * Bumped whenever cached data is thrown away (data source switched, portfolio reset),
     * so screens know to fetch again and late results from the old source are ignored.
     */
    var generation by mutableIntStateOf(0)
        private set
    private var refreshingGeneration: Int? = null
    private var queuedRefresh: List<HoldingInput>? = null

    var demoMode by mutableStateOf(settings.demoMode)
        private set

    var provider: MarketDataProvider = makeProvider(demoMode)
        private set
    private var builder = CaseBuilder(provider)

    fun useDemoData(on: Boolean) {
        if (on == demoMode) return
        demoMode = on
        settings.demoMode = on
        provider = makeProvider(on)
        builder = CaseBuilder(provider)
        clearCaches()
    }

    fun clearCaches() {
        generation++
        cases.clear()
        reports.clear()
        failures.clear()
        briefing = null
    }

    fun isInvestigating(symbol: String) = investigating[symbol] == true

    fun snapshot(holdings: List<HoldingInput>) = PortfolioSnapshot(holdings.mapNotNull { cases[it.symbol] }, holdings)

    /**
     * Gather fresh evidence for every holding, write instant rule-based reports,
     * then let on-device AI re-investigate the most suspicious moves.
     */
    suspend fun refresh(holdings: List<HoldingInput>) {
        if (holdings.isEmpty()) return
        val gen = generation
        if (refreshingGeneration == gen) {
            // Already fetching from this source; run again afterwards with the latest list
            // so holdings added mid-refresh (e.g. by an import) aren't skipped.
            queuedRefresh = holdings
            return
        }
        refreshingGeneration = gen
        isRefreshing = true
        try {
            // One independent job per holding, in the app scope so they finish even if the
            // screen that asked goes away; collected in order.
            val builder = builder
            val jobs = holdings.map { h -> h.symbol to scope.async(Dispatchers.IO) { runCatching { builder.buildCase(h) } } }
            for ((symbol, job) in jobs) {
                val outcome = job.await()
                if (gen != generation) return // data source changed meanwhile
                outcome.onSuccess { file ->
                    cases[symbol] = file
                    failures.remove(symbol)
                    val existing = reports[symbol]
                    if (existing?.author?.isAI != true || Duration.between(existing.createdAt, Instant.now()).seconds > 1800) {
                        reports[symbol] = RuleBasedDetective.investigate(file)
                    }
                }.onFailure { e ->
                    failures[symbol] = e.message ?: "Couldn't load $symbol."
                }
            }
            lastRefresh = Instant.now()

            // On-device model runs one request at a time; investigate the biggest surprises first.
            val suspects = holdings.mapNotNull { cases[it.symbol] }
                .filter { it.facts.isSignificant }
                .sortedByDescending { abs(it.facts.zScore) }
                .take(3)
            for (file in suspects) {
                if (reports[file.symbol]?.author?.isAI == true) continue
                if (gen != generation) return
                investigate(file.symbol)
            }
        } finally {
            if (refreshingGeneration == gen) {
                refreshingGeneration = null
                isRefreshing = false
            }
        }

        // Holdings added while we were busy (e.g. by an import): fetch the ones still missing.
        val queued = queuedRefresh
        if (queued != null && gen == generation) {
            queuedRefresh = null
            if (queued.any { cases[it.symbol] == null && failures[it.symbol] == null }) refresh(queued)
        }
    }

    suspend fun investigate(symbol: String) {
        val file = cases[symbol] ?: return
        if (isInvestigating(symbol)) return
        investigating[symbol] = true
        try {
            val gen = generation
            val report = ai.investigate(file)
            if (gen == generation) reports[symbol] = report
        } finally {
            investigating.remove(symbol)
        }
    }

    suspend fun writeBriefing(holdings: List<HoldingInput>) {
        if (isBriefing) return
        if (cases.isEmpty()) refresh(holdings)
        isBriefing = true
        try {
            val files = holdings.mapNotNull { cases[it.symbol] }
            briefing = ai.brief(snapshot(holdings), files, reports.toMap())
        } finally {
            isBriefing = false
        }
    }

    /** Drop AI write-ups (e.g. after the model finished downloading) so cases are re-investigated; keep the data. */
    fun clearReports() {
        for ((symbol, file) in cases) reports[symbol] = RuleBasedDetective.investigate(file)
        briefing = null
    }

    fun remove(symbol: String) {
        cases.remove(symbol)
        reports.remove(symbol)
        failures.remove(symbol)
    }
}
