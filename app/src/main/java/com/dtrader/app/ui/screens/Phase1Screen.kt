package com.dtrader.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dtrader.app.data.WorkflowData
import com.dtrader.app.ui.components.CheckItem
import com.dtrader.app.ui.components.DCard
import com.dtrader.app.ui.components.LinkRow
import com.dtrader.app.ui.components.SectionHeader
import com.dtrader.app.ui.theme.BullGreen
import com.dtrader.app.ui.theme.InfoBlue
import com.dtrader.app.ui.theme.WarnAmber

@Composable
fun Phase1Screen(modifier: Modifier = Modifier) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            SectionHeader(
                eyebrow = "60–90 min before the bell",
                title = "Map where money is flowing",
                subtitle = "Read regime → sector → catalyst → premarket. " +
                    "End with a written 3-name shortlist.",
            )
        }

        item {
            DCard(title = "1. Futures + Bond Yield", accent = InfoBlue) {
                WorkflowData.futuresLinks.forEach {
                    LinkRow(it.label, it.url, it.note)
                }
            }
        }

        item {
            DCard(title = "2. Sector Strength", accent = BullGreen) {
                WorkflowData.sectorLinks.forEach {
                    LinkRow(it.label, it.url, it.note)
                }
            }
        }

        item {
            DCard(title = "3. News & Catalysts", accent = WarnAmber) {
                WorkflowData.newsLinks.forEach {
                    LinkRow(it.label, it.url, it.note)
                }
            }
        }

        item {
            DCard(title = "4. Premarket Movers", accent = InfoBlue) {
                WorkflowData.premarketLinks.forEach {
                    LinkRow(it.label, it.url, it.note)
                }
            }
        }

        item {
            DCard(title = "Pre-Market Checklist") {
                WorkflowData.phase1Checklist.forEachIndexed { i, t ->
                    CheckItem(text = t, index = i + 1)
                }
            }
        }
    }
}
