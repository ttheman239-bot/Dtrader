package com.dtrader.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dtrader.app.data.TickerTheme
import com.dtrader.app.data.WorkflowData
import com.dtrader.app.ui.components.CheckItem
import com.dtrader.app.ui.components.DCard
import com.dtrader.app.ui.components.LinkRow
import com.dtrader.app.ui.components.Pill
import com.dtrader.app.ui.components.SectionHeader
import com.dtrader.app.ui.theme.Accent
import com.dtrader.app.ui.theme.BullGreen
import com.dtrader.app.ui.theme.InfoBlue
import com.dtrader.app.ui.theme.WarnAmber

@Composable
fun Phase2Screen(modifier: Modifier = Modifier) {
    val grouped = WorkflowData.tickers.groupBy { it.theme }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            SectionHeader(
                eyebrow = "First 15–30 minutes",
                title = "Find the leader, not all 5 names",
                subtitle = "Rank by relative strength, confirm with sector ETF and volume. " +
                    "One leader per day.",
            )
        }

        item {
            DCard(title = "Open Routine") {
                WorkflowData.phase2Checklist.forEachIndexed { i, t ->
                    CheckItem(text = t, index = i + 1)
                }
            }
        }

        grouped.forEach { (theme, list) ->
            item {
                val color = when (theme) {
                    TickerTheme.SEMIS -> InfoBlue
                    TickerTheme.POWER -> WarnAmber
                    TickerTheme.SOFTWARE -> Accent
                }
                DCard(title = theme.displayName, accent = color) {
                    list.forEach { ticker ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Pill(text = ticker.symbol, color = color)
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = ticker.name,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        LinkRow(
                            label = "TradingView — ${ticker.symbol}",
                            url = WorkflowData.tradingViewUrl(ticker.symbol),
                            note = "Chart, RS, VWAP",
                        )
                        LinkRow(
                            label = "Finviz — ${ticker.symbol}",
                            url = WorkflowData.finvizUrl(ticker.symbol),
                            note = "Quote + news",
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }

        item {
            DCard(title = "Index / Sector Confirmation", accent = BullGreen) {
                LinkRow("Nasdaq 100 (QQQ)", "https://www.tradingview.com/symbols/NASDAQ-QQQ/", "Risk-on confirm")
                LinkRow("Semis ETF (SMH)", "https://www.tradingview.com/symbols/AMEX-SMH/", "Semis breadth")
                LinkRow("Utilities ETF (XLU)", "https://www.tradingview.com/symbols/AMEX-XLU/", "Power confirm")
                LinkRow("S&P 500 (SPY)", "https://www.tradingview.com/symbols/AMEX-SPY/", "Tape direction")
            }
        }
    }
}
