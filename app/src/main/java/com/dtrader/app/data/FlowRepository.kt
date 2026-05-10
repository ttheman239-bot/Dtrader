package com.dtrader.app.data

/**
 * Computes "where money is flowing" from a snapshot of quotes.
 *
 *  * Regime: derived from SPY / QQQ / SMH / XLU / VIX / US10Y / DXY.
 *  * Sector ranking: ETF % change ordered desc, with relative volume.
 *  * Watchlist ranking: relative strength vs SPY = stock.pct - SPY.pct.
 *
 *  The regime narrative is the same heuristic the playbook codifies —
 *  yields softer + semis leading = AI infra setup; XLU leading = power
 *  rotation; VIX up + breadth red = risk-off, stand aside.
 */

data class SectorReading(
    val symbol: String,
    val name: String,
    val pctChange: Double,
    val relVolume: Double,
)

data class WatchlistReading(
    val ticker: Ticker,
    val pctChange: Double,
    val rsVsSpy: Double,
    val relVolume: Double,
)

data class RegimeReading(
    val headline: String,
    val detail: String,
    val tone: RegimeTone,
    val signals: List<String>,
)

enum class RegimeTone { RISK_ON, RISK_OFF, MIXED, POWER, AI_INFRA }

data class FlowSnapshot(
    val regime: RegimeReading,
    val benchmarks: List<SectorReading>,
    val sectors: List<SectorReading>,
    val watchlist: List<WatchlistReading>,
    val asOfMillis: Long,
)

class FlowRepository(private val api: QuoteApi = QuoteApi()) {

    private val benchmarkSymbols = listOf(
        "SPY" to "S&P 500",
        "QQQ" to "Nasdaq 100",
        "DIA" to "Dow Jones",
        "^TNX" to "US 10Y Yield",
        "DX-Y.NYB" to "DXY (USD)",
        "^VIX" to "VIX",
    )

    private val sectorSymbols = listOf(
        "SMH" to "Semiconductors",
        "XLK" to "Technology",
        "XLC" to "Communications",
        "XLU" to "Utilities",
        "XLE" to "Energy",
        "XLF" to "Financials",
        "XLI" to "Industrials",
        "XLY" to "Consumer Discretionary",
        "XLV" to "Healthcare",
    )

    suspend fun load(tickers: List<Ticker>): FlowSnapshot {
        val allSymbols = (
            benchmarkSymbols.map { it.first } +
                sectorSymbols.map { it.first } +
                tickers.map { it.symbol }
            ).distinct()

        val quotes = api.fetchAll(allSymbols)

        val benchmarks = benchmarkSymbols.mapNotNull { (sym, name) ->
            quotes[sym]?.let { SectorReading(sym, name, it.pctChange, it.relVolume) }
        }
        val sectors = sectorSymbols.mapNotNull { (sym, name) ->
            quotes[sym]?.let { SectorReading(sym, name, it.pctChange, it.relVolume) }
        }.sortedByDescending { it.pctChange }

        val spyPct = quotes["SPY"]?.pctChange ?: 0.0
        val watch = tickers.mapNotNull { t ->
            quotes[t.symbol]?.let { q ->
                WatchlistReading(
                    ticker = t,
                    pctChange = q.pctChange,
                    rsVsSpy = q.pctChange - spyPct,
                    relVolume = q.relVolume,
                )
            }
        }.sortedByDescending { it.rsVsSpy }

        val regime = inferRegime(quotes)

        return FlowSnapshot(
            regime = regime,
            benchmarks = benchmarks,
            sectors = sectors,
            watchlist = watch,
            asOfMillis = System.currentTimeMillis(),
        )
    }

    private fun inferRegime(quotes: Map<String, Quote>): RegimeReading {
        val spy = quotes["SPY"]?.pctChange
        val qqq = quotes["QQQ"]?.pctChange
        val smh = quotes["SMH"]?.pctChange
        val xlu = quotes["XLU"]?.pctChange
        val xlk = quotes["XLK"]?.pctChange
        val xle = quotes["XLE"]?.pctChange
        val vix = quotes["^VIX"]?.pctChange
        val tnx = quotes["^TNX"]?.pctChange
        val dxy = quotes["DX-Y.NYB"]?.pctChange

        val signals = mutableListOf<String>()
        if (spy != null) signals += "SPY ${pct(spy)}"
        if (qqq != null) signals += "QQQ ${pct(qqq)}"
        if (smh != null) signals += "SMH ${pct(smh)}"
        if (xlu != null) signals += "XLU ${pct(xlu)}"
        if (xle != null) signals += "XLE ${pct(xle)}"
        if (vix != null) signals += "VIX ${pct(vix)}"
        if (tnx != null) signals += "US10Y ${pct(tnx)}"
        if (dxy != null) signals += "DXY ${pct(dxy)}"

        val riskOn = (spy ?: 0.0) > 0.1 && (qqq ?: 0.0) > 0.0 && (vix ?: 0.0) < 0.0
        val riskOff = (spy ?: 0.0) < -0.3 || (vix ?: 0.0) > 4.0
        val aiLeading = (smh ?: -99.0) > (spy ?: 0.0) + 0.3 && (tnx ?: 99.0) <= 0.5
        val powerLeading = (xlu ?: -99.0) > (spy ?: 0.0) + 0.4

        return when {
            riskOff -> RegimeReading(
                headline = "Risk-Off — stand aside",
                detail = "VIX bid, breadth weak. Don't fight the tape; wait for VWAP" +
                    " stabilisation before any AI/Power swing.",
                tone = RegimeTone.RISK_OFF,
                signals = signals,
            )
            aiLeading -> RegimeReading(
                headline = "AI Infra setup — semis leading, yields soft",
                detail = "SMH leads SPY and US10Y is contained. Focus the watch on" +
                    " AVGO / NVDA / MU; require VWAP reclaim with sector confirm.",
                tone = RegimeTone.AI_INFRA,
                signals = signals,
            )
            powerLeading -> RegimeReading(
                headline = "Power rotation — utilities outperforming",
                detail = "XLU bid relative to SPY suggests money rotating to power /" +
                    " grid names (VRT, VST, CEG). Watch for volume confirm on the leader.",
                tone = RegimeTone.POWER,
                signals = signals,
            )
            riskOn -> RegimeReading(
                headline = "Risk-On — broad bid",
                detail = "SPY + QQQ green, VIX softening. Trade the leader of the day," +
                    " not the whole basket.",
                tone = RegimeTone.RISK_ON,
                signals = signals,
            )
            else -> RegimeReading(
                headline = "Mixed tape — pick your leader",
                detail = "No single sector dominates. Lean on relative strength" +
                    " ranking and only enter on a clean VWAP reclaim.",
                tone = RegimeTone.MIXED,
                signals = signals,
            )
        }
    }

    private fun pct(v: Double): String = if (v >= 0) "+%.2f%%".format(v) else "%.2f%%".format(v)
}
