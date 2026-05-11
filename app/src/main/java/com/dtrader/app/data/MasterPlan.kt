package com.dtrader.app.data

import kotlin.math.abs

/**
 * Institutional-grade premarket flow framework.
 *
 *  Maps Yahoo-derived signals into the 7-step framework:
 *    1. Market regime         -> [MarketRegime]
 *    2. Sector flow           -> [strongest / weakest sectors]
 *    3. Premarket leaders     -> [LeaderRead]
 *    4. Narrative + rotation  -> [NarrativeRotation]
 *    5. Expectation vs reality-> [ExpectationRead]
 *    6. Opening watchlist     -> max 3 [WatchlistEntry]
 *    7. Avoid list + final    -> [AvoidEntry] + [FinalConclusion]
 *
 *  Outputs are bilingual (Thai-primary). The job here is detection, not
 *  prediction — every interpretation traces back to a measurable signal.
 */

// ---------------------------------------------------------------------------
// Regime
// ---------------------------------------------------------------------------

enum class RegimeKind {
    BROAD_MOMENTUM,
    SELECTIVE_ROTATION,
    AI_CONSOLIDATION,
    AI_ACCELERATION,
    POWER_BOTTLENECK,
    DEFENSIVE_FLOW,
    CROWDED_EUPHORIC,
    FRAGILE,
    LIQUIDITY_SQUEEZE,
    RISK_OFF,
}

data class MarketRegime(
    val kind: RegimeKind,
    val nameTh: String,
    val nameEn: String,
    val whyTh: String,
    val signals: List<String>,
)

data class FuturesYieldRow(
    val labelTh: String,
    val labelEn: String,
    val pctChange: Double,
    val interpretationTh: String,
)

data class SectorFlowRow(
    val symbol: String,
    val nameTh: String,
    val pctChange: Double,
    val fiveDayReturn: Double,
    val relVolume: Double,
    val flowReadTh: String,
)

data class LeaderRead(
    val ticker: Ticker,
    val sessionPct: Double,
    val regularPct: Double,
    val rsVsSpy: Double,
    val relVolume: Double,
    val twentyDayReturn: Double,
    val narrativeTh: String,
    val relativeStrengthTh: String,
    val liquidityTh: String,
    val catalystTh: String,
    val institutionalTh: String,
    val sustainableTh: String,
    val crowdingTh: String,
)

data class NarrativeRotation(
    val arrowTh: String,
    val arrowEn: String,
    val whyTh: String,
    val stageTh: String,
    val driverTh: String,
)

data class ExpectationRead(
    val crowdedTh: List<String>,
    val underOwnedTh: List<String>,
    val expectationGapTh: String,
)

data class WatchlistEntry(
    val ticker: Ticker,
    val whyTh: String,
    val confirmsTh: String,
    val invalidatesTh: String,
    val institutionalLooksLikeTh: String,
    val entryStyleTh: String,
)

data class AvoidEntry(val symbol: String, val reasonTh: String)

data class FinalConclusion(
    val dayTypeTh: String,
    val dayTypeEn: String,
    val dominantFlowTh: String,
    val dominantNarrativeTh: String,
    val likelyInstitutionalBehaviorTh: String,
)

data class RoutineStep(
    val timeLabel: String,
    val titleTh: String,
    val titleEn: String,
    val detailTh: String,
)

data class MasterPlan(
    val session: MarketSession,
    val nyTimestamp: String,
    val regime: MarketRegime,
    val futuresYields: List<FuturesYieldRow>,
    val strongestSectors: List<SectorFlowRow>,
    val weakestSectors: List<SectorFlowRow>,
    val leaders: List<LeaderRead>,
    val rotation: NarrativeRotation,
    val expectation: ExpectationRead,
    val openingWatchlist: List<WatchlistEntry>,
    val avoidList: List<AvoidEntry>,
    val finalConclusion: FinalConclusion,
    val rulesTh: List<String>,
    val routine: List<RoutineStep>,
    val mantraTh: String,
    val asOfMillis: Long,
)

// ---------------------------------------------------------------------------
// Repository
// ---------------------------------------------------------------------------

private data class SectorMeta(val symbol: String, val nameTh: String, val nameEn: String)

class MasterPlanRepository(private val api: QuoteApi = QuoteApi()) {

    private val futuresMacroSymbols = listOf(
        "ES=F" to "S&P 500 Futures",
        "NQ=F" to "Nasdaq 100 Futures",
        "YM=F" to "Dow Futures",
        "^TNX" to "US 10Y Yield",
        "DX-Y.NYB" to "DXY (USD)",
        "^VIX" to "VIX",
        "CL=F" to "Crude Oil",
    )

    private val sectors = listOf(
        SectorMeta("SMH", "Semiconductors", "Semiconductors"),
        SectorMeta("XLK", "Technology", "Technology"),
        SectorMeta("XLC", "Communications", "Communications"),
        SectorMeta("XLU", "Utilities / Power", "Utilities"),
        SectorMeta("XLE", "Energy", "Energy"),
        SectorMeta("XLF", "Financials", "Financials"),
        SectorMeta("XLI", "Industrials", "Industrials"),
        SectorMeta("XLY", "Consumer Disc.", "Consumer Discretionary"),
        SectorMeta("XLV", "Healthcare", "Healthcare"),
        SectorMeta("XLP", "Consumer Staples", "Consumer Staples"),
    )

