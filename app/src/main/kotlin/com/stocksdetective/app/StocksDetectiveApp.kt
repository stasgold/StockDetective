package com.stocksdetective.app

import android.app.Application
import com.stocksdetective.app.ai.DetectiveAI
import com.stocksdetective.app.background.BackgroundInvestigator
import com.stocksdetective.app.data.HoldingsStore
import com.stocksdetective.app.data.Settings
import com.stocksdetective.app.data.SharedPrefsStore
import kotlinx.coroutines.MainScope

/** Owns the app-wide objects (the iOS `@main` app struct + `SharedStore`). */
class StocksDetectiveApp : Application() {
    lateinit var settings: Settings
        private set
    lateinit var holdings: HoldingsStore
        private set
    lateinit var model: AppModel
        private set

    override fun onCreate() {
        super.onCreate()
        val store = SharedPrefsStore(this)
        settings = Settings(store)
        holdings = HoldingsStore(store)
        model = AppModel(settings, DetectiveAI(), MainScope())
        BackgroundInvestigator.createChannel(this)
        if (settings.alertsEnabled) BackgroundInvestigator.schedule(this)
    }
}
