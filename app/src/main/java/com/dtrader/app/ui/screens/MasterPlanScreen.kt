package com.dtrader.app.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.dtrader.app.data.AvoidEntry
import com.dtrader.app.data.FuturesYieldRow
import com.dtrader.app.data.LeaderRead
import com.dtrader.app.data.MarketSession
import com.dtrader.app.data.MasterPlan
import com.dtrader.app.data.MasterPlanRepository
import com.dtrader.app.data.RegimeKind
import com.dtrader.app.data.SectorFlowRow
import com.dtrader.app.data.WatchlistEntry
import com.dtrader.app.data.WorkflowData
import com.dtrader.app.ui.components.CheckItem
import com.dtrader.app.ui.components.DCard
import com.dtrader.app.ui.components.Pill
import com.dtrader.app.ui.theme.Accent
import com.dtrader.app.ui.theme.BearRed
import com.dtrader.app.ui.theme.BullGreen
import com.dtrader.app.ui.theme.InfoBlue
import com.dtrader.app.ui.theme.WarnAmber
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private sealed interface MasterUi {
    data object Loading : MasterUi
    data class Loaded(val plan: MasterPlan) : MasterUi
    data class Error(val message: String) : MasterUi
}

@Composable
fun MasterPlanScreen(modifier: Modifier = Modifier) {
    val repo = remember { MasterPlanRepository() }
    var state by remember { mutableStateOf<MasterUi>(MasterUi.Loading) }
    var refreshTick by remember { mutableStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun refresh() {
        refreshing = true
        try {
            val plan = repo.build(WorkflowData.tickers)
            state = MasterUi.Loaded(plan)
        } catch (e: Throwable) {
            state = MasterUi.Error(e.message ?: "Network error")
        } finally {
            refreshing = false
        }
    }

    LaunchedEffect(refreshTick) {
        refresh()
        while (true) {
            delay(30_000L)
            refresh()
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        TopBar(state, refreshing) { scope.launch { refresh() } }
        when (val s = state) {
            MasterUi.Loading -> Loading()
            is MasterUi.Error -> ErrorBlock(s.message) { refreshTick++ }
            is MasterUi.Loaded -> Content(s.plan)
        }
    }
}

@Composable
private fun TopBar(state: MasterUi, refreshing: Boolean, onRefresh: () -> Unit) {
    val plan = (state as? MasterUi.Loaded)?.plan
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "เกมเช้านี้ — INSTITUTIONAL FLOW",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                plan?.let { "${sessionLabel(it.session)} · ${it.nyTimestamp}" } ?: "กำลังโหลด…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (plan != null) {
                Text(
                    "อัปเดต ${formatTime(plan.asOfMillis)} · auto refresh 30s",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (refreshing) {
            CircularProgressIndicator(
                modifier = Modifier.width(20.dp).height(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(8.dp))
        }
        IconButton(onClick = onRefresh) {
            Icon(Icons.Filled.Refresh, contentDescription = "Refresh", tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun Loading() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(12.dp))
            Text(
                "วิเคราะห์ตลาด — institutional flow",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ErrorBlock(message: String, onRetry: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("ดึงข้อมูลไม่สำเร็จ", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(8.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                    .clickable { onRetry() }
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            ) { Text("ลองใหม่ / Retry", color = MaterialTheme.colorScheme.primary) }
        }
    }
}

@Composable
private fun Content(plan: MasterPlan) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { SessionBanner(plan) }
        item { RegimeCard(plan) }
        item { FuturesYieldsCard(plan) }
        item { SectorFlowCard(plan) }
        item { LeadersCard(plan) }
        item { RotationCard(plan) }
        item { ExpectationCard(plan) }
        item { OpeningWatchlistCard(plan) }
        if (plan.avoidList.isNotEmpty()) item { AvoidListCard(plan) }
        item { FinalConclusionCard(plan) }
        item { RulesCard(plan) }
        item { RoutineCard(plan) }
        item { MantraCard(plan) }
        item {
            Text(
                "Data: Yahoo Finance public chart API (regular + pre/post). " +
                    "RS = ticker% − SPY%. 5D/20D = trailing daily-bar returns. " +
                    "Refresh 30s · ไม่ใช่คำแนะนำการลงทุน",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
            )
        }
    }
}

// ---------------- Section cards ----------------

@Composable
private fun SessionBanner(plan: MasterPlan) {
    val (color, narrative) = when (plan.session) {
        MarketSession.PREMARKET -> InfoBlue to "Pre-market — ดูเงินไหลก่อนเปิด, lock watchlist"
        MarketSession.REGULAR -> BullGreen to "ตลาดเปิด — RS ranking + VWAP reclaim เท่านั้น"
        MarketSession.POSTMARKET -> WarnAmber to "หลังตลาดปิด — เน้น earnings movers"
        MarketSession.CLOSED -> Accent to "ตลาดปิด — เตรียม watchlist + อ่าน narrative"
    }
    DCard(title = "Session · ${sessionLabel(plan.session)}", accent = color) {
        Text(narrative, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(4.dp))
        Text("นาฬิกานิวยอร์ก: ${plan.nyTimestamp}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RegimeCard(plan: MasterPlan) {
    val color = regimeColor(plan.regime.kind)
    DCard(title = "1 · Market Regime", accent = color) {
        Pill(text = plan.regime.nameTh, color = color)
        Spacer(Modifier.height(10.dp))
        Text(plan.regime.nameTh,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface)
        Text(plan.regime.nameEn,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Text(plan.regime.whyTh,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface)
        if (plan.regime.signals.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            SignalGrid(plan.regime.signals)
        }
    }
}

@Composable
private fun FuturesYieldsCard(plan: MasterPlan) {
    DCard(title = "Futures + Yields", accent = InfoBlue) {
        if (plan.futuresYields.isEmpty()) {
            Text("ดึงข้อมูล futures/macro ไม่ครบ",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            plan.futuresYields.forEach { FuturesRow(it) }
        }
    }
}

@Composable
private fun FuturesRow(row: FuturesYieldRow) {
    val color = if (row.pctChange >= 0) BullGreen else BearRed
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(row.labelTh, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            if (row.interpretationTh.isNotEmpty()) {
                Text(row.interpretationTh,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(formatPct(row.pctChange), style = MaterialTheme.typography.titleMedium, color = color)
    }
}

@Composable
private fun SectorFlowCard(plan: MasterPlan) {
    DCard(title = "2 · Sector Flow", accent = BullGreen) {
        Text("STRONGEST",
            style = MaterialTheme.typography.labelMedium,
            color = BullGreen)
        plan.strongestSectors.forEach { SectorRow(it) }
        if (plan.weakestSectors.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text("WEAKEST",
                style = MaterialTheme.typography.labelMedium,
                color = BearRed)
            plan.weakestSectors.forEach { SectorRow(it) }
        }
    }
}

@Composable
private fun SectorRow(row: SectorFlowRow) {
    val color = if (row.pctChange >= 0) BullGreen else BearRed
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill(text = row.symbol, color = color)
            Spacer(Modifier.width(10.dp))
            Text(row.nameTh, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            Text(formatPct(row.pctChange), style = MaterialTheme.typography.titleMedium, color = color)
        }
        Spacer(Modifier.height(2.dp))
        Text(
            "5D ${formatPct(row.fiveDayReturn)} · RelVol ${"%.2f".format(row.relVolume)}x · ${row.flowReadTh}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LeadersCard(plan: MasterPlan) {
    val title = when (plan.session) {
        MarketSession.PREMARKET -> "3 · Premarket Leaders"
        MarketSession.POSTMARKET -> "3 · Post-Market Leaders"
        else -> "3 · Session Leaders"
    }
    DCard(title = title, accent = Accent) {
        if (plan.leaders.isEmpty()) {
            Text("ยังไม่มี leader ที่ชัด — รอ flow",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@DCard
        }
        plan.leaders.forEachIndexed { i, l ->
            LeaderBlock(rank = i + 1, leader = l)
            if (i != plan.leaders.lastIndex) {
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

@Composable
private fun LeaderBlock(rank: Int, leader: LeaderRead) {
    val context = LocalContext.current
    val color = if (leader.sessionPct >= 0) BullGreen else BearRed
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .clickable {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, WorkflowData.tradingViewUrl(leader.ticker.symbol).toUri())
                )
            }
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .width(28.dp).height(28.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center,
            ) { Text("#$rank", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
            Spacer(Modifier.width(10.dp))
            Pill(text = leader.ticker.symbol, color = color)
            Spacer(Modifier.width(10.dp))
            Text(leader.ticker.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            Text(formatPct(leader.sessionPct), style = MaterialTheme.typography.titleMedium, color = color)
        }
        Spacer(Modifier.height(8.dp))
        LeaderLine("Narrative", leader.narrativeTh)
        LeaderLine("Relative strength", leader.relativeStrengthTh)
        LeaderLine("Liquidity", leader.liquidityTh)
        LeaderLine("Catalyst", leader.catalystTh)
        LeaderLine("Institutional read", leader.institutionalTh)
        LeaderLine("Sustainable?", leader.sustainableTh)
        LeaderLine("Crowding", leader.crowdingTh)
    }
}

@Composable
private fun LeaderLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            label,
            modifier = Modifier.width(120.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            value,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun RotationCard(plan: MasterPlan) {
    DCard(title = "4 · Narrative Rotation", accent = WarnAmber) {
        Text(
            plan.rotation.arrowTh,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(6.dp))
        Text(plan.rotation.whyTh, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(8.dp))
        Row {
            Pill(text = "Stage: ${plan.rotation.stageTh}", color = InfoBlue)
            Spacer(Modifier.width(8.dp))
            Pill(text = "Driver: ${plan.rotation.driverTh}", color = Accent)
        }
    }
}

@Composable
private fun ExpectationCard(plan: MasterPlan) {
    DCard(title = "5 · Expectation vs Reality", accent = InfoBlue) {
        Text("Crowded / over-owned",
            style = MaterialTheme.typography.labelMedium,
            color = BearRed)
        if (plan.expectation.crowdedTh.isEmpty()) {
            Text("— ไม่พบสัญญาณ crowded ชัดเจน",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp))
        } else {
            plan.expectation.crowdedTh.forEach {
                Text("• $it",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(vertical = 2.dp))
            }
        }
        Spacer(Modifier.height(10.dp))
        Text("Under-owned / hidden strength",
            style = MaterialTheme.typography.labelMedium,
            color = BullGreen)
        if (plan.expectation.underOwnedTh.isEmpty()) {
            Text("— ไม่พบสัญญาณ under-owned breakout",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp))
        } else {
            plan.expectation.underOwnedTh.forEach {
                Text("• $it",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(vertical = 2.dp))
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(plan.expectation.expectationGapTh,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun OpeningWatchlistCard(plan: MasterPlan) {
    DCard(title = "6 · Opening Watchlist (≤ 3 names)", accent = BullGreen) {
        if (plan.openingWatchlist.isEmpty()) {
            Text("ยังไม่มี setup ผ่านคุณภาพ — ถือเงินสด",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@DCard
        }
        plan.openingWatchlist.forEachIndexed { i, w ->
            WatchlistBlock(rank = i + 1, entry = w)
            if (i != plan.openingWatchlist.lastIndex) Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun WatchlistBlock(rank: Int, entry: WatchlistEntry) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .clickable {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, WorkflowData.tradingViewUrl(entry.ticker.symbol).toUri())
                )
            }
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .width(28.dp).height(28.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center,
            ) { Text("#$rank", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
            Spacer(Modifier.width(10.dp))
            Pill(text = entry.ticker.symbol, color = BullGreen)
            Spacer(Modifier.width(10.dp))
            Text(entry.ticker.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface)
        }
        Spacer(Modifier.height(8.dp))
        LeaderLine("Why", entry.whyTh)
        LeaderLine("Confirms", entry.confirmsTh)
        LeaderLine("Invalidates", entry.invalidatesTh)
        LeaderLine("Institutional", entry.institutionalLooksLikeTh)
        LeaderLine("Entry style", entry.entryStyleTh)
    }
}

@Composable
private fun AvoidListCard(plan: MasterPlan) {
    DCard(title = "7 · Avoid List · กับดักวันนี้", accent = BearRed) {
        plan.avoidList.forEach { AvoidRow(it) }
    }
}

@Composable
private fun AvoidRow(entry: AvoidEntry) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
        Pill(text = entry.symbol, color = BearRed)
        Spacer(Modifier.width(10.dp))
        Text(entry.reasonTh,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun FinalConclusionCard(plan: MasterPlan) {
    DCard(title = "Final Conclusion · สรุปวันนี้", accent = Accent) {
        Pill(text = plan.finalConclusion.dayTypeTh, color = regimeColor(plan.regime.kind))
        Spacer(Modifier.height(8.dp))
        LeaderLine("Dominant flow", plan.finalConclusion.dominantFlowTh)
        LeaderLine("Dominant narrative", plan.finalConclusion.dominantNarrativeTh)
        LeaderLine("Institutional behavior", plan.finalConclusion.likelyInstitutionalBehaviorTh)
    }
}

@Composable
private fun RulesCard(plan: MasterPlan) {
    DCard(title = "Rules วันนี้", accent = BearRed) {
        plan.rulesTh.forEachIndexed { i, r -> CheckItem(text = r, index = i + 1) }
    }
}

@Composable
private fun RoutineCard(plan: MasterPlan) {
    DCard(title = "Daily Routine (ET)", accent = InfoBlue) {
        plan.routine.forEach { step ->
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
                Box(
                    modifier = Modifier
                        .width(110.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(step.timeLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(step.titleTh, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                    Text(step.titleEn, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(2.dp))
                    Text(step.detailTh, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

@Composable
private fun MantraCard(plan: MasterPlan) {
    DCard(title = "Mantra", accent = Accent) {
        Text("“${plan.mantraTh}”",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface)
    }
}

// ---------------- Helpers ----------------

@Composable
private fun SignalGrid(signals: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        signals.chunked(2).forEach { row ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { label ->
                    Box(modifier = Modifier.weight(1f)) { Pill(text = label, color = signalColor(label)) }
                }
                if (row.size == 1) Box(modifier = Modifier.weight(1f)) {}
            }
        }
    }
}

private fun sessionLabel(s: MarketSession) = "${s.labelTh} · ${s.labelEn}"

private fun regimeColor(k: RegimeKind): Color = when (k) {
    RegimeKind.AI_ACCELERATION, RegimeKind.AI_CONSOLIDATION -> InfoBlue
    RegimeKind.POWER_BOTTLENECK -> WarnAmber
    RegimeKind.BROAD_MOMENTUM -> BullGreen
    RegimeKind.SELECTIVE_ROTATION -> Accent
    RegimeKind.DEFENSIVE_FLOW -> WarnAmber
    RegimeKind.CROWDED_EUPHORIC -> WarnAmber
    RegimeKind.FRAGILE -> BearRed
    RegimeKind.LIQUIDITY_SQUEEZE, RegimeKind.RISK_OFF -> BearRed
}

private fun signalColor(label: String): Color = when {
    label.contains("VIX") && label.contains("+") -> BearRed
    label.contains("VIX") -> BullGreen
    label.contains("US10Y") && label.contains("+") -> WarnAmber
    label.contains("US10Y") -> InfoBlue
    label.contains("DXY") && label.contains("+") -> WarnAmber
    label.contains("DXY") -> InfoBlue
    label.contains("Breadth") -> InfoBlue
    label.contains("+") -> BullGreen
    else -> BearRed
}

private fun formatPct(v: Double): String =
    if (v >= 0) "+%.2f%%".format(v) else "%.2f%%".format(v)

private fun formatTime(millis: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(millis))