    suspend fun build(
        tickers: List<Ticker>,
        session: MarketSession = SessionDetector.current(),
    ): MasterPlan {
        val symbols = (
            futuresMacroSymbols.map { it.first } +
                listOf("SPY", "QQQ") +
                sectors.map { it.symbol } +
                tickers.map { it.symbol }
            ).distinct()

        val quotes = api.fetchAll(symbols)

        val regime = classifyRegime(quotes)
        val futures = buildFuturesYields(quotes)
        val sectorRows = sectors.mapNotNull { meta ->
            quotes[meta.symbol]?.let { q ->
                SectorFlowRow(
                    symbol = meta.symbol,
                    nameTh = meta.nameTh,
                    pctChange = q.pctChange,
                    fiveDayReturn = q.fiveDayReturn,
                    relVolume = q.relVolume,
                    flowReadTh = readSectorFlow(meta.symbol, q),
                )
            }
        }
        val strongest = sectorRows.sortedByDescending { it.pctChange }.take(4)
        val weakest = sectorRows.sortedBy { it.pctChange }.take(3)

        val leaders = tickers.mapNotNull { t -> buildLeaderRead(t, quotes, session, regime.kind) }
            .sortedByDescending { it.sessionPct }
            .take(6)

        val rotation = detectRotation(sectorRows, regime.kind)
        val expectation = readExpectation(tickers, quotes, sectorRows)

        val watchlist = buildOpeningWatchlist(tickers, quotes, session, regime.kind, sectorRows)
        val avoid = buildAvoidList(tickers, quotes, sectorRows, regime.kind)
        val final = buildFinalConclusion(regime, sectorRows, leaders)

        return MasterPlan(
            session = session,
            nyTimestamp = SessionDetector.timestamp(),
            regime = regime,
            futuresYields = futures,
            strongestSectors = strongest,
            weakestSectors = weakest,
            leaders = leaders,
            rotation = rotation,
            expectation = expectation,
            openingWatchlist = watchlist,
            avoidList = avoid,
            finalConclusion = final,
            rulesTh = rulesFor(regime.kind, session),
            routine = routineFor(),
            mantraTh = mantraFor(regime.kind),
            asOfMillis = System.currentTimeMillis(),
        )
    }

    // ----- 1. Regime --------------------------------------------------------

    private fun classifyRegime(quotes: Map<String, Quote>): MarketRegime {
        val spy = quotes["SPY"]?.pctChange ?: 0.0
        val qqq = quotes["QQQ"]?.pctChange ?: 0.0
        val smh = quotes["SMH"]?.pctChange ?: 0.0
        val xlu = quotes["XLU"]?.pctChange ?: 0.0
        val xlk = quotes["XLK"]?.pctChange ?: 0.0
        val xlp = quotes["XLP"]?.pctChange ?: 0.0
        val xlv = quotes["XLV"]?.pctChange ?: 0.0
        val xle = quotes["XLE"]?.pctChange ?: 0.0
        val xlf = quotes["XLF"]?.pctChange ?: 0.0
        val xly = quotes["XLY"]?.pctChange ?: 0.0
        val xli = quotes["XLI"]?.pctChange ?: 0.0
        val xlc = quotes["XLC"]?.pctChange ?: 0.0
        val vix = quotes["^VIX"]?.pctChange ?: 0.0
        val tnx = quotes["^TNX"]?.pctChange ?: 0.0
        val dxy = quotes["DX-Y.NYB"]?.pctChange ?: 0.0
        val spy20 = quotes["SPY"]?.twentyDayReturn ?: 0.0

        val all = listOf(smh, xlk, xlc, xlu, xle, xlf, xli, xly, xlv, xlp)
        val greens = all.count { it > 0.0 }
        val breadth = if (all.isEmpty()) 0.0 else greens.toDouble() / all.size

        val signals = buildList {
            add("SPY ${pct(spy)}")
            add("QQQ ${pct(qqq)}")
            add("SMH ${pct(smh)}")
            add("XLU ${pct(xlu)}")
            add("VIX ${pct(vix)}")
            add("US10Y ${pct(tnx)}")
            add("DXY ${pct(dxy)}")
            add("Breadth ${"%.0f".format(breadth * 100)}%")
        }

        // Order matters — more extreme regimes resolved first.
        return when {
            spy < -0.5 || vix > 8.0 || breadth < 0.25 -> MarketRegime(
                RegimeKind.RISK_OFF,
                "Risk-off / Deleveraging",
                "Risk-off",
                "SPY ติดลบ + VIX พุ่ง + breadth แคบมาก — institutions ลด exposure",
                signals,
            )
            vix > 5.0 && dxy > 0.3 && tnx > 1.0 -> MarketRegime(
                RegimeKind.LIQUIDITY_SQUEEZE,
                "Liquidity Squeeze",
                "Liquidity squeeze",
                "DXY + Yields + VIX วิ่งพร้อมกัน — เงินดอลลาร์ตึง risk asset โดน de-rate",
                signals,
            )
            vix > 4.0 && breadth < 0.45 -> MarketRegime(
                RegimeKind.FRAGILE,
                "Fragile / Crack risk",
                "Fragile",
                "VIX bid แต่ breadth ยังไม่แตก — เก็งกำไรกลุ่มแคบ เสี่ยงพังเร็ว",
                signals,
            )
            xlu > spy + 0.4 && smh < spy - 0.1 -> MarketRegime(
                RegimeKind.DEFENSIVE_FLOW,
                "Defensive Flow",
                "Defensive flow",
                "XLU/XLV เด่นกว่า SPY ขณะที่ semis อ่อน — เงินไหลออกจาก growth เข้า defensive",
                signals,
            )
            xlu > spy + 0.35 && smh > 0.0 -> MarketRegime(
                RegimeKind.POWER_BOTTLENECK,
                "Power Bottleneck",
                "Power bottleneck",
                "XLU เด่นพร้อม semis ยังไม่ติดลบ — narrative bottleneck ของ AI cycle ไหลเข้า power",
                signals,
            )
            smh > spy + 0.4 && tnx <= 0.3 -> MarketRegime(
                RegimeKind.AI_ACCELERATION,
                "AI Acceleration",
                "AI acceleration",
                "SMH นำ SPY มาก, US10Y ไม่กดดัน — flow ไหลเข้า AI infra แรง",
                signals,
            )
            smh < spy && xlk > spy && abs(smh) < 0.3 -> MarketRegime(
                RegimeKind.AI_CONSOLIDATION,
                "Selective Rotation / AI Consolidation",
                "Selective rotation / AI consolidation",
                "Semis พักตัวขณะ software ยังเด่น — money หมุนภายใน AI complex",
                signals,
            )
            breadth > 0.75 && spy > 0.6 && spy20 > 6.0 -> MarketRegime(
                RegimeKind.CROWDED_EUPHORIC,
                "Crowded Euphoric",
                "Crowded euphoric",
                "ทุก sector เขียวหนา + SPY 20D สูง — ระวัง distribution ลึก",
                signals,
            )
            breadth > 0.70 && spy > 0.3 -> MarketRegime(
                RegimeKind.BROAD_MOMENTUM,
                "Broad Momentum",
                "Broad momentum",
                "Breadth กว้าง + SPY เขียวชัด — risk-on จริง ไม่เลือก sector",
                signals,
            )
            else -> MarketRegime(
                RegimeKind.SELECTIVE_ROTATION,
                "Selective Rotation",
                "Selective rotation",
                "Breadth ปานกลาง, leader-laggard spread สูง — ต้องคัดตัวด้วย RS",
                signals,
            )
        }
    }

