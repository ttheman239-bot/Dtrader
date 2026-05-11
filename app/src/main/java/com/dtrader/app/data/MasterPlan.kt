package com.dtrader.app.data

/**
 * Output of the "what would a master trader do today" calculation.
 *
 * Session-aware: pulls in pre-market / regular / post-market metrics from
 * the same Yahoo quote feed, scores every name in the watchlist by a
 * conviction algorithm, and prescribes specific entry rules for the
 * detected regime.
 *
 * Headlines / details are bilingual (Thai primary, English secondary).
 */

enum class Bias(val labelEn: String, val labelTh: String) {
    AI_INFRA("AI Infra continuation", "AI Infrastructure วิ่งต่อ"),
    POWER("Power / cooling rotation", "หมุนเข้าฝั่ง Power / Cooling"),
    RISK_ON("Broad risk-on", "ตลาดเปิดรับเสี่ยง"),
    MIXED("Mixed tape — pick the leader", "ตลาดผสม — เลือก leader"),
    RISK_OFF("Risk-off — stand aside", "Risk-off — ถือเงินสดดีกว่า"),
}

data class BiasReading(
    val bias: Bias,
    val headlineTh: String,
    val headlineEn: String,
    val detailTh: String,
    val detailEn: String,
    val signals: List<String>,
)

data class PickReading(
    val ticker: Ticker,
    val score: Double,
    val sessionPct: Double,
    val regularPct: Double,
    val rsVsSpy: Double,
    val relVolume: Double,
    val reasonsTh: List<String>,
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
    val bias: BiasReading,
    val tierS: List<PickReading>,
    val tierA: List<PickReading>,
    val sessionMovers: List<PickReading>,
    val rulesTh: List<String>,
    val routine: List<RoutineStep>,
    val mantraTh: String,
    val asOfMillis: Long,
)

class MasterPlanRepository(private val api: QuoteApi = QuoteApi()) {

    private val benchmarks = listOf("SPY", "QQQ", "SMH", "XLU", "XLK", "XLE", "XLF", "^VIX", "^TNX", "DX-Y.NYB")

    suspend fun build(
        tickers: List<Ticker>,
        session: MarketSession = SessionDetector.current(),
    ): MasterPlan {
        val symbols = (benchmarks + tickers.map { it.symbol }).distinct()
        val quotes = api.fetchAll(symbols)

        val bias = inferBias(quotes)
        val picks = tickers.mapNotNull { t -> scorePick(t, quotes, session, bias.bias) }
            .sortedByDescending { it.score }

        val tierS = picks.take(3)
        val tierA = picks.drop(3).take(3)
        val sessionMovers = picks
            .filter { it.sessionPct.let { p -> p > 0.3 || p < -0.3 } }
            .sortedByDescending { it.sessionPct }
            .take(6)

        return MasterPlan(
            session = session,
            nyTimestamp = SessionDetector.timestamp(),
            bias = bias,
            tierS = tierS,
            tierA = tierA,
            sessionMovers = sessionMovers,
            rulesTh = rulesFor(bias.bias, session),
            routine = routineFor(session),
            mantraTh = mantraFor(bias.bias),
            asOfMillis = System.currentTimeMillis(),
        )
    }

    // ----- Scoring -----------------------------------------------------------

    private fun scorePick(
        ticker: Ticker,
        quotes: Map<String, Quote>,
        session: MarketSession,
        bias: Bias,
    ): PickReading? {
        val q = quotes[ticker.symbol] ?: return null
        val spyPct = quotes["SPY"]?.pctChange ?: 0.0
        val sessionPct = q.sessionPct(session)
        val regularPct = q.pctChange
        val rs = sessionPct - spyPct

        val momentum = sessionPct * 8.0
        val rsScore = rs * 6.0
        val volBonus = ((q.relVolume - 1.0).coerceIn(-1.0, 4.0)) * 5.0
        val narrative = narrativeWeight(ticker.theme, bias)
        val score = momentum + rsScore + volBonus + narrative

        val reasons = buildList {
            add("Session %: ${pct(sessionPct)} (RS ${pct(rs)} vs SPY)")
            if (q.relVolume >= 1.5) add("Volume แรง ${"%.2f".format(q.relVolume)}x ของค่าเฉลี่ย")
            else if (q.relVolume in 0.0..0.6) add("Volume เบา ${"%.2f".format(q.relVolume)}x — รอ confirm")
            add(themeNarrative(ticker.theme, bias))
        }

        return PickReading(
            ticker = ticker,
            score = score,
            sessionPct = sessionPct,
            regularPct = regularPct,
            rsVsSpy = rs,
            relVolume = q.relVolume,
            reasonsTh = reasons,
        )
    }

