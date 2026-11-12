package com.stocksdetective.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.github.takahirom.roborazzi.captureRoboImage
import com.stocksdetective.app.ai.DetectiveAI
import com.stocksdetective.app.data.HoldingsEditor
import com.stocksdetective.app.data.HoldingsStore
import com.stocksdetective.app.data.MemoryStore
import com.stocksdetective.app.data.Settings
import com.stocksdetective.app.ui.BriefingScreen
import com.stocksdetective.app.ui.CaseDetailScreen
import com.stocksdetective.app.ui.ImportScreen
import com.stocksdetective.app.ui.InterrogationScreen
import com.stocksdetective.app.ui.PortfolioScreen
import com.stocksdetective.app.ui.SettingsScreen
import com.stocksdetective.app.ui.StocksDetectiveTheme
import com.stocksdetective.kit.DemoMarketData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders every screen with the demo portfolio and saves PNGs to docs/screenshots.
 * Opt-in (slow): ./gradlew :app:testDebugUnitTest -Pscreenshots --tests '*ScreenshotTests*'
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w400dp-h860dp-xxhdpi")
class ScreenshotTests {
    @get:Rule val compose = createComposeRule()

    private lateinit var deps: Deps
    private val dir get() = File(System.getProperty("screenshotDir") ?: "build/screenshots")

    private val aiReply = """{"headline":"Nvidia jumps as cloud giants lift AI spending","whatHappened":"Nvidia rose 12.6% while its sector benchmark XLK slipped 0.9%. Almost the whole move is specific to Nvidia, on volume close to normal.",
        "causes":[{"title":"Higher AI capex guidance","detail":"Hyperscalers raised their AI spending plans, which flows straight into Nvidia's data-center sales.","evidence":"Nvidia jumps as hyperscalers raise AI capex guidance"},
        {"title":"Supplier strength","detail":"TSMC's record revenue confirms chip demand is still accelerating.","evidence":"Nvidia supplier TSMC reports record quarterly revenue"}],
        "verdict":"overreaction","verdictReasoning":"The news is clearly positive, but a move almost 5x a normal day on ordinary volume looks stretched. Some of this may fade.",
        "watchNext":["Whether volume confirms the move tomorrow","Analyst estimate revisions after the capex news","How XLK trades versus NVDA next session"],"confidence":72}"""

    @Before fun setUp() {
        assumeTrue("screenshots are opt-in", System.getProperty("screenshots") == "true")
        val kv = MemoryStore()
        val settings = Settings(kv)
        val fake = FakeModel { prompt ->
            when {
                prompt.contains("QUESTION:") -> "A move this size usually needs a second catalyst to hold. If volume fades tomorrow and peers like AMD stay flat, I'd move my verdict toward overreaction being confirmed; strong follow-through would make it justified."
                prompt.contains("STOCK: NVDA") -> aiReply
                else -> "not json"
            }
        }
        val ai = DetectiveAI(fake)
        runBlocking { ai.refreshStatus() }
        val model = AppModel(settings, ai, CoroutineScope(SupervisorJob() + Dispatchers.Default)) { DemoMarketData() }
        val store = HoldingsStore(kv)
        HoldingsEditor.seedDemo(store, model)
        runBlocking { model.refresh(store.current.map { it.input }) }
        deps = Deps(model, store, settings)
    }

    private fun shot(name: String, content: @Composable () -> Unit) {
        compose.setContent { StocksDetectiveTheme(content) }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(File(dir, "$name.png").path)
    }

    @Test fun portfolio() = shot("portfolio") { PortfolioScreen(deps, {}, {}, {}) }

    @Config(qualifiers = "w400dp-h2300dp-xxhdpi")
    @Test fun caseDetail() = shot("case-nvda") { CaseDetailScreen("NVDA", deps.model, {}, {}) }

    @Config(qualifiers = "w400dp-h1200dp-xxhdpi")
    @Test fun caseDetailNoNews() = shot("case-tsla") { CaseDetailScreen("TSLA", deps.model, {}, {}) }

    @Test fun briefing() = shot("briefing") { BriefingScreen(deps) {} }

    @Test fun settings() = shot("settings") { SettingsScreen(deps) }

    @Test fun import() {
        compose.setContent { StocksDetectiveTheme { ImportScreen(deps) {} } }
        compose.onNodeWithText("SYM   QTY   PRICE", substring = true).performTextInput(
            "SYM   QTY   PRICE(\$)\nVOO   238   675.99\nTSM   160   375.44\nNVDA  210   186.64\nAMZN  150   259.30\nAAPL  oops"
        )
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(File(dir, "import.png").path)
    }

    @Test fun interrogation() {
        compose.setContent { StocksDetectiveTheme { InterrogationScreen("NVDA", deps) {} } }
        compose.onNodeWithText("What would change your verdict?").performClick()
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithText("second catalyst", substring = true).assertExists() }.isSuccess }
        compose.onRoot().captureRoboImage(File(dir, "interrogation.png").path)
    }

    @Config(qualifiers = "w400dp-h860dp-night-xxhdpi")
    @Test fun portfolioDark() = shot("portfolio-dark") { PortfolioScreen(deps, {}, {}, {}) }

    @Test fun empty() {
        HoldingsEditor.deleteAll(deps.holdings, deps.model)
        shot("empty") { PortfolioScreen(deps, {}, {}, {}) }
    }
}
