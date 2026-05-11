package com.dtrader.app.data

/**
 * Snapshot of a ticker pulled from Yahoo Finance's chart endpoint.
 *
 *  - `pctChange` is intraday vs. previous close.
 *  - `relVolume` = today / trailing-3-month average (proxy for inst. interest).
 *  - `premarketPct` / `postmarketPct` populated when Yahoo returns them.
 */
data class Quote(
    val symbol: String,
    val price: Double,
    val previousClose: Double,
    val pctChange: Double,
    val volume: Long,
    val avgVolume: Long,
    val premarketPrice: Double? = null,
    val premarketPct: Double? = null,
    val postmarketPrice: Double? = null,
    val postmarketPct: Double? = null,
) {
    val relVolume: Double = if (avgVolume > 0) volume.toDouble() / avgVolume.toDouble() else 0.0

    /** Best % move for the supplied session (falls back to regular). */
    fun sessionPct(session: MarketSession): Double = when (session) {
        MarketSession.PREMARKET -> premarketPct ?: pctChange
        MarketSession.POSTMARKET -> postmarketPct ?: pctChange
        else -> pctChange
    }
}
