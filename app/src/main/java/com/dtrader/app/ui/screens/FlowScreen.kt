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
import com.dtrader.app.data.FlowRepository
import com.dtrader.app.data.FlowSnapshot
import com.dtrader.app.data.RegimeTone
import com.dtrader.app.data.SectorReading
import com.dtrader.app.data.WatchlistReading
import com.dtrader.app.data.WorkflowData
import com.dtrader.app.ui.components.DCard
import com.dtrader.app.ui.components.Pill
import com.dtrader.app.ui.components.SectionHeader
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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private sealed interface FlowUiState {
    data object Loading : FlowUiState
    data class Loaded(val snapshot: FlowSnapshot) : FlowUiState
    data class Error(val message: String) : FlowUiState
}

@Composable
fun FlowScreen(modifier: Modifier = Modifier) {
    val repo = remember { FlowRepository() }
    var state by remember { mutableStateOf<FlowUiState>(FlowUiState.Loading) }
    var refreshTick by remember { mutableStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun refresh() {
        refreshing = true
        try {
            val snap = repo.load(WorkflowData.tickers)
            state = FlowUiState.Loaded(snap)
        } catch (e: Throwable) {
            state = FlowUiState.Error(e.message ?: "Network error")
        } finally {
            refreshing = false
        }
    }

    // Initial load + auto-refresh every 30s.
    LaunchedEffect(refreshTick) {
        refresh()
        while (true) {
            delay(30_000L)
            refresh()
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Header(
            asOfMillis = (state as? FlowUiState.Loaded)?.snapshot?.asOfMillis,
            refreshing = refreshing,
            onRefresh = { scope.launch { refresh() } },
        )

        when (val s = state) {
            FlowUiState.Loading -> LoadingBlock()
            is FlowUiState.Error -> ErrorBlock(s.message) { refreshTick++ }
            is FlowUiState.Loaded -> LoadedContent(s.snapshot)
        }
    }
}

@Composable
private fun Header(asOfMillis: Long?, refreshing: Boolean, onRefresh: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "REAL-TIME FLOW",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = if (asOfMillis != null) "Updated ${formatTime(asOfMillis)}"
                else "Fetching live quotes…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
private fun LoadingBlock() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(12.dp))
            Text(
                "Reading the tape…",
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
                "Couldn't reach the quote feed.",
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
                Text("Retry", color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun LoadedContent(snap: FlowSnapshot) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { RegimeCard(snap) }

        item {
            DCard(title = "Benchmarks & Macro", accent = InfoBlue) {
                snap.benchmarks.forEach { ReadingRow(it.symbol, it.name, it.pctChange, it.relVolume) }
            }
        }

        item {
            DCard(title = "Sector Flow (sorted)", accent = BullGreen) {
                if (snap.sectors.isEmpty()) {
                    Text(
                        "No sector data available right now.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    val maxAbs = max(
                        0.5,
                        snap.sectors.maxOf { abs(it.pctChange) },
                    )
                    snap.sectors.forEach { SectorBarRow(it, maxAbs) }
                }
            }
        }

        item {
            DCard(title = "Watchlist — RS vs SPY", accent = Accent) {
                if (snap.watchlist.isEmpty()) {
                    Text(
                        "Waiting for ticker quotes…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    snap.watchlist.forEachIndexed { i, w ->
                        WatchlistRow(rank = i + 1, reading = w)
                    }
                }
            }
        }

        item {
            Text(
                "Data via Yahoo Finance public chart API. RS = ticker% − SPY%. " +
                    "RelVol = today / 3-month avg. Refreshes every 30s.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun RegimeCard(snap: FlowSnapshot) {
    val tone = snap.regime.tone
    val color = when (tone) {
        RegimeTone.RISK_ON -> BullGreen
        RegimeTone.AI_INFRA -> InfoBlue
        RegimeTone.POWER -> WarnAmber
        RegimeTone.MIXED -> Accent
        RegimeTone.RISK_OFF -> BearRed
    }
    DCard(title = "Where money is flowing", accent = color) {
        Pill(text = tone.name.replace('_', ' '), color = color)
        Spacer(Modifier.height(10.dp))
        Text(
            snap.regime.headline,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            snap.regime.detail,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (snap.regime.signals.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            FlowRow(snap.regime.signals)
        }
    }
}

@Composable
private fun FlowRow(signals: List<String>) {
    // Simple two-column flow without the experimental FlowRow API.
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        signals.chunked(2).forEach { pair ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                pair.forEach { s ->
                    Box(modifier = Modifier.weight(1f)) {
                        Pill(text = s, color = signalColor(s))
                    }
                }
                if (pair.size == 1) Box(modifier = Modifier.weight(1f)) {}
            }
        }
    }
}

@Composable
private fun ReadingRow(symbol: String, name: String, pct: Double, relVol: Double) {
    val color = if (pct >= 0) BullGreen else BearRed
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Pill(text = symbol, color = color)
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            if (relVol > 0) {
                Text(
                    "RelVol ${"%.2f".format(relVol)}x",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = formatPct(pct),
            style = MaterialTheme.typography.titleMedium,
            color = color,
        )
    }
}

@Composable
private fun SectorBarRow(reading: SectorReading, maxAbs: Double) {
    val color = if (reading.pctChange >= 0) BullGreen else BearRed
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill(text = reading.symbol, color = color)
            Spacer(Modifier.width(10.dp))
            Text(
                reading.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                formatPct(reading.pctChange),
                style = MaterialTheme.typography.titleMedium,
                color = color,
            )
        }
        Spacer(Modifier.height(6.dp))
        SignedBar(value = reading.pctChange, maxAbs = maxAbs, color = color)
        if (reading.relVolume > 0) {
            Text(
                "RelVol ${"%.2f".format(reading.relVolume)}x",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun SignedBar(value: Double, maxAbs: Double, color: Color) {
    val capped = max(-maxAbs, min(maxAbs, value))
    val frac = (abs(capped) / maxAbs).toFloat().coerceIn(0f, 1f)
    Row(modifier = Modifier.fillMaxWidth()) {
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            if (value < 0) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(frac)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(color),
                )
            }
        }
        Box(
            modifier = Modifier
                .width(2.dp)
                .height(8.dp)
                .background(MaterialTheme.colorScheme.outline),
        )
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value >= 0) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(frac)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(color),
                )
            }
        }
    }
}

@Composable
private fun WatchlistRow(rank: Int, reading: WatchlistReading) {
    val context = LocalContext.current
    val color = if (reading.rsVsSpy >= 0) BullGreen else BearRed
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable {
                val url = WorkflowData.tradingViewUrl(reading.ticker.symbol)
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
        Pill(text = reading.ticker.symbol, color = color)
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                reading.ticker.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "RS ${formatPct(reading.rsVsSpy)} · RelVol ${"%.2f".format(reading.relVolume)}x",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            formatPct(reading.pctChange),
            style = MaterialTheme.typography.titleMedium,
            color = color,
        )
    }
}

private fun formatPct(v: Double): String =
    if (v >= 0) "+%.2f%%".format(v) else "%.2f%%".format(v)

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

private fun formatTime(millis: Long): String {
    val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    return fmt.format(Date(millis))
}