    private fun narrativeWeight(theme: TickerTheme, bias: Bias): Double = when (bias) {
        Bias.AI_INFRA -> when (theme) {
            TickerTheme.SEMIS -> 8.0
            TickerTheme.SOFTWARE -> 3.0
            TickerTheme.POWER -> 2.0
        }
        Bias.POWER -> when (theme) {
            TickerTheme.POWER -> 9.0
            TickerTheme.SEMIS -> 1.5
            TickerTheme.SOFTWARE -> 0.0
        }
        Bias.RISK_ON -> when (theme) {
            TickerTheme.SEMIS -> 4.0
            TickerTheme.SOFTWARE -> 4.0
            TickerTheme.POWER -> 3.0
        }
        Bias.MIXED -> 0.0
        Bias.RISK_OFF -> -4.0
    }

    private fun themeNarrative(theme: TickerTheme, bias: Bias): String = when (theme) {
        TickerTheme.SEMIS -> when (bias) {
            Bias.AI_INFRA -> "AI Infra เป็น narrative หลัก — semis ได้แรงหนุน"
            Bias.POWER -> "Semis เป็นรอง power rotation วันนี้"
            else -> "Semis อยู่ใน core AI playbook"
        }
        TickerTheme.POWER -> when (bias) {
            Bias.POWER -> "Power/Cooling เป็น bottleneck ของ AI cycle — flow ไหลเข้า"
            Bias.AI_INFRA -> "ตามรอบ — semis นำ, power follow"
            else -> "Watch for sector rotation confirm"
        }
        TickerTheme.SOFTWARE -> when (bias) {
            Bias.RISK_ON -> "Software monetization theme รอ trigger"
            else -> "AI monetization — รอ catalyst เฉพาะตัว"
        }
    }

    // ----- Bias inference ---------------------------------------------------

