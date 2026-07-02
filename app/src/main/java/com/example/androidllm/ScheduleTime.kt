package com.example.androidllm

import java.util.Calendar

/**
 * Pure scheduling math for proactive briefings (no Android dependencies, so it is
 * host-unit-testable). A schedule fires at a wall-clock [hour]:[minute] on the weekdays
 * selected by [daysMask].
 *
 * [daysMask] is a bitmask of weekdays: bit0 = Sunday … bit6 = Saturday. A value of 0 means
 * "every day".
 */
object ScheduleTime {

    /** Weekday bit for a [Calendar] DAY_OF_WEEK value (Calendar.SUNDAY == 1). */
    fun bitFor(calendarDayOfWeek: Int): Int = 1 shl (calendarDayOfWeek - Calendar.SUNDAY)

    /** True if [daysMask] includes the given [Calendar] DAY_OF_WEEK (mask 0 = every day). */
    fun includesDay(daysMask: Int, calendarDayOfWeek: Int): Boolean =
        daysMask == 0 || (daysMask and bitFor(calendarDayOfWeek)) != 0

    /**
     * Epoch millis of the next time this schedule should fire strictly after [nowMillis].
     * Scans up to 8 days ahead; returns the first matching day at [hour]:[minute].
     */
    fun nextRun(nowMillis: Long, hour: Int, minute: Int, daysMask: Int): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = nowMillis
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        // If today's slot already passed (or isn't a selected day), advance day by day.
        for (i in 0..8) {
            if (cal.timeInMillis > nowMillis &&
                includesDay(daysMask, cal.get(Calendar.DAY_OF_WEEK))
            ) {
                return cal.timeInMillis
            }
            cal.add(Calendar.DAY_OF_MONTH, 1)
        }
        // Fallback (should not happen for any non-degenerate mask): tomorrow's slot.
        return cal.timeInMillis
    }

    /** Human-readable summary of the selected days, e.g. "Every day", "Weekdays", "Mon, Wed". */
    fun daysLabel(daysMask: Int): String {
        if (daysMask == 0) return "Every day"
        val weekdays = 0b0111110 // Mon..Fri
        val weekend = 0b1000001  // Sun, Sat
        if (daysMask == weekdays) return "Weekdays"
        if (daysMask == weekend) return "Weekends"
        if (daysMask == 0b1111111) return "Every day"
        val names = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
        return (0..6).filter { (daysMask and (1 shl it)) != 0 }
            .joinToString(", ") { names[it] }
    }
}
