package com.example.androidllm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class ScheduleTimeTest {

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long {
        val c = Calendar.getInstance()
        c.set(year, month - 1, day, hour, minute, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    private fun fieldsOf(millis: Long): Triple<Int, Int, Int> {
        val c = Calendar.getInstance().apply { timeInMillis = millis }
        return Triple(c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
    }

    @Test
    fun laterToday_whenSlotStillAhead() {
        val now = at(2026, 7, 2, 6, 0)          // 06:00
        val next = ScheduleTime.nextRun(now, 8, 0, 0) // 08:00 every day
        val (day, h, m) = fieldsOf(next)
        assertEquals(2, day)
        assertEquals(8, h)
        assertEquals(0, m)
    }

    @Test
    fun tomorrow_whenSlotAlreadyPassed() {
        val now = at(2026, 7, 2, 9, 0)          // 09:00, past 08:00
        val next = ScheduleTime.nextRun(now, 8, 0, 0)
        val (day, h, _) = fieldsOf(next)
        assertEquals(3, day)
        assertEquals(8, h)
    }

    @Test
    fun exactlyAtSlot_movesToNextOccurrence() {
        val now = at(2026, 7, 2, 8, 0)          // exactly 08:00
        val next = ScheduleTime.nextRun(now, 8, 0, 0)
        assertTrue(next > now)
        val (day, _, _) = fieldsOf(next)
        assertEquals(3, day) // strictly after now → tomorrow
    }

    @Test
    fun weekdayMask_skipsToSelectedDay() {
        // 2026-07-04 is a Saturday; Mon..Fri mask should jump to Mon 2026-07-06.
        val saturday = at(2026, 7, 4, 6, 0)
        val weekdays = 0b0111110 // Mon..Fri
        val next = ScheduleTime.nextRun(saturday, 8, 0, weekdays)
        val c = Calendar.getInstance().apply { timeInMillis = next }
        assertEquals(Calendar.MONDAY, c.get(Calendar.DAY_OF_WEEK))
        assertEquals(6, c.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun includesDay_zeroMaskMeansEveryDay() {
        assertTrue(ScheduleTime.includesDay(0, Calendar.WEDNESDAY))
        assertTrue(ScheduleTime.includesDay(0, Calendar.SUNDAY))
    }

    @Test
    fun includesDay_respectsMask() {
        val sundayOnly = 1 shl 0
        assertTrue(ScheduleTime.includesDay(sundayOnly, Calendar.SUNDAY))
        assertFalse(ScheduleTime.includesDay(sundayOnly, Calendar.MONDAY))
    }

    @Test
    fun daysLabel_variants() {
        assertEquals("Every day", ScheduleTime.daysLabel(0))
        assertEquals("Weekdays", ScheduleTime.daysLabel(0b0111110))
        assertEquals("Weekends", ScheduleTime.daysLabel(0b1000001))
        assertEquals("Mon, Wed", ScheduleTime.daysLabel((1 shl 1) or (1 shl 3)))
    }
}
