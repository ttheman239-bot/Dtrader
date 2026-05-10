package com.dtrader.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class QuoteApi {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    /** Fetch a snapshot for one symbol. Returns null on any error. */
    suspend fun fetchQuote(symbol: String): Quote? = withContext(Dispatchers.IO) {
        val url = "https://query1.finance.yahoo.com/v8/finance/chart/" +
            "$symbol?range=3mo&interval=1d&includePrePost=true"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .build()
        runCatching {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = resp.body?.string() ?: return@use null
                parse(symbol, body)
            }
        }.getOrNull()
    }

    /** Fetch many symbols in parallel. Symbols that fail are dropped. */
    suspend fun fetchAll(symbols: List<String>): Map<String, Quote> = coroutineScope {
        symbols.map { sym -> async(Dispatchers.IO) { sym to fetchQuote(sym) } }
            .map { it.await() }
            .mapNotNull { (s, q) -> q?.let { s to it } }
            .toMap()
    }

    private fun parse(symbol: String, body: String): Quote? {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val chart = root.optJSONObject("chart") ?: return null
        val result = chart.optJSONArray("result")?.optJSONObject(0) ?: return null
        val meta = result.optJSONObject("meta") ?: return null

        val price = meta.optDouble("regularMarketPrice", Double.NaN)
        val prevClose = meta.optDouble("chartPreviousClose", meta.optDouble("previousClose", Double.NaN))
        if (price.isNaN() || prevClose.isNaN() || prevClose <= 0.0) return null

        val pct = (price - prevClose) / prevClose * 100.0

        val quoteObj = result.optJSONObject("indicators")
            ?.optJSONArray("quote")
            ?.optJSONObject(0)
        val volumes = quoteObj?.optJSONArray("volume")
        val volList = mutableListOf<Long>()
        if (volumes != null) {
            for (i in 0 until volumes.length()) {
                val v = volumes.opt(i)
                if (v is Number) volList += v.toLong()
            }
        }
        val todayVol = volList.lastOrNull() ?: meta.optLong("regularMarketVolume", 0L)
        val avgVol = if (volList.size > 1) {
            volList.dropLast(1).filter { it > 0 }.average().toLong()
        } else 0L

        return Quote(
            symbol = symbol,
            price = price,
            previousClose = prevClose,
            pctChange = pct,
            volume = todayVol,
            avgVolume = avgVol,
        )
    }

    private companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36 Dtrader/1.2"
    }
}
