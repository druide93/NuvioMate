package com.nuvio.tv.livetv.calendar

import java.time.DayOfWeek
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class CalendarWeekStartTest {
    // Thursday 19 November 2026.
    private val thursday = LocalDate.of(2026, 11, 19)

    @Test fun `setting falls back to Monday`() {
        assertEquals(DayOfWeek.MONDAY, calendarFirstDay("MONDAY"))
        assertEquals(DayOfWeek.SATURDAY, calendarFirstDay("SATURDAY"))
        assertEquals(DayOfWeek.SUNDAY, calendarFirstDay("SUNDAY"))
        assertEquals(DayOfWeek.MONDAY, calendarFirstDay("WEDNESDAY"))
        assertEquals(DayOfWeek.MONDAY, calendarFirstDay("junk"))
    }

    @Test fun `default setting is Monday`() {
        assertEquals(DayOfWeek.MONDAY, calendarFirstDay(com.nuvio.tv.livetv.model.LiveTvSettings().calendarWeekStart))
    }

    @Test fun `week starts on the chosen day`() {
        assertEquals(LocalDate.of(2026, 11, 15), calendarWeekOf(thursday, DayOfWeek.SUNDAY))
        assertEquals(LocalDate.of(2026, 11, 16), calendarWeekOf(thursday, DayOfWeek.MONDAY))
        assertEquals(LocalDate.of(2026, 11, 14), calendarWeekOf(thursday, DayOfWeek.SATURDAY))
    }

    @Test fun `first day is its own week start`() {
        val monday = LocalDate.of(2026, 11, 16)
        assertEquals(monday, calendarWeekOf(monday, DayOfWeek.MONDAY))
        assertEquals(0, calendarDayIndex(monday, DayOfWeek.MONDAY))
        // Sunday is the last column when the week starts on Monday.
        assertEquals(6, calendarDayIndex(LocalDate.of(2026, 11, 22), DayOfWeek.MONDAY))
    }
}
