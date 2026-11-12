package com.stocksdetective.app.data

import com.stocksdetective.app.AppModel
import com.stocksdetective.kit.CaseFile
import com.stocksdetective.kit.PortfolioImporter
import com.stocksdetective.kit.Sector
import kotlin.math.abs

/** Portfolio edits used by the screens, kept out of the UI so they can be unit tested. */
object HoldingsEditor {
    /** "12.5" or "12,5" → 12.5; empty, zero or negative → null. */
    fun parseAmount(s: String): Double? =
        s.trim().replace(",", ".").toDoubleOrNull()?.takeIf { it > 0 && it.isFinite() }

    /** Adds a holding, or updates it if the ticker is already in the portfolio. */
    fun upsert(store: HoldingsStore, symbol: String, name: String, shares: Double, costBasis: Double?, sector: Sector) {
        val key = symbol.uppercase()
        store.update { list ->
            if (list.any { it.symbol == key }) {
                list.map { if (it.symbol == key) it.copy(shares = shares, costBasis = costBasis, sectorName = sector.name) else it }
            } else {
                list + Holding.create(key, name, shares, costBasis, sector)
            }
        }
    }

    /** Applies pasted import rows. With [replace], holdings not in the paste are removed. */
    fun importRows(rows: List<PortfolioImporter.Row>, replace: Boolean, store: HoldingsStore, model: AppModel) {
        val keep = rows.map { it.symbol }.toSet()
        val removed = if (replace) store.current.map { it.symbol }.filter { it !in keep } else emptyList()
        store.update { list ->
            val bySymbol = list.associateBy { it.symbol }
            var t = System.currentTimeMillis()
            val updated = list.filter { !replace || it.symbol in keep }.map { h ->
                rows.firstOrNull { it.symbol == h.symbol }?.let { h.copy(shares = it.shares) } ?: h
            }
            // Name is filled in from market data on the first refresh.
            updated + rows.filter { it.symbol !in bySymbol }.map {
                Holding.create(it.symbol, it.symbol, it.shares, null, Sector.guess(it.symbol)).copy(addedAt = t++)
            }
        }
        removed.forEach(model::remove)
        if (model.demoMode) model.useDemoData(false) // real holdings need live data
    }

    /** Loads the demo portfolio into an empty store and switches to demo data. */
    fun seedDemo(store: HoldingsStore, model: AppModel) {
        if (store.current.isNotEmpty()) return
        model.useDemoData(true)
        var t = System.currentTimeMillis()
        store.update { DemoPortfolio.holdings.map { it.copy(addedAt = t++) } }
    }

    /** Imported holdings start with just a ticker; use the company name once we have it. */
    fun fillInNames(store: HoldingsStore, cases: Map<String, CaseFile>) {
        val needs = store.current.any { h -> h.name == h.symbol && !cases[h.symbol]?.companyName.isNullOrEmpty() }
        if (!needs) return
        store.update { list ->
            list.map { h ->
                val name = cases[h.symbol]?.companyName
                if (h.name == h.symbol && !name.isNullOrEmpty()) h.copy(name = name) else h
            }
        }
    }

    /** Most surprising moves first: that's where the story is. */
    fun sortedBySurprise(holdings: List<Holding>, cases: Map<String, CaseFile>): List<Holding> =
        holdings.sortedByDescending { abs(cases[it.symbol]?.facts?.zScore ?: 0.0) }

    fun delete(symbols: Collection<String>, store: HoldingsStore, model: AppModel) {
        val set = symbols.toSet()
        store.update { list -> list.filter { it.symbol !in set } }
        set.forEach(model::remove)
    }

    fun deleteAll(store: HoldingsStore, model: AppModel) {
        delete(store.current.map { it.symbol }, store, model)
        model.clearCaches()
    }
}
