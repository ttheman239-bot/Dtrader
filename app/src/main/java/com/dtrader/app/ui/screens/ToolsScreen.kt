package com.dtrader.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dtrader.app.ui.components.DCard
import com.dtrader.app.ui.components.LinkRow
import com.dtrader.app.ui.components.SectionHeader
import com.dtrader.app.ui.theme.Accent
import com.dtrader.app.ui.theme.BullGreen
import com.dtrader.app.ui.theme.InfoBlue
import com.dtrader.app.ui.theme.WarnAmber

@Composable
fun ToolsScreen(modifier: Modifier = Modifier) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            SectionHeader(
                eyebrow = "Cheat Sheet",
                title = "Must-have tools",
                subtitle = "The five sites that actually matter, plus calendars.",
            )
        }

        item {
            DCard(title = "Charts & Screening", accent = InfoBlue) {
                LinkRow("TradingView", "https://www.tradingview.com/", "Chart + RS comparison")
                LinkRow("TradingView Screener", "https://www.tradingview.com/screener/", "Custom filters")
                LinkRow("Finviz Heatmap", "https://finviz.com/map.ashx?t=sec", "Sector flow at a glance")
                LinkRow("Finviz Screener", "https://finviz.com/screener.ashx", "Premarket gainers")
            }
        }

        item {
            DCard(title = "News & Catalysts", accent = WarnAmber) {
                LinkRow("Benzinga", "https://www.benzinga.com/news", "Fast tape headlines")
                LinkRow("MarketWatch", "https://www.marketwatch.com/markets", "Macro framing")
                LinkRow("TrendForce", "https://www.trendforce.com/news/", "AI supply chain")
                LinkRow("Reuters Markets", "https://www.reuters.com/markets/", "Wire headlines")
            }
        }

        item {
            DCard(title = "Calendars", accent = BullGreen) {
                LinkRow("Earnings Whispers", "https://www.earningswhispers.com/calendar", "Whisper numbers")
                LinkRow("Seeking Alpha Earnings", "https://seekingalpha.com/earnings/earnings-calendar", "Earnings dates")
                LinkRow("Investing.com Economic Calendar", "https://www.investing.com/economic-calendar/", "Macro events")
                LinkRow("Fed Calendar", "https://www.federalreserve.gov/monetarypolicy/fomccalendars.htm", "FOMC schedule")
            }
        }

        item {
            DCard(title = "Macro Quick Look", accent = Accent) {
                LinkRow("US10Y", "https://www.tradingview.com/symbols/TVC-US10Y/", "Yield direction")
                LinkRow("DXY", "https://www.tradingview.com/symbols/TVC-DXY/", "Dollar index")
                LinkRow("VIX", "https://www.tradingview.com/symbols/CBOE-VIX/", "Vol regime")
                LinkRow("Crude (CL1!)", "https://www.tradingview.com/symbols/NYMEX-CL1!/", "Energy macro")
            }
        }
    }
}
