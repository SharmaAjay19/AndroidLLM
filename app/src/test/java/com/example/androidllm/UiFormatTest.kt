package com.example.androidllm

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

class UiFormatTest {

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long {
        val c = Calendar.getInstance()
        c.set(year, month - 1, day, hour, minute, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    @Test
    fun dayGroupTodayYesterdayEarlier() {
        val now = at(2026, 7, 2, 15, 0)
        assertEquals("Today", UiFormat.dayGroup(at(2026, 7, 2, 8, 0), now))
        assertEquals("Yesterday", UiFormat.dayGroup(at(2026, 7, 1, 23, 0), now))
        assertEquals("Earlier", UiFormat.dayGroup(at(2026, 6, 20, 8, 0), now))
    }

    @Test
    fun relativeTodayShowsTime() {
        val now = at(2026, 7, 2, 15, 0)
        val s = UiFormat.relative(at(2026, 7, 2, 9, 5), now)
        // Same day → a clock time containing ":".
        assert(s.contains(":")) { "expected a time, got $s" }
    }

    @Test
    fun relativeYesterday() {
        val now = at(2026, 7, 2, 15, 0)
        assertEquals("Yesterday", UiFormat.relative(at(2026, 7, 1, 10, 0), now))
    }

    @Test
    fun scheduleInWordsDaily() {
        // Daily (mask 0) at 07:00
        val s = UiFormat.scheduleInWords(7, 0, 0)
        assert(s.startsWith("Daily at")) { s }
    }

    @Test
    fun scheduleInWordsWeekdays() {
        val weekdays = 0b0111110
        val s = UiFormat.scheduleInWords(13, 45, weekdays)
        assert(s.startsWith("Weekdays at")) { s }
    }

    @Test
    fun scheduleInWordsSingleDay() {
        val thursdayOnly = 1 shl Calendar.THURSDAY.minus(1) // bit for Thursday (Sun=bit0)
        val s = UiFormat.scheduleInWords(8, 0, thursdayOnly)
        assert(s.startsWith("Every Thursday")) { s }
    }

    @Test
    fun toolLabelsAreHumanReadable() {
        assertEquals("Checking your calendar", ToolLabels.running("read_calendar"))
        assertEquals("Checked your calendar", ToolLabels.done("read_calendar"))
        assertEquals("Searched your documents", ToolLabels.done("search_documents"))
    }
}