    // ----- Futures / yields -------------------------------------------------

    private fun buildFuturesYields(quotes: Map<String, Quote>): List<FuturesYieldRow> =
        futuresMacroSymbols.mapNotNull { (sym, name) ->
            val q = quotes[sym] ?: return@mapNotNull null
            val pct = q.pctChange
            val interp = when (sym) {
                "ES=F", "NQ=F", "YM=F" -> if (pct >= 0.3) "Bullish bias — risk-on tone"
                else if (pct >= 0.0) "Slightly bullish — wait for confirmation"
                else if (pct >= -0.3) "Slightly soft — watch for downside follow-through"
                else "Bearish bias — risk-off open likely"
                "^TNX" -> if (pct <= -0.5) "Yields ลง → growth/AI ได้แต้มต่อ"
                else if (pct >= 0.8) "Yields พุ่ง → growth/AI โดนกด"
                else "Yields ใกล้ neutral"
                "DX-Y.NYB" -> if (pct >= 0.3) "Dollar แข็ง → ตลาดเสี่ยงโดนกด"
                else if (pct <= -0.3) "Dollar อ่อน → tailwind risk asset"
                else "DXY neutral"
                "^VIX" -> if (pct >= 5.0) "VIX bid — ระวัง vol spike"
                else if (pct <= -3.0) "VIX อ่อน — vol นิ่ง"
                else "VIX neutral"
                "CL=F" -> if (pct >= 1.5) "Oil เด่น → energy bid"
                else if (pct <= -1.5) "Oil ร่วง → growth อาจได้"
                else "Oil neutral"
                else -> ""
            }
            FuturesYieldRow(name, name, pct, interp)
        }

    // ----- 2. Sector flow ---------------------------------------------------

    private fun readSectorFlow(symbol: String, q: Quote): String {
        val todayPct = q.pctChange
        val fiveDay = q.fiveDayReturn
        val twentyDay = q.twentyDayReturn
        val relVol = q.relVolume

        val baseTrend = when {
            fiveDay > 3.0 && twentyDay > 5.0 -> "ทิศทาง trend ขึ้นชัดทั้ง 5D และ 20D"
            fiveDay > 1.5 -> "5D positive — โมเมนตัมกำลังก่อตัว"
            fiveDay < -3.0 -> "5D ติดลบหนัก — sector อ่อนตัวจริง"
            fiveDay < -1.5 -> "5D อ่อน — flow ออกต่อเนื่อง"
            else -> "5D นิ่ง — ยังไม่มี directional flow"
        }
        val todayLabel = when {
            todayPct > 1.0 -> "วันนี้ bid หนัก (${pct(todayPct)})"
            todayPct > 0.3 -> "วันนี้บวกเบาๆ"
            todayPct < -1.0 -> "วันนี้ขายแรง (${pct(todayPct)})"
            else -> "วันนี้ flat"
        }
        val vol = if (relVol >= 1.5) " · volume เด่น ${"%.2f".format(relVol)}x"
        else if (relVol in 0.0..0.6) " · volume เบา ${"%.2f".format(relVol)}x"
        else ""
        return "$todayLabel · $baseTrend$vol"
    }

    // ----- 3. Leader read ---------------------------------------------------

