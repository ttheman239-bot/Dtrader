package com.dtrader.app.data

/**
 * Static workflow data for the Dtrader app.
 *
 * Encodes the day-trader playbook for the AI + Power + Infra narrative:
 * Phase 1 = pre-market preparation, Phase 2 = open execution,
 * Phase 3 = entry on VWAP confirmation. Tools tab is the quick-access
 * cheat sheet.
 */

data class LinkRef(
    val label: String,
    val url: String,
    val note: String = "",
)

data class Ticker(
    val symbol: String,
    val name: String,
    val theme: TickerTheme,
)

enum class TickerTheme(val displayName: String) {
    SEMIS("AI / Semiconductors"),
    POWER("Power / Infra"),
    SOFTWARE("AI Software"),
}

object WorkflowData {

    val futuresLinks = listOf(
        LinkRef("Nasdaq 100 Futures", "https://www.investing.com/indices/nq-100-futures", "Risk-on tone"),
        LinkRef("S&P 500 Futures", "https://www.investing.com/indices/us-spx-500-futures", "Broad market"),
        LinkRef("Dow Futures", "https://www.investing.com/indices/us-30-futures", "Cyclical tone"),
        LinkRef("US 10Y Yield", "https://www.tradingview.com/symbols/TVC-US10Y/", "Yield down = growth bid"),
    )

    val sectorLinks = listOf(
        LinkRef("Finviz Heatmap", "https://finviz.com/map.ashx?t=sec", "Sector rotation at a glance"),
        LinkRef("Finviz Semis", "https://finviz.com/groups.ashx?g=industry&sg=semiconductors&v=210", "Semis breadth"),
        LinkRef("Finviz Utilities", "https://finviz.com/groups.ashx?g=industry&sg=electricutilities&v=210", "Power leadership"),
    )

    val newsLinks = listOf(
        LinkRef("Benzinga News", "https://www.benzinga.com/news", "Fast tape headlines"),
        LinkRef("MarketWatch", "https://www.marketwatch.com/markets", "Macro framing"),
        LinkRef("TrendForce", "https://www.trendforce.com/news/", "AI supply chain narrative"),
        LinkRef("Seeking Alpha Earnings", "https://seekingalpha.com/earnings/earnings-calendar", "Earnings catalysts"),
        LinkRef("Earnings Whispers", "https://www.earningswhispers.com/calendar", "Whisper numbers"),
    )

    val premarketLinks = listOf(
        LinkRef("MarketWatch Premarket", "https://www.marketwatch.com/tools/screener/premarket", "Gainers & losers"),
        LinkRef("TradingView Screener", "https://www.tradingview.com/screener/", "Custom premarket scan"),
        LinkRef("Finviz Premarket", "https://finviz.com/screener.ashx?v=111&s=ta_topgainers", "Top gainers w/ volume"),
    )

    val tickers = listOf(
        Ticker("NVDA", "NVIDIA Corporation", TickerTheme.SEMIS),
        Ticker("AVGO", "Broadcom Inc.", TickerTheme.SEMIS),
        Ticker("AMD", "Advanced Micro Devices", TickerTheme.SEMIS),
        Ticker("MU", "Micron Technology", TickerTheme.SEMIS),
        Ticker("VRT", "Vertiv Holdings", TickerTheme.POWER),
        Ticker("VST", "Vistra Corp.", TickerTheme.POWER),
        Ticker("CEG", "Constellation Energy", TickerTheme.POWER),
        Ticker("META", "Meta Platforms", TickerTheme.SOFTWARE),
        Ticker("MSFT", "Microsoft", TickerTheme.SOFTWARE),
    )

    val phase1Checklist = listOf(
        "Check Nasdaq / S&P / Dow futures direction",
        "Read US10Y — yield down = growth/AI tailwind",
        "Open Finviz heatmap, mark sector leaders",
        "Scan Benzinga + TrendForce for a real catalyst",
        "Filter premarket movers: volume + gap + sector + news",
        "Write down top 3 candidates BEFORE the bell",
    )

    val phase2Checklist = listOf(
        "First 5 min — do NOT trade, just observe",
        "Rank candidates by relative strength (% vs SPY)",
        "Confirm volume above 1.5x average on the 5-min",
        "Sector ETF (SMH / XLU) must be green to confirm",
        "Pick ONE leader — never trade the whole basket",
    )

    val phase3Checklist = listOf(
        "Wait for first pullback to VWAP",
        "Look for hammer / inside bar holding VWAP",
        "Volume must come back IN on the reclaim candle",
        "9 EMA crossing back above price = trigger",
        "Stop = below VWAP or pullback low (whichever tighter)",
        "First target: 1R / prior session high / round number",
    )

    val rules = listOf(
        "Trade flow, not predictions — follow institutional money",
        "One leader per day, not the whole sector",
        "No FOMO buy on the first green candle",
        "If thesis breaks (yields rip, sector ETF red) — exit, no negotiation",
        "Pre-market plan > intraday improvisation",
    )

    fun tradingViewUrl(symbol: String) = "https://www.tradingview.com/symbols/NASDAQ-$symbol/"
    fun finvizUrl(symbol: String) = "https://finviz.com/quote.ashx?t=$symbol"
}