    private fun inferBias(quotes: Map<String, Quote>): BiasReading {
        val spy = quotes["SPY"]?.pctChange
        val qqq = quotes["QQQ"]?.pctChange
        val smh = quotes["SMH"]?.pctChange
        val xlu = quotes["XLU"]?.pctChange
        val xle = quotes["XLE"]?.pctChange
        val vix = quotes["^VIX"]?.pctChange
        val tnx = quotes["^TNX"]?.pctChange
        val dxy = quotes["DX-Y.NYB"]?.pctChange

        val signals = buildList {
            spy?.let { add("SPY ${pct(it)}") }
            qqq?.let { add("QQQ ${pct(it)}") }
            smh?.let { add("SMH ${pct(it)}") }
            xlu?.let { add("XLU ${pct(it)}") }
            xle?.let { add("XLE ${pct(it)}") }
            vix?.let { add("VIX ${pct(it)}") }
            tnx?.let { add("US10Y ${pct(it)}") }
            dxy?.let { add("DXY ${pct(it)}") }
        }

        val riskOff = (spy ?: 0.0) < -0.4 || (vix ?: 0.0) > 5.0
        val aiLeading = (smh ?: -99.0) > (spy ?: 0.0) + 0.25 && (tnx ?: 99.0) <= 0.5
        val powerLeading = (xlu ?: -99.0) > (spy ?: 0.0) + 0.35
        val riskOn = (spy ?: 0.0) > 0.15 && (qqq ?: 0.0) > 0.0 && (vix ?: 0.0) < 0.0

        return when {
            riskOff -> BiasReading(
                bias = Bias.RISK_OFF,
                headlineTh = "Risk-off — อย่าฝืน",
                headlineEn = "Risk-off — don't fight it",
                detailTh = "VIX ติดบิด, breadth อ่อน. รอ VWAP นิ่งก่อนค่อยมองหา setup",
                detailEn = "VIX bid, breadth weak. Wait for VWAP stabilisation.",
                signals = signals,
            )
            aiLeading -> BiasReading(
                bias = Bias.AI_INFRA,
                headlineTh = "AI Infra วิ่งต่อ — semis นำ, yields ไม่กดดัน",
                headlineEn = "AI Infra setup — semis lead, yields contained",
                detailTh = "SMH นำ SPY และ US10Y ไม่พุ่ง — focus AVGO / NVDA / MU เข้า VWAP reclaim พร้อม volume",
                detailEn = "SMH leads SPY, US10Y contained — focus AVGO / NVDA / MU on VWAP reclaim with volume.",
                signals = signals,
            )
            powerLeading -> BiasReading(
                bias = Bias.POWER,
                headlineTh = "หมุนเข้า Power — utilities เด่นกว่าตลาด",
                headlineEn = "Power rotation — utilities outperform",
                detailTh = "XLU เด่นกว่า SPY → flow ไหลเข้า VRT / VST / CEG รอ volume confirm บน leader",
                detailEn = "XLU outperforming SPY — money rotating into VRT / VST / CEG; wait for volume on the leader.",
                signals = signals,
            )
            riskOn -> BiasReading(
                bias = Bias.RISK_ON,
                headlineTh = "ตลาดเปิดรับเสี่ยง — เล่น leader",
                headlineEn = "Risk-on — trade the leader",
                detailTh = "SPY + QQQ เขียว, VIX อ่อน. เล่นตัวนำของวัน, ไม่เล่นทั้งตะกร้า",
                detailEn = "SPY + QQQ green, VIX softening. Trade the leader of the day, not the whole basket.",
                signals = signals,
            )
            else -> BiasReading(
                bias = Bias.MIXED,
                headlineTh = "ตลาดผสม — เลือก leader ตาม RS",
                headlineEn = "Mixed tape — pick the leader",
                detailTh = "ไม่มี sector ไหนเด่นชัด ใช้ relative strength + VWAP reclaim เป็นตัวตัดสิน",
                detailEn = "No single sector dominates — lean on RS + VWAP reclaim.",
                signals = signals,
            )
        }
    }

    // ----- Rules / routine / mantra -----------------------------------------

    private fun rulesFor(bias: Bias, session: MarketSession): List<String> {
        val core = listOf(
            "อย่าซื้อแท่งเขียวแรก — รอ pullback + VWAP reclaim",
            "ถือ leader ตัวเดียว ไม่กระจาย",
            "Stop = ใต้ VWAP หรือ pullback low (เลือกที่ตึงกว่า)",
            "ถ้า thesis เสีย (yields พุ่ง / sector ETF แดง) — ออกทันที",
        )
        val sessionSpecific = when (session) {
            MarketSession.PREMARKET -> listOf(
                "Pre-market: ดู volume > avg ของ pre-market 5 วัน, ข่าว catalyst ของจริง",
                "ห้ามเข้า pre-market ถ้า spread > 0.5% หรือ liquidity บาง",
            )
            MarketSession.REGULAR -> listOf(
                "5 นาทีแรก: ห้ามเข้า, แค่ดู — ranking RS",
                "Entry หลัง 09:35 ET เมื่อ VWAP reclaim + volume กลับเข้ามา",
                "ลด size 50% ในช่วง lunch chop (12:00–13:30 ET)",
            )
            MarketSession.POSTMARKET -> listOf(
                "Post-market: เน้น earnings movers เท่านั้น",
                "ลด size, spread กว้าง — อย่ายัด stop ใกล้",
            )
            MarketSession.CLOSED -> listOf(
                "ตลาดปิด: เตรียม watchlist + อ่าน flow ข้ามคืน",
                "Sunday futures เปิด 18:00 ET — เริ่ม monitor NQ และ US10Y",
            )
        }
        val biasSpecific = when (bias) {
            Bias.AI_INFRA -> listOf(
                "Focus AVGO/NVDA/MU — ตัวที่ RS สูงสุดเข้า VWAP reclaim ก่อน",
                "Trim 1/3 ที่ 1R, trail stop เป็น break-even",
            )
            Bias.POWER -> listOf(
                "VRT/CEG/VST — ต้องเห็น XLU เขียว + volume 2x ขึ้นไปบน leader",
                "Power เคลื่อนช้ากว่า semis — อย่ารีบ trim",
            )
            Bias.RISK_ON -> listOf(
                "Trade leader ตัวเดียว, ไม่เกิน 2 ครั้งต่อวัน",
            )
            Bias.MIXED -> listOf(
                "ลด size, รอ setup ที่ชัดมาก — A+ setup เท่านั้น",
            )
            Bias.RISK_OFF -> listOf(
                "ถือเงินสด หรือ short ETF เป็น hedge เท่านั้น",
                "อย่าซื้อ dip จนกว่า VIX จะ peak",
            )
        }
        return core + sessionSpecific + biasSpecific
    }