    private fun buildLeaderRead(
        ticker: Ticker,
        quotes: Map<String, Quote>,
        session: MarketSession,
        regime: RegimeKind,
    ): LeaderRead? {
        val q = quotes[ticker.symbol] ?: return null
        val spyPct = quotes["SPY"]?.pctChange ?: 0.0
        val sessionPct = q.sessionPct(session)
        val rs = sessionPct - spyPct
        val twentyD = q.twentyDayReturn

        // Sustainability heuristic — RS sign + volume confirm + sector confirm.
        val sectorMomentum = when (ticker.theme) {
            TickerTheme.SEMIS -> quotes["SMH"]?.pctChange ?: 0.0
            TickerTheme.POWER -> quotes["XLU"]?.pctChange ?: 0.0
            TickerTheme.SOFTWARE -> quotes["XLK"]?.pctChange ?: 0.0
        }
        val sustainable = when {
            rs > 0.5 && q.relVolume >= 1.3 && sectorMomentum > 0 ->
                "ของจริง — RS + volume + sector ยืนยัน"
            rs > 0.0 && q.relVolume in 0.7..1.3 ->
                "บวกเฉยๆ — รอ volume กลับเข้ามาก่อน"
            rs > 0.0 && sectorMomentum < 0 ->
                "ระวัง trap — RS บวกแต่ sector ไม่เอา"
            rs < 0.0 && q.relVolume >= 1.3 ->
                "อ่อนพร้อม volume — distribution กำลังเกิด"
            else -> "Neutral — ยังไม่มี edge ชัด"
        }

        val crowding = when {
            twentyD >= 15.0 && q.relVolume < 0.9 ->
                "Crowded + extended 20D ${pct(twentyD)} แต่ volume แผ่ว — เสี่ยง distribution"
            twentyD >= 10.0 -> "ค่อนข้าง extended (20D ${pct(twentyD)}) — เข้า pullback only"
            twentyD <= -5.0 && rs > 0.5 -> "Under-owned / reversal — flow เพิ่งกลับ"
            twentyD in -2.0..3.0 && q.relVolume >= 1.5 -> "Base breakout potential — under-owned"
            else -> "Positioning ปกติ"
        }

        val narrative = when (ticker.theme) {
            TickerTheme.SEMIS -> when (regime) {
                RegimeKind.AI_ACCELERATION, RegimeKind.AI_CONSOLIDATION -> "Core GPU/AI infra"
                RegimeKind.POWER_BOTTLENECK -> "Semis รอง power rotation"
                else -> "Semis cycle"
            }
            TickerTheme.POWER -> when (regime) {
                RegimeKind.POWER_BOTTLENECK -> "Power/cooling bottleneck — bid"
                RegimeKind.DEFENSIVE_FLOW -> "Defensive utility flow"
                else -> "Power/grid theme"
            }
            TickerTheme.SOFTWARE -> when (regime) {
                RegimeKind.AI_ACCELERATION -> "AI monetization layer"
                RegimeKind.AI_CONSOLIDATION -> "Software เด่นกว่า semis ในวันนี้"
                else -> "Software / AI monetization"
            }
        }

        val rsRead = when {
            rs >= 1.0 -> "Top-decile RS (${pct(rs)} vs SPY)"
            rs >= 0.3 -> "RS positive (${pct(rs)})"
            rs <= -1.0 -> "RS อ่อนชัด (${pct(rs)})"
            else -> "RS neutral (${pct(rs)})"
        }
        val liquidity = when {
            q.relVolume >= 2.0 -> "Liquidity แรงมาก ${"%.2f".format(q.relVolume)}x"
            q.relVolume >= 1.2 -> "Liquidity คุณภาพดี ${"%.2f".format(q.relVolume)}x"
            q.relVolume >= 0.6 -> "Liquidity ปกติ ${"%.2f".format(q.relVolume)}x"
            else -> "Liquidity เบา ${"%.2f".format(q.relVolume)}x — ระวัง spread"
        }
        val catalyst = when (session) {
            MarketSession.PREMARKET ->
                if (q.premarketPct != null && abs(q.premarketPct) >= 1.0)
                    "Pre-market move แรง ${pct(q.premarketPct)} — ต้องมีข่าวรองรับ"
                else "ไม่มี catalyst แรง — wait for confirmation"
            MarketSession.REGULAR ->
                if (q.relVolume >= 1.5) "Volume catalyst ${"%.2f".format(q.relVolume)}x"
                else "ไม่มี catalyst แรง — รอ setup ที่ชัด"
            else -> "Session ไม่ใช่ trading hours"
        }
        val institutional = when {
            rs >= 0.5 && q.relVolume >= 1.5 -> "Institutional accumulation ชัดเจน"
            rs <= -0.5 && q.relVolume >= 1.5 -> "Institutional distribution"
            q.relVolume < 0.6 -> "Retail-only flow — institutions ยังไม่เข้า"
            else -> "Flow แบบ mixed — ยังไม่มี institutional signature ชัด"
        }

        return LeaderRead(
            ticker = ticker,
            sessionPct = sessionPct,
            regularPct = q.pctChange,
            rsVsSpy = rs,
            relVolume = q.relVolume,
            twentyDayReturn = twentyD,
            narrativeTh = narrative,
            relativeStrengthTh = rsRead,
            liquidityTh = liquidity,
            catalystTh = catalyst,
            institutionalTh = institutional,
            sustainableTh = sustainable,
            crowdingTh = crowding,
        )
    }

    // ----- 4. Rotation -------------------------------------------------------

