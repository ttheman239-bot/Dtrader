package com.dtrader.app.data

/**
 * Snapshot of a ticker pulled from Yahoo Finance's chart endpoint.
 *
 * `pctChange` is intraday vs. previous close. `relVolume` is today's
 * volume divided by the trailing-3-month average — a proxy for
 * institutional interest.
 */
data class Quote(
    val symbol: String,
    val price: Double,
    val previousClose: Double,
    val pctChange: Double,
    val volume: Long,
    val avgVolume: Long,
) {
    val relVolume: Double = if (avgVolume > 0) volume.toDouble() / avgVolume.toDouble() else 0.0
}
