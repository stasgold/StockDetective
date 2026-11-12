package com.stocksdetective.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Newspaper
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.stocksdetective.app.background.BackgroundInvestigator
import com.stocksdetective.app.data.HoldingsEditor
import com.stocksdetective.app.data.HoldingsStore
import com.stocksdetective.app.data.Settings
import com.stocksdetective.app.ui.AddHoldingScreen
import com.stocksdetective.app.ui.BriefingScreen
import com.stocksdetective.app.ui.CaseDetailScreen
import com.stocksdetective.app.ui.ImportScreen
import com.stocksdetective.app.ui.InterrogationScreen
import com.stocksdetective.app.ui.Palette
import com.stocksdetective.app.ui.PortfolioScreen
import com.stocksdetective.app.ui.SettingsScreen
import com.stocksdetective.app.ui.StocksDetectiveTheme
import kotlinx.coroutines.launch

/** What the screens need, passed down explicitly. */
data class Deps(val model: AppModel, val holdings: HoldingsStore, val settings: Settings)

private enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    PORTFOLIO("portfolio", "Portfolio", Icons.Filled.Work),
    BRIEFING("briefing", "Briefing", Icons.Filled.Newspaper),
    SETTINGS("settings", "Settings", Icons.Filled.Settings),
}

/**
 * Intent extras used for debugging and automated smoke tests (the iOS launch arguments):
 *   adb shell am start -n com.stasgold.stocksdetective/com.stocksdetective.app.MainActivity --ez seedDemoPortfolio true --es openCase NVDA
 *   --es openTab briefing|settings|import
 */
object LaunchOptions {
    const val SEED_DEMO = "seedDemoPortfolio"
    const val OPEN_CASE = BackgroundInvestigator.EXTRA_SYMBOL
    const val OPEN_TAB = "openTab"
}

class MainActivity : ComponentActivity() {
    /** Case or tab requested by the launching intent (notification tap, adb). */
    private val pending = mutableStateOf<Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as StocksDetectiveApp
        val deps = Deps(app.model, app.holdings, app.settings)
        if (savedInstanceState == null) pending.value = intent
        setContent {
            StocksDetectiveTheme {
                AppRoot(deps, pending.value) { pending.value = null }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pending.value = intent
    }

    override fun onResume() {
        super.onResume()
        // Pick up a Gemini Nano download that finished while we were away.
        val model = (application as StocksDetectiveApp).model
        lifecycleScope.launch { model.ai.prewarm() }
    }
}

@Composable
private fun AppRoot(deps: Deps, launch: Intent?, consumed: () -> Unit) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val showTabs = Tab.entries.any { it.route == route }

    LaunchedEffect(launch) {
        val intent = launch ?: return@LaunchedEffect
        if (intent.getBooleanExtra(LaunchOptions.SEED_DEMO, false)) HoldingsEditor.seedDemo(deps.holdings, deps.model)
        when (intent.getStringExtra(LaunchOptions.OPEN_TAB)) {
            "briefing" -> nav.selectTab(Tab.BRIEFING)
            "settings" -> nav.selectTab(Tab.SETTINGS)
            "import" -> nav.navigate("import")
        }
        intent.getStringExtra(LaunchOptions.OPEN_CASE)?.let { nav.navigate("case/${it.uppercase()}") }
        consumed()
    }

    Scaffold(
        bottomBar = {
            if (showTabs) {
                NavigationBar {
                    for (tab in Tab.entries) {
                        NavigationBarItem(
                            selected = route == tab.route,
                            onClick = { nav.selectTab(tab) },
                            icon = { Icon(tab.icon, null) },
                            label = { Text(tab.label) },
                            colors = NavigationBarItemDefaults.colors(selectedIconColor = Palette.accent, selectedTextColor = Palette.accent),
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = Tab.PORTFOLIO.route, modifier = Modifier.padding(padding).consumeWindowInsets(padding)) {
            val openCase: (String) -> Unit = { nav.navigate("case/$it") }
            composable(Tab.PORTFOLIO.route) {
                PortfolioScreen(deps, openCase, onAdd = { nav.navigate("add") }, onImport = { nav.navigate("import") })
            }
            composable(Tab.BRIEFING.route) { BriefingScreen(deps, openCase) }
            composable(Tab.SETTINGS.route) { SettingsScreen(deps) }
            composable("case/{symbol}", arguments = listOf(navArgument("symbol") { type = NavType.StringType })) {
                val symbol = it.arguments?.getString("symbol").orEmpty()
                CaseDetailScreen(symbol, deps.model, onBack = { nav.popBackStack() }, onInterrogate = { nav.navigate("interrogate/$symbol") })
            }
            composable("interrogate/{symbol}") {
                InterrogationScreen(it.arguments?.getString("symbol").orEmpty(), deps) { nav.popBackStack() }
            }
            composable("add") { AddHoldingScreen(deps) { nav.popBackStack() } }
            composable("import") { ImportScreen(deps) { nav.popBackStack() } }
        }
    }
}

private fun NavHostController.selectTab(tab: Tab) {
    navigate(tab.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