    private fun detectRotation(sectors: List<SectorFlowRow>, regime: RegimeKind): NarrativeRotation {
        val byFiveDay = sectors.sortedByDescending { it.fiveDayReturn }
        val top = byFiveDay.firstOrNull()
        val bottom = byFiveDay.lastOrNull()
        if (top == null || bottom == null || top == bottom) {
            return NarrativeRotation(
                arrowTh = "ยังไม่ชัด",
                arrowEn = "No clear rotation",
                whyTh = "Sector spread แคบ — ตลาดยังไม่ตัดสินใจ",
                stageTh = "—",
                driverTh = "—",
            )
        }
        val arrow = "${arrowFor(bottom.symbol)} → ${arrowFor(top.symbol)}"
        val why = "ใน 5 วัน ${top.symbol} ทำ ${pct(top.fiveDayReturn)} ขณะ ${bottom.symbol} " +
            "ทำ ${pct(bottom.fiveDayReturn)} — money เปลี่ยนทิศ"
        val stage = when {
            abs(top.fiveDayReturn - bottom.fiveDayReturn) > 8.0 -> "Late — rotation เต็มที่"
            abs(top.fiveDayReturn - bottom.fiveDayReturn) > 4.0 -> "Mid — กำลังลาก trend"
            else -> "Early — เพิ่งเริ่ม rotation"
        }
        val driver = when (regime) {
            RegimeKind.POWER_BOTTLENECK, RegimeKind.AI_ACCELERATION ->
                "Institutional — narrative driver ชัด"
            RegimeKind.DEFENSIVE_FLOW, RegimeKind.RISK_OFF, RegimeKind.LIQUIDITY_SQUEEZE ->
                "Institutional — risk reduction"
            RegimeKind.CROWDED_EUPHORIC -> "Mixed — late retail chasing"
            else -> "Mixed flow"
        }
        return NarrativeRotation(arrow, arrow, why, stage, driver)
    }

    private fun arrowFor(symbol: String): String = when (symbol) {
        "SMH" -> "Semis"
        "XLK" -> "Tech"
        "XLC" -> "Comms"
        "XLU" -> "Power"
        "XLE" -> "Energy"
        "XLF" -> "Financials"
        "XLI" -> "Industrials"
        "XLY" -> "ConsDisc"
        "XLV" -> "Healthcare"
        "XLP" -> "Staples"
        else -> symbol
    }

    // ----- 5. Expectation vs reality ---------------------------------------

