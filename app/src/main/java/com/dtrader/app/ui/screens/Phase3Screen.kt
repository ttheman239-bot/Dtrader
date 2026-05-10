package com.dtrader.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dtrader.app.data.WorkflowData
import com.dtrader.app.ui.components.CheckItem
import com.dtrader.app.ui.components.DCard
import com.dtrader.app.ui.components.SectionHeader
import com.dtrader.app.ui.theme.Accent
import com.dtrader.app.ui.theme.BearRed
import com.dtrader.app.ui.theme.BullGreen
import com.dtrader.app.ui.theme.WarnAmber

@Composable
fun Phase3Screen(modifier: Modifier = Modifier) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            SectionHeader(
                eyebrow = "Execution",
                title = "VWAP reclaim, then enter",
                subtitle = "Wait for the first pullback. Confirm with volume on the reclaim. " +
                    "No FOMO buys on the first green candle.",
            )
        }

        item {
            DCard(title = "VWAP Setup", accent = BullGreen) {
                WorkflowData.phase3Checklist.forEachIndexed { i, t ->
                    CheckItem(text = t, index = i + 1)
                }
            }
        }

        item {
            DCard(title = "Scenario A — AI Infra Day", accent = Accent) {
                Text(
                    "Catalyst: hyperscaler capex up, yields softening.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                CheckItem("Watchlist: AVGO, NVDA, MU")
                CheckItem("Pick the strongest RS at 09:45 ET")
                CheckItem("Enter on VWAP reclaim with SMH green")
                CheckItem("Stop = pullback low; trail to b/e at 1R")
            }
        }

        item {
            DCard(title = "Scenario B — Power Rotation", accent = WarnAmber) {
                Text(
                    "Catalyst: nuclear PPA, grid shortage, utility upgrade.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                CheckItem("Watchlist: VRT, VST, CEG")
                CheckItem("Need 5x volume on the leader")
                CheckItem("XLU green confirms the rotation")
                CheckItem("Add only on a second VWAP hold")
            }
        }

        item {
            DCard(title = "Hard Rules", accent = BearRed) {
                WorkflowData.rules.forEach { CheckItem(it) }
            }
        }
    }
}