    private fun routineFor(session: MarketSession): List<RoutineStep> = listOf(
        RoutineStep(
            timeLabel = "Sun 18:00 ET",
            titleTh = "เปิด futures + macro",
            titleEn = "Sunday futures open",
            detailTh = "เช็ค NQ / ES / DXY / US10Y — bias ของสัปดาห์",
        ),
        RoutineStep(
            timeLabel = "04:00 ET",
            titleTh = "Pre-market เริ่ม",
            titleEn = "Pre-market opens",
            detailTh = "ดู Finviz heatmap, ข่าว overnight, premarket movers volume + gap",
        ),
        RoutineStep(
            timeLabel = "08:30 ET",
            titleTh = "Macro release (ถ้ามี)",
            titleEn = "Macro release",
            detailTh = "CPI / NFP / Retail Sales — ดูปฏิกิริยา 10Y และ DXY ก่อนตัดสินใจ",
        ),
        RoutineStep(
            timeLabel = "09:25 ET",
            titleTh = "Lock watchlist",
            titleEn = "Lock watchlist",
            detailTh = "ตัด list เหลือ 3 ชื่อ จัด Tier S / A พร้อม trigger",
        ),
        RoutineStep(
            timeLabel = "09:30 ET",
            titleTh = "ตลาดเปิด — ห้ามเข้า 5 นาทีแรก",
            titleEn = "Open — no entry for 5 min",
            detailTh = "ดู RS, รอ VWAP reclaim + volume confirm บน leader",
        ),
        RoutineStep(
            timeLabel = "09:45 – 10:30 ET",
            titleTh = "Prime entry window",
            titleEn = "Prime entry window",
            detailTh = "Pullback → VWAP hold → volume กลับ → enter Tier S ที่ score สูงสุด",
        ),
        RoutineStep(
            timeLabel = "12:00 – 13:30 ET",
            titleTh = "Lunch chop — ลด size",
            titleEn = "Lunch chop",
            detailTh = "Volume เบา, false breakout เยอะ — ลด size 50% หรือถือ winner เฉยๆ",
        ),
        RoutineStep(
            timeLabel = "15:00 – 16:00 ET",
            titleTh = "Power hour",
            titleEn = "Power hour",
            detailTh = "ดู institutional close — leader ทำ HOD หรือ fail? ตัดสินใจถือข้ามคืนหรือ flat",
        ),
        RoutineStep(
            timeLabel = "16:00 – 20:00 ET",
            titleTh = "Post-market — เน้น earnings",
            titleEn = "Post-market",
            detailTh = "Earnings reactions เท่านั้น, ลด size, spread กว้าง, อย่ายัด stop ใกล้",
        ),
    )

    private fun mantraFor(bias: Bias): String = when (bias) {
        Bias.AI_INFRA -> "AI Infra วิ่งต่อ — เล่นตัวนำ ไม่ต้องเล่นทุกตัว"
        Bias.POWER -> "Bottleneck กำลัง shift จาก GPU → Power. ตามเงิน อย่าตามข่าวเก่า"
        Bias.RISK_ON -> "ตลาดดี ก็ยังต้องเลือก leader ตัวเดียว"
        Bias.MIXED -> "ไม่มี A+ ก็ไม่เข้า. Cash is a position."
        Bias.RISK_OFF -> "Cash is a position. ไม่ต้องเล่นทุกวัน"
    }

    private fun pct(v: Double): String = if (v >= 0) "+%.2f%%".format(v) else "%.2f%%".format(v)
}