    private fun readExpectation(
        tickers: List<Ticker>,
        quotes: Map<String, Quote>,
        sectorRows: List<SectorFlowRow>,
    ): ExpectationRead {
        val crowded = tickers.mapNotNull { t ->
            val q = quotes[t.symbol] ?: return@mapNotNull null
            if (q.twentyDayReturn >= 12.0 && q.relVolume < 1.0) {
                "${t.symbol}: 20D ${pct(q.twentyDayReturn)} แต่ volume วันนี้ ${"%.2f".format(q.relVolume)}x — เริ่ม distribution"
            } else null
        }
        val underOwned = tickers.mapNotNull { t ->
            val q = quotes[t.symbol] ?: return@mapNotNull null
            if (q.twentyDayReturn <= 3.0 && q.relVolume >= 1.4 && q.pctChange > 0.5) {
                "${t.symbol}: 20D ${pct(q.twentyDayReturn)} + volume ${"%.2f".format(q.relVolume)}x — under-owned breakout setup"
            } else null
        }
        val topSector = sectorRows.maxByOrNull { it.pctChange }
        val bottomSector = sectorRows.minByOrNull { it.pctChange }
        val gap = if (topSector != null && bottomSector != null)
            "Sector spread วันนี้ = ${pct(topSector.pctChange - bottomSector.pctChange)} (${topSector.symbol} > ${bottomSector.symbol}) — ${
                if (topSector.pctChange - bottomSector.pctChange > 1.5) "ตลาดเลือก leader ชัด"
                else "ตลาดยังไม่ตัดสินใจ"
            }"
        else "ข้อมูล sector ไม่ครบ"
        return ExpectationRead(crowded, underOwned, gap)
    }

    // ----- 6. Opening watchlist (max 3) ------------------------------------

    private fun buildOpeningWatchlist(
        tickers: List<Ticker>,
        quotes: Map<String, Quote>,
        session: MarketSession,
        regime: RegimeKind,
        sectorRows: List<SectorFlowRow>,
    ): List<WatchlistEntry> {
        // Score = momentum(session) + RS + volBonus + narrative + sector-confirm
        val spy = quotes["SPY"]?.pctChange ?: 0.0
        val scored = tickers.mapNotNull { t ->
            val q = quotes[t.symbol] ?: return@mapNotNull null
            val sp = q.sessionPct(session)
            val rs = sp - spy
            val volBonus = ((q.relVolume - 1.0).coerceIn(-1.0, 4.0)) * 5.0
            val narrative = narrativeWeight(t.theme, regime)
            val sectorSym = sectorSymbolFor(t.theme)
            val sectorMomentum = sectorRows.firstOrNull { it.symbol == sectorSym }?.pctChange ?: 0.0
            val sectorConfirm = if (sectorMomentum > 0) 4.0 else -2.0
            // Penalise crowded extended names — institutional money avoids them on entry.
            val crowdPenalty = when {
                q.twentyDayReturn >= 18.0 && q.relVolume < 1.0 -> -6.0
                q.twentyDayReturn >= 12.0 && q.relVolume < 0.8 -> -3.0
                else -> 0.0
            }
            val score = sp * 7.0 + rs * 6.0 + volBonus + narrative + sectorConfirm + crowdPenalty
            Triple(t, q, score)
        }.sortedByDescending { it.third }

        return scored.take(3).map { (t, q, _) ->
            val sectorMomentum = sectorRows.firstOrNull { it.symbol == sectorSymbolFor(t.theme) }?.pctChange ?: 0.0
            val rs = q.sessionPct(session) - spy
            val entryStyle = when {
                rs > 1.0 && q.relVolume >= 1.5 ->
                    "ซื้อ pullback ไป VWAP/9EMA reclaim — volume ต้อง confirm กลับเข้ามา"
                rs > 0.3 ->
                    "Pullback only — รอ first 5-min ผ่านก่อน, เข้าหลัง 09:35 ET"
                else ->
                    "ห้ามเข้า breakout — ใช้แค่ relative-strength reclaim เป็น trigger"
            }
            val invalidates = buildList {
                if (sectorMomentum > 0) add("Sector ETF (${sectorSymbolFor(t.theme)}) กลับเป็นแดง")
                add("VWAP ขาดและไม่กลับใน 1-2 แท่ง")
                if (q.twentyDayReturn >= 10.0) add("Volume เบาในขณะที่ราคา push สูงขึ้น (distribution)")
                if (regime == RegimeKind.AI_ACCELERATION || regime == RegimeKind.AI_CONSOLIDATION)
                    add("US10Y พุ่งกว่า +1% intraday")
            }.joinToString("; ")

            val confirms = buildList {
                add("VWAP reclaim พร้อม volume กลับเข้า ≥ 1.5x")
                add("Sector ETF ${sectorSymbolFor(t.theme)} เขียวพร้อมกัน")
                if (regime == RegimeKind.POWER_BOTTLENECK)
                    add("XLU ทำ HOD พร้อม yields ไม่ตึง")
                if (regime == RegimeKind.AI_ACCELERATION)
                    add("SMH เด่นชัด และ US10Y ไม่พุ่ง")
            }.joinToString("; ")

            val why = "${t.symbol} ${narrativeReason(t.theme, regime)} · " +
                "RS ${pct(rs)} · RelVol ${"%.2f".format(q.relVolume)}x · 20D ${pct(q.twentyDayReturn)}"
            val institutional = when {
                q.relVolume >= 1.8 && rs >= 0.7 ->
                    "Block-style buying: volume สูง + RS นำตลาด → institutions เข้า"
                q.relVolume >= 1.2 && rs >= 0.3 ->
                    "Steady accumulation — volume สม่ำเสมอ, ราคาไม่ฝืน"
                else ->
                    "ยังไม่เห็น signature institutional ชัด — รอ confirmation"
            }
            WatchlistEntry(
                ticker = t,
                whyTh = why,
                confirmsTh = confirms,
                invalidatesTh = invalidates,
                institutionalLooksLikeTh = institutional,
                entryStyleTh = entryStyle,
            )
        }
    }

    private fun narrativeReason(theme: TickerTheme, regime: RegimeKind): String = when (theme) {
        TickerTheme.SEMIS -> when (regime) {
            RegimeKind.AI_ACCELERATION -> "Core AI infra leader"
            RegimeKind.AI_CONSOLIDATION -> "Selective AI rotation candidate"
            RegimeKind.POWER_BOTTLENECK -> "Semis รอง — รอ leader pause"
            else -> "Semis cycle"
        }
        TickerTheme.POWER -> when (regime) {
            RegimeKind.POWER_BOTTLENECK -> "Power bottleneck — ยังมี room"
            RegimeKind.DEFENSIVE_FLOW -> "Defensive bid — utility flow"
            else -> "Power/grid narrative"
        }
        TickerTheme.SOFTWARE -> when (regime) {
            RegimeKind.AI_ACCELERATION -> "AI monetization layer"
            RegimeKind.AI_CONSOLIDATION -> "Software เด่นกว่า semis"
            else -> "AI monetization theme"
        }
    }

    private fun sectorSymbolFor(theme: TickerTheme): String = when (theme) {
        TickerTheme.SEMIS -> "SMH"
        TickerTheme.POWER -> "XLU"
        TickerTheme.SOFTWARE -> "XLK"
    }

    private fun narrativeWeight(theme: TickerTheme, regime: RegimeKind): Double = when (regime) {
        RegimeKind.AI_ACCELERATION -> when (theme) {
            TickerTheme.SEMIS -> 9.0
            TickerTheme.SOFTWARE -> 4.0
            TickerTheme.POWER -> 2.0
        }
        RegimeKind.AI_CONSOLIDATION -> when (theme) {
            TickerTheme.SOFTWARE -> 8.0
            TickerTheme.SEMIS -> 3.0
            TickerTheme.POWER -> 2.0
        }
        RegimeKind.POWER_BOTTLENECK -> when (theme) {
            TickerTheme.POWER -> 10.0
            TickerTheme.SEMIS -> 2.0
            TickerTheme.SOFTWARE -> 0.5
        }
        RegimeKind.DEFENSIVE_FLOW -> when (theme) {
            TickerTheme.POWER -> 5.0
            else -> -2.0
        }
        RegimeKind.BROAD_MOMENTUM -> 5.0
        RegimeKind.SELECTIVE_ROTATION -> 1.0
        RegimeKind.CROWDED_EUPHORIC -> -3.0
        RegimeKind.FRAGILE, RegimeKind.RISK_OFF, RegimeKind.LIQUIDITY_SQUEEZE -> -8.0
    }

    // ----- 7. Avoid list ----------------------------------------------------

    private fun buildAvoidList(
        tickers: List<Ticker>,
        quotes: Map<String, Quote>,
        sectorRows: List<SectorFlowRow>,
        regime: RegimeKind,
    ): List<AvoidEntry> {
        val list = mutableListOf<AvoidEntry>()
        tickers.forEach { t ->
            val q = quotes[t.symbol] ?: return@forEach
            val sectorMomentum = sectorRows.firstOrNull {
                it.symbol == sectorSymbolFor(t.theme)
            }?.pctChange ?: 0.0
            when {
                q.twentyDayReturn >= 18.0 && q.relVolume < 1.0 ->
                    list += AvoidEntry(
                        t.symbol,
                        "Extended 20D ${pct(q.twentyDayReturn)} + volume เบา ${"%.2f".format(q.relVolume)}x — เสี่ยง distribution",
                    )
                q.twentyDayReturn >= 12.0 && q.relVolume < 0.7 ->
                    list += AvoidEntry(
                        t.symbol,
                        "Crowded + volume แห้ง — รอ pullback ลึกเท่านั้น",
                    )
                q.pctChange > 0.5 && sectorMomentum < -0.5 ->
                    list += AvoidEntry(
                        t.symbol,
                        "ราคาบวกขณะ sector แดง — divergence, เสี่ยง fakeout",
                    )
                regime == RegimeKind.RISK_OFF || regime == RegimeKind.LIQUIDITY_SQUEEZE ->
                    if (t.theme == TickerTheme.SEMIS && q.pctChange > 0.3)
                        list += AvoidEntry(
                            t.symbol,
                            "Semis bounce ในตลาด risk-off — likely dead-cat",
                        )
            }
        }
        return list.distinctBy { it.symbol }.take(4)
    }

    // ----- Final conclusion -------------------------------------------------

    private fun buildFinalConclusion(
        regime: MarketRegime,
        sectorRows: List<SectorFlowRow>,
        leaders: List<LeaderRead>,
    ): FinalConclusion {
        val dayTypeTh: String
        val dayTypeEn: String
        when (regime.kind) {
            RegimeKind.BROAD_MOMENTUM -> { dayTypeTh = "Broad momentum"; dayTypeEn = "Broad momentum" }
            RegimeKind.SELECTIVE_ROTATION -> { dayTypeTh = "Selective rotation"; dayTypeEn = "Selective rotation" }
            RegimeKind.AI_ACCELERATION -> { dayTypeTh = "AI acceleration — selective"; dayTypeEn = "Selective rotation (AI)" }
            RegimeKind.AI_CONSOLIDATION -> { dayTypeTh = "AI consolidation — rotation ภายใน complex"; dayTypeEn = "Selective rotation (AI consolidation)" }
            RegimeKind.POWER_BOTTLENECK -> { dayTypeTh = "Selective rotation (Power)"; dayTypeEn = "Selective rotation (Power)" }
            RegimeKind.DEFENSIVE_FLOW -> { dayTypeTh = "Defensive"; dayTypeEn = "Defensive" }
            RegimeKind.CROWDED_EUPHORIC -> { dayTypeTh = "Crowded euphoric"; dayTypeEn = "Crowded euphoric" }
            RegimeKind.FRAGILE -> { dayTypeTh = "Fragile"; dayTypeEn = "Fragile" }
            RegimeKind.LIQUIDITY_SQUEEZE -> { dayTypeTh = "Risk-off / Squeeze"; dayTypeEn = "Liquidity squeeze" }
            RegimeKind.RISK_OFF -> { dayTypeTh = "Risk-off"; dayTypeEn = "Risk-off" }
        }
        val topSector = sectorRows.maxByOrNull { it.pctChange }
        val dominantFlow = topSector?.let {
            "เงินไหลเข้า ${arrowFor(it.symbol)} (${pct(it.pctChange)}) — เป็น sector ที่นำตลาดวันนี้"
        } ?: "Sector flow ยังไม่ชัด"
        val dominantNarrative = when (regime.kind) {
            RegimeKind.AI_ACCELERATION -> "AI infrastructure — semis เป็น engine"
            RegimeKind.POWER_BOTTLENECK -> "Power / cooling — bottleneck ของ AI cycle"
            RegimeKind.AI_CONSOLIDATION -> "AI monetization — money หมุนเข้า software / hyperscalers"
            RegimeKind.DEFENSIVE_FLOW -> "Defensive — utilities / staples / healthcare"
            RegimeKind.CROWDED_EUPHORIC -> "Late-cycle chasing — ระวัง mean reversion"
            RegimeKind.RISK_OFF, RegimeKind.LIQUIDITY_SQUEEZE -> "De-risking — institutions ลด exposure"
            else -> "Mixed — leader rotates rapidly"
        }
        val institutionalBehavior = when (regime.kind) {
            RegimeKind.AI_ACCELERATION -> "Add to AI infra leaders on pullbacks"
            RegimeKind.POWER_BOTTLENECK -> "Initiate / add power names (VRT/CEG/VST) on dips"
            RegimeKind.AI_CONSOLIDATION -> "Trim semis extended, rotate to software / power"
            RegimeKind.DEFENSIVE_FLOW -> "Rotate to utilities + healthcare, cut growth"
            RegimeKind.BROAD_MOMENTUM -> "Buy index, broad participation"
            RegimeKind.SELECTIVE_ROTATION -> "Stock-picking — RS ranking matters"
            RegimeKind.CROWDED_EUPHORIC -> "Take profits, raise cash"
            RegimeKind.FRAGILE -> "Hedge with VIX/puts, reduce size"
            RegimeKind.RISK_OFF, RegimeKind.LIQUIDITY_SQUEEZE -> "Cash + treasuries — preserve capital"
        }
        return FinalConclusion(
            dayTypeTh = dayTypeTh,
            dayTypeEn = dayTypeEn,
            dominantFlowTh = dominantFlow,
            dominantNarrativeTh = dominantNarrative,
            likelyInstitutionalBehaviorTh = institutionalBehavior,
        )
    }

    // ----- Rules / routine / mantra ----------------------------------------

    private fun rulesFor(regime: RegimeKind, session: MarketSession): List<String> {
        val core = listOf(
            "อย่าซื้อแท่งเขียวแรก — รอ pullback + VWAP reclaim",
            "ถือ leader ตัวเดียว ไม่กระจาย",
            "Stop = ใต้ VWAP หรือ pullback low (เลือกที่ตึงกว่า)",
            "ถ้า thesis เสีย (yields พุ่ง / sector ETF แดง) — ออกทันที",
        )
        val sessionSpecific = when (session) {
            MarketSession.PREMARKET -> listOf(
                "Pre-market: ต้องมี volume + ข่าว catalyst ของจริง",
                "ห้ามเข้า pre-market ถ้า spread > 0.5% หรือ liquidity บาง",
            )
            MarketSession.REGULAR -> listOf(
                "5 นาทีแรก: ห้ามเข้า, แค่ดู ranking RS",
                "Entry หลัง 09:35 ET เมื่อ VWAP reclaim + volume กลับ",
                "Lunch chop (12:00–13:30 ET): ลด size 50%",
            )
            MarketSession.POSTMARKET -> listOf(
                "Post-market: เน้น earnings movers, ลด size, spread กว้าง",
            )
            MarketSession.CLOSED -> listOf(
                "ตลาดปิด — เตรียม watchlist + อ่าน narrative",
                "Sunday futures เปิด 18:00 ET — เริ่ม monitor NQ และ US10Y",
            )
        }
        val regimeSpecific = when (regime) {
            RegimeKind.AI_ACCELERATION ->
                listOf("Focus AI infra leaders, รอ pullback ลึกพอ — ห้าม chase")
            RegimeKind.POWER_BOTTLENECK ->
                listOf("VRT/CEG/VST — ต้องเห็น XLU เขียว + volume ≥ 2x บน leader")
            RegimeKind.AI_CONSOLIDATION ->
                listOf("Trim semis extended, rotate to software / power names")
            RegimeKind.DEFENSIVE_FLOW ->
                listOf("Lean defensive (XLU/XLV/XLP), หลีกเลี่ยง high-beta growth")
            RegimeKind.BROAD_MOMENTUM ->
                listOf("Index follow-through OK, แต่ยังเล่น leader ตัวเดียว")
            RegimeKind.SELECTIVE_ROTATION ->
                listOf("Stock-picking by RS — A+ setup เท่านั้น")
            RegimeKind.CROWDED_EUPHORIC ->
                listOf("ลด size, take profits, อย่าเพิ่ม risk ใหม่")
            RegimeKind.FRAGILE ->
                listOf("Hedge, cut size 50%, รอ vol cluster แตก")
            RegimeKind.LIQUIDITY_SQUEEZE, RegimeKind.RISK_OFF ->
                listOf("ถือเงินสด หรือ short ETF เป็น hedge เท่านั้น")
        }
        return core + sessionSpecific + regimeSpecific
    }

    private fun routineFor(): List<RoutineStep> = listOf(
        RoutineStep("Sun 18:00 ET", "เปิด futures + macro", "Sunday futures open",
            "เช็ค NQ/ES/DXY/US10Y → bias ของสัปดาห์"),
        RoutineStep("04:00 ET", "Pre-market เริ่ม", "Pre-market opens",
            "Finviz heatmap, ข่าว overnight, premarket movers (volume + gap)"),
        RoutineStep("08:30 ET", "Macro release (ถ้ามี)", "Macro release",
            "CPI/NFP/Retail — ดูปฏิกิริยา 10Y/DXY ก่อนตัดสินใจ"),
        RoutineStep("09:25 ET", "Lock watchlist", "Lock watchlist",
            "ตัดเหลือ 3 ชื่อ + trigger ของแต่ละตัว"),
        RoutineStep("09:30 ET", "ตลาดเปิด — ห้ามเข้า 5 นาทีแรก", "Open — no entry 5 min",
            "ดู RS, รอ VWAP reclaim + volume confirm บน leader"),
        RoutineStep("09:45 – 10:30 ET", "Prime entry window", "Prime entry window",
            "Pullback → VWAP hold → volume กลับ → enter Tier S"),
        RoutineStep("12:00 – 13:30 ET", "Lunch chop — ลด size", "Lunch chop",
            "False breakouts เยอะ — ลด size 50% หรือถือ winner เฉยๆ"),
        RoutineStep("15:00 – 16:00 ET", "Power hour", "Power hour",
            "Leader ทำ HOD หรือ fail? ตัดสินใจถือข้ามคืนหรือ flat"),
        RoutineStep("16:00 – 20:00 ET", "Post-market — earnings", "Post-market",
            "Earnings movers เท่านั้น, ลด size, spread กว้าง"),
    )

    private fun mantraFor(regime: RegimeKind): String = when (regime) {
        RegimeKind.AI_ACCELERATION -> "AI infra วิ่งต่อ — เล่นตัวนำ ไม่เล่นทุกตัว"
        RegimeKind.POWER_BOTTLENECK -> "Bottleneck กำลัง shift จาก GPU → Power. ตามเงิน อย่าตามข่าวเก่า"
        RegimeKind.AI_CONSOLIDATION -> "Semis พัก = software/power ได้เวลา"
        RegimeKind.BROAD_MOMENTUM -> "ตลาดดี ก็ยังต้องเลือก leader ตัวเดียว"
        RegimeKind.SELECTIVE_ROTATION -> "เลือกตัวนำ A+ เท่านั้น Cash is a position."
        RegimeKind.DEFENSIVE_FLOW -> "เงินไหลออกจาก growth — อย่าค้าน flow"
        RegimeKind.CROWDED_EUPHORIC -> "Take profits — euphoria คือ exit"
        RegimeKind.FRAGILE -> "Vol bid + breadth แคบ = ลด size"
        RegimeKind.RISK_OFF, RegimeKind.LIQUIDITY_SQUEEZE -> "Cash is a position. ไม่ต้องเล่นทุกวัน"
    }

    private fun pct(v: Double): String = if (v >= 0) "+%.2f%%".format(v) else "%.2f%%".format(v)
}
