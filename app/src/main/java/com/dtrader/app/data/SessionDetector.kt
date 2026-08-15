package com.dtrader.app.data

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Month
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

/**
 * Detects the current US equity market session from the New York wall clock.
 *
 *  Premarket:   04:00 – 09:30 ET (Mon–Fri, non-holiday)
 *  Regular:     09:30 – 16:00 ET (Mon–Fri, non-holiday)
 *  Postmarket:  16:00 – 20:00 ET (Mon–Fri, non-holiday)
 *  Closed:      weekends + full-day US holidays.
 *
 *  Sunday 18:00 ET onwards (CME futures session open) is reported as
 *  PREMARKET so the Sunday-night workflow has a place to live.
 *
 *  Early-close half-days (day after Thanksgiving, Christmas Eve, etc.)
 *  are treated as normal sessions — this only handles full-day holidays.
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
        val today = now.toLocalDate()

        // Sunday evening → treat as premarket prep (futures open 18:00 ET).
        if (dow == DayOfWeek.SUNDAY) {
            val nextDay = today.plusDays(1)
            return if (hm >= 18 * 60 && !USMarketHolidays.isFullDayClosed(nextDay))
                MarketSession.PREMARKET
            else MarketSession.CLOSED
        }
        if (dow == DayOfWeek.SATURDAY) return MarketSession.CLOSED
        if (USMarketHolidays.isFullDayClosed(today)) return MarketSession.CLOSED

        return when (hm) {
            in (4 * 60) until (9 * 60 + 30) -> MarketSession.PREMARKET
            in (9 * 60 + 30) until (16 * 60) -> MarketSession.REGULAR
            in (16 * 60) until (20 * 60) -> MarketSession.POSTMARKET
            else -> MarketSession.CLOSED
        }
    }

    fun timestamp(now: ZonedDateTime = nowInNewYork()): String {
        val day = now.dayOfWeek.name.lowercase().replaceFirstChar { it.titlecase() }.take(3)
        val hour = "%02d".format(now.hour)
        val minute = "%02d".format(now.minute)
        return "$day $hour:$minute ET"
    }
}

/**
 * NYSE / NASDAQ full-day holiday calendar. Fixed dates observed to
 * following Monday when they land on Sunday, previous Friday when
 * they land on Saturday (per NYSE Rule 7.2). Good Friday computed
 * from Easter (anonymous Gregorian algorithm).
 */
object USMarketHolidays {

    fun isFullDayClosed(date: LocalDate): Boolean = holidaysFor(date.year).contains(date)

    private val cache = mutableMapOf<Int, Set<LocalDate>>()

    private fun holidaysFor(year: Int): Set<LocalDate> = cache.getOrPut(year) {
        val list = mutableListOf<LocalDate>()
        list += observed(LocalDate.of(year, Month.JANUARY, 1))                                       // New Year's Day
        list += nthWeekdayOfMonth(year, Month.JANUARY, DayOfWeek.MONDAY, 3)                          // MLK Jr Day
        list += nthWeekdayOfMonth(year, Month.FEBRUARY, DayOfWeek.MONDAY, 3)                         // Presidents' Day
        list += goodFriday(year)                                                                     // Good Friday
        list += lastWeekdayOfMonth(year, Month.MAY, DayOfWeek.MONDAY)                                // Memorial Day
        list += observed(LocalDate.of(year, Month.JUNE, 19))                                         // Juneteenth
        list += observed(LocalDate.of(year, Month.JULY, 4))                                          // Independence Day
        list += nthWeekdayOfMonth(year, Month.SEPTEMBER, DayOfWeek.MONDAY, 1)                        // Labor Day
        list += nthWeekdayOfMonth(year, Month.NOVEMBER, DayOfWeek.THURSDAY, 4)                       // Thanksgiving
        list += observed(LocalDate.of(year, Month.DECEMBER, 25))                                     // Christmas
        return list.toSet()
    }

    /** Move Saturday holidays to Friday, Sunday to Monday (NYSE rule 7.2). */
    private fun observed(date: LocalDate): LocalDate = when (date.dayOfWeek) {
        DayOfWeek.SATURDAY -> date.minusDays(1)
        DayOfWeek.SUNDAY -> date.plusDays(1)
        else -> date
    }

    private fun nthWeekdayOfMonth(year: Int, month: Month, dow: DayOfWeek, n: Int): LocalDate =
        LocalDate.of(year, month, 1).with(TemporalAdjusters.dayOfWeekInMonth(n, dow))

    private fun lastWeekdayOfMonth(year: Int, month: Month, dow: DayOfWeek): LocalDate =
        LocalDate.of(year, month, 1).with(TemporalAdjusters.lastInMonth(dow))

    /** Anonymous Gregorian algorithm for Easter Sunday; Good Friday = Easter - 2. */
    private fun goodFriday(year: Int): LocalDate {
        val a = year % 19
        val b = year / 100
        val c = year % 100
        val d = b / 4
        val e = b % 4
        val f = (b + 8) / 25
        val g = (b - f + 1) / 3
        val h = (19 * a + b - d - g + 15) % 30
        val i = c / 4
        val k = c % 4
        val l = (32 + 2 * e + 2 * i - h - k) % 7
        val m = (a + 11 * h + 22 * l) / 451
        val month = (h + l - 7 * m + 114) / 31
        val day = (h + l - 7 * m + 114) % 31 + 1
        val easter = LocalDate.of(year, month, day)
        return easter.minusDays(2)
    }
}
