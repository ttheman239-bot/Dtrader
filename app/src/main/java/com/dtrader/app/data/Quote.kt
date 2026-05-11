package com.dtrader.app.data

/**
 * Snapshot of a ticker pulled from Yahoo Finance's chart endpoint.
 *
 *  - `pctChange`   intraday vs. previous close (regular hours).
 *  - `relVolume`   today / trailing-3-month average (institutional proxy).
 *  - `premarketPct` / `postmarketPct`  populated when Yahoo returns them.
 *  - `fiveDayReturn` / `twentyDayReturn`  trailing trend windows used for
 *    crowding + rotation detection.
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
    val fiveDayReturn: Double = 0.0,
    val twentyDayReturn: Double = 0.0,
) {
    val relVolume: Double = if (avgVolume > 0) volume.toDouble() / avgVolume.toDouble() else 0.0

    fun sessionPct(session: MarketSession): Double = when (session) {
        MarketSession.PREMARKET -> premarketPct ?: pctChange
        MarketSession.POSTMARKET -> postmarketPct ?: pctChange
        else -> pctChange
    }
}
