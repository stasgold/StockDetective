package com.stocksdetective.app.data

import android.content.Context
import com.stocksdetective.kit.HoldingInput
import com.stocksdetective.kit.Sector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class Holding(
    val symbol: String,
    val name: String,
    val shares: Double,
    val costBasis: Double? = null,
    val sectorName: String = Sector.BROAD_MARKET.name,
    val addedAt: Long = System.currentTimeMillis(),
) {
    val sector: Sector get() = Sector.fromName(sectorName)
    val input: HoldingInput get() = HoldingInput(symbol, sector, shares, costBasis)

    companion object {
        fun create(symbol: String, name: String, shares: Double, costBasis: Double?, sector: Sector) =
            Holding(symbol.uppercase(), name, shares, costBasis, sector.name)
    }
}

object DemoPortfolio {
    val holdings = listOf(
        Holding.create("AAPL", "Apple Inc.", 40.0, 172.0, Sector.guess("AAPL")),
        Holding.create("NVDA", "NVIDIA Corporation", 25.0, 98.0, Sector.guess("NVDA")),
        Holding.create("MSFT", "Microsoft Corporation", 15.0, 390.0, Sector.guess("MSFT")),
        Holding.create("TSLA", "Tesla, Inc.", 12.0, 240.0, Sector.guess("TSLA")),
        Holding.create("JPM", "JPMorgan Chase & Co.", 20.0, 185.0, Sector.guess("JPM")),
    )
}

/** Tiny key-value storage, so persistence can be swapped for an in-memory map in tests. */
interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String?)
    fun getBoolean(key: String, default: Boolean = false): Boolean
    fun putBoolean(key: String, value: Boolean)
    fun getFloat(key: String, default: Float): Float
    fun putFloat(key: String, value: Float)
}

class SharedPrefsStore(context: Context, name: String = "stocks_detective") : KeyValueStore {
    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    override fun getString(key: String) = prefs.getString(key, null)
    override fun putString(key: String, value: String?) { prefs.edit().putString(key, value).apply() }
    override fun getBoolean(key: String, default: Boolean) = prefs.getBoolean(key, default)
    override fun putBoolean(key: String, value: Boolean) { prefs.edit().putBoolean(key, value).apply() }
    override fun getFloat(key: String, default: Float) = prefs.getFloat(key, default)
    override fun putFloat(key: String, value: Float) { prefs.edit().putFloat(key, value).apply() }
}

class MemoryStore : KeyValueStore {
    private val map = mutableMapOf<String, Any?>()
    override fun getString(key: String) = map[key] as? String
    override fun putString(key: String, value: String?) { map[key] = value }
    override fun getBoolean(key: String, default: Boolean) = map[key] as? Boolean ?: default
    override fun putBoolean(key: String, value: Boolean) { map[key] = value }
    override fun getFloat(key: String, default: Float) = map[key] as? Float ?: default
    override fun putFloat(key: String, value: Float) { map[key] = value }
}

/** The portfolio, persisted as JSON. Observed by every screen. */
class HoldingsStore(private val store: KeyValueStore) {
    private val serializer = ListSerializer(Holding.serializer())
    private val json = Json { ignoreUnknownKeys = true }
    private val _holdings = MutableStateFlow(load())

    /** Sorted by when they were added, like the iOS `@Query(sort: \.addedAt)`. */
    val holdings: StateFlow<List<Holding>> = _holdings.asStateFlow()
    val current: List<Holding> get() = _holdings.value

    private fun load(): List<Holding> =
        store.getString(KEY)?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() } ?: emptyList()

    /** Applies a change atomically and saves it. */
    @Synchronized
    fun update(change: (List<Holding>) -> List<Holding>) {
        val next = change(_holdings.value).distinctBy { it.symbol }.sortedBy { it.addedAt }
        _holdings.value = next
        store.putString(KEY, json.encodeToString(serializer, next))
    }

    companion object {
        const val KEY = "holdings"
    }
}

/** User preferences (the iOS app's `@AppStorage` values). */
class Settings(private val store: KeyValueStore) {
    var demoMode: Boolean
        get() = store.getBoolean(DEMO)
        set(v) = store.putBoolean(DEMO, v)
    var alertsEnabled: Boolean
        get() = store.getBoolean(ALERTS)
        set(v) = store.putBoolean(ALERTS, v)
    /** Alert when a move is this many times a normal day (z-score threshold). */
    var alertSensitivity: Double
        get() = store.getFloat(SENSITIVITY, 2f).toDouble()
        set(v) = store.putFloat(SENSITIVITY, v.toFloat())

    /** One alert per symbol per day. Returns false if this symbol was already alerted today. */
    fun markAlerted(key: String): Boolean {
        if (store.getBoolean(key)) return false
        store.putBoolean(key, true)
        return true
    }

    companion object {
        const val DEMO = "demoMode"
        const val ALERTS = "alertsEnabled"
        const val SENSITIVITY = "alertSensitivity"
    }
}
