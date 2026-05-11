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
import com.dtrader.app.data.Bias
import com.dtrader.app.data.MarketSession
import com.dtrader.app.data.MasterPlan
import com.dtrader.app.data.MasterPlanRepository
import com.dtrader.app.data.PickReading
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
        TopBar(
            state = state,
            refreshing = refreshing,
            onRefresh = { scope.launch { refresh() } },
        )
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
                text = "เกมเช้านี้ — MASTER PLAN",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            val sessionLine = plan?.let { "${sessionLabel(it.session)} · ${it.nyTimestamp}" }
                ?: "กำลังโหลดข้อมูล…"
            Text(
                text = sessionLine,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (plan != null) {
                Text(
                    text = "อัปเดต ${formatTime(plan.asOfMillis)} · auto refresh 30s",
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
            Icon(
                Icons.Filled.Refresh,
                contentDescription = "Refresh",
                tint = MaterialTheme.colorScheme.primary,
            )
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
                "วิเคราะห์ตลาด — กำลังโหลด real-time flow",
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
            Text(
                "ดึงข้อมูล quote ไม่สำเร็จ",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                    .clickable { onRetry() }
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            ) {
                Text("ลองใหม่ / Retry", color = MaterialTheme.colorScheme.primary)
            }
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
        item { BiasCard(plan) }
        if (plan.sessionMovers.isNotEmpty()) {
            item { MoversCard(plan) }
        }
        item { TierCard(title = "Tier S — Highest Conviction", color = BullGreen, picks = plan.tierS) }
        if (plan.tierA.isNotEmpty()) {
            item { TierCard(title = "Tier A — รองลงมา", color = InfoBlue, picks = plan.tierA) }
        }
        item { RulesCard(plan) }
        item { RoutineCard(plan) }
        item { MantraCard(plan) }
        item {
            Text(
                "ข้อมูล quote จาก Yahoo Finance public chart API · " +
                    "RS = ticker% − SPY% · RelVol = today / 3-month avg · " +
                    "Refresh ทุก 30 วินาที. ไม่ใช่คำแนะนำการลงทุน",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun SessionBanner(plan: MasterPlan) {
    val (color, narrative) = when (plan.session) {
        MarketSession.PREMARKET -> InfoBlue to
            "Pre-market — ดูเงินไหลก่อนเปิด, lock watchlist, รอ trigger"
        MarketSession.REGULAR -> BullGreen to
            "ตลาดเปิด — RS ranking + VWAP reclaim เท่านั้น"
        MarketSession.POSTMARKET -> WarnAmber to
            "หลังตลาดปิด — เน้น earnings movers, ลด size"
        MarketSession.CLOSED -> Accent to
            "ตลาดปิด — เตรียม watchlist, อ่าน narrative คืนนี้"
    }
    DCard(title = "Session ปัจจุบัน · ${sessionLabel(plan.session)}", accent = color) {
        Text(
            text = narrative,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "นาฬิกานิวยอร์ก: ${plan.nyTimestamp}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BiasCard(plan: MasterPlan) {
    val color = biasColor(plan.bias.bias)
    DCard(title = "Where money is flowing · เงินไหลไปไหน", accent = color) {
        Pill(text = plan.bias.bias.labelTh, color = color)
        Spacer(Modifier.height(10.dp))
        Text(
            plan.bias.headlineTh,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            plan.bias.headlineEn,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            plan.bias.detailTh,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (plan.bias.signals.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            SignalGrid(plan.bias.signals)
        }
    }
}

@Composable
private fun SignalGrid(signals: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        signals.chunked(2).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.forEach { label ->
                    Box(modifier = Modifier.weight(1f)) {
                        Pill(text = label, color = signalColor(label))
                    }
                }
                if (row.size == 1) Box(modifier = Modifier.weight(1f)) {}
            }
        }
    }
}

@Composable
private fun MoversCard(plan: MasterPlan) {
    val title = when (plan.session) {
        MarketSession.PREMARKET -> "Pre-market money flow · ใครได้บิด"
        MarketSession.POSTMARKET -> "Post-market movers · ใครวิ่งหลังปิด"
        else -> "Session movers · ใครวิ่งวันนี้"
    }
    DCard(title = title, accent = Accent) {
        plan.sessionMovers.forEachIndexed { i, p ->
            PickRow(rank = i + 1, pick = p, useSessionPct = true)
        }
    }
}

@Composable
private fun TierCard(title: String, color: Color, picks: List<PickReading>) {
    DCard(title = title, accent = color) {
        if (picks.isEmpty()) {
            Text(
                "ยังไม่มีตัวเลือก — รอข้อมูล",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@DCard
        }
        picks.forEachIndexed { i, p ->
            PickRow(rank = i + 1, pick = p, useSessionPct = false)
            if (p.reasonsTh.isNotEmpty()) {
                Column(modifier = Modifier.padding(start = 46.dp, top = 2.dp, bottom = 10.dp)) {
                    p.reasonsTh.forEach { r ->
                        Text(
                            "• $r",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PickRow(rank: Int, pick: PickReading, useSessionPct: Boolean) {
    val context = LocalContext.current
    val displayPct = if (useSessionPct) pick.sessionPct else pick.regularPct
    val color = if (displayPct >= 0) BullGreen else BearRed
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable {
                val url = WorkflowData.tradingViewUrl(pick.ticker.symbol)
                context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
            }
            .padding(vertical = 8.dp, horizontal = 4.dp),
    ) {
        Box(
            modifier = Modifier
                .width(28.dp)
                .height(28.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text("#$rank", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.width(10.dp))
        Pill(text = pick.ticker.symbol, color = color)
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                pick.ticker.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "Score ${"%.1f".format(pick.score)} · RS ${formatPct(pick.rsVsSpy)} · " +
                    "RelVol ${"%.2f".format(pick.relVolume)}x",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            formatPct(displayPct),
            style = MaterialTheme.typography.titleMedium,
            color = color,
        )
    }
}

@Composable
private fun RulesCard(plan: MasterPlan) {
    DCard(title = "กฎการเทรดวันนี้ · Today's Rules", accent = BearRed) {
        plan.rulesTh.forEachIndexed { i, rule ->
            CheckItem(text = rule, index = i + 1)
        }
    }
}

@Composable
private fun RoutineCard(plan: MasterPlan) {
    DCard(title = "Daily Routine (ET)", accent = InfoBlue) {
        plan.routine.forEach { step ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Box(
                    modifier = Modifier
                        .width(110.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(
                        step.timeLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        step.titleTh,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        step.titleEn,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        step.detailTh,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
private fun MantraCard(plan: MasterPlan) {
    DCard(title = "Mantra วันนี้", accent = Accent) {
        Text(
            "“${plan.mantraTh}”",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

private fun sessionLabel(s: MarketSession) =
    "${s.labelTh} · ${s.labelEn}"

private fun biasColor(b: Bias): Color = when (b) {
    Bias.AI_INFRA -> InfoBlue
    Bias.POWER -> WarnAmber
    Bias.RISK_ON -> BullGreen
    Bias.MIXED -> Accent
    Bias.RISK_OFF -> BearRed
}

private fun signalColor(label: String): Color = when {
    label.contains("VIX") && label.contains("+") -> BearRed
    label.contains("VIX") -> BullGreen
    label.contains("US10Y") && label.contains("+") -> WarnAmber
    label.contains("US10Y") -> InfoBlue
    label.contains("DXY") && label.contains("+") -> WarnAmber
    label.contains("DXY") -> InfoBlue
    label.contains("+") -> BullGreen
    else -> BearRed
}

private fun formatPct(v: Double): String =
    if (v >= 0) "+%.2f%%".format(v) else "%.2f%%".format(v)

private fun formatTime(millis: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(millis))
