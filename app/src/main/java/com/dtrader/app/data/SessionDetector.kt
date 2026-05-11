package com.dtrader.app.data

import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Detects the current US equity market session from the New York wall clock.
 *
 *  Premarket:   04:00 – 09:30 ET (Mon–Fri)
 *  Regular:     09:30 – 16:00 ET (Mon–Fri)
 *  Postmarket:  16:00 – 20:00 ET (Mon–Fri)
 *  Closed:      everything else.
 *
 *  Sunday 18:00 ET onwards is treated as PREMARKET so the Sunday-night
 *  futures workflow has a place to live.
 */
enum class MarketSession(val labelEn: String, val labelTh: String) {
    PREMARKET("Pre-Market", "ก่อนตลาดเปิด"),
    REGULAR("Regular Hours", "ตลาดเปิด"),
    POSTMARKET("Post-Market", "หลังตลาดปิด"),
    CLOSED("Closed", "ตลาดปิด"),
}

object SessionDetector {

    private val NY: ZoneId = ZoneId.of("America/New_York")

    fun nowInNewYork(): ZonedDateTime = ZonedDateTime.now(NY)

    fun current(now: ZonedDateTime = nowInNewYork()): MarketSession {
        val dow = now.dayOfWeek
        val hm = now.hour * 60 + now.minute

        // Sunday evening futures-watch window — treat as Monday pre-market prep.
        if (dow == DayOfWeek.SUNDAY) {
            return if (hm >= 18 * 60) MarketSession.PREMARKET else MarketSession.CLOSED
        }
        if (dow == DayOfWeek.SATURDAY) return MarketSession.CLOSED

        return when (hm) {
            in (4 * 60) until (9 * 60 + 30) -> MarketSession.PREMARKET
            in (9 * 60 + 30) until (16 * 60) -> MarketSession.REGULAR
            in (16 * 60) until (20 * 60) -> MarketSession.POSTMARKET
            else -> MarketSession.CLOSED
        }
    }

    /** Friendly NY time stamp, e.g. "Mon 09:14 ET". */
    fun timestamp(now: ZonedDateTime = nowInNewYork()): String {
        val day = now.dayOfWeek.name.lowercase().replaceFirstChar { it.titlecase() }.take(3)
        val hour = "%02d".format(now.hour)
        val minute = "%02d".format(now.minute)
        return "$day $hour:$minute ET"
    }
}
