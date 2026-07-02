package com.example.androidllm

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Human-readable, present-tense labels for tool activity, so the chat shows "Checking your
 * calendar" instead of the raw `read_calendar()` machinery. [running] is shown while the tool
 * runs; [done] once it finishes.
 */
object ToolLabels {

    private data class Verb(val running: String, val done: String)

    private val verbs = mapOf(
        "read_file" to Verb("Reading a file", "Read a file"),
        "write_file" to Verb("Writing a file", "Wrote a file"),
        "list_files" to Verb("Listing files", "Listed files"),
        "web_search" to Verb("Searching the web", "Searched the web"),
        "fetch_url" to Verb("Opening a web page", "Read a web page"),
        "read_clipboard" to Verb("Reading the clipboard", "Read the clipboard"),
        "find_contact" to Verb("Looking up a contact", "Looked up a contact"),
        "read_calendar" to Verb("Checking your calendar", "Checked your calendar"),
        "create_event" to Verb("Adding a calendar event", "Added a calendar event"),
        "create_reminder" to Verb("Setting a reminder", "Set a reminder"),
        "compose_message" to Verb("Preparing a message", "Prepared a message"),
        "search_documents" to Verb("Searching your documents", "Searched your documents"),
        "search_memory" to Verb("Searching your memories", "Searched your memories"),
    )

    fun running(name: String): String = verbs[name]?.running ?: "Working"
    fun done(name: String): String = verbs[name]?.done ?: "Used a tool"
}

/** Small, dependency-free formatting helpers for user-facing time strings. */
object UiFormat {

    private val timeFmt = SimpleDateFormat("h:mm a", Locale.getDefault())
    private val dowFmt = SimpleDateFormat("EEEE", Locale.getDefault())
    private val monthDayFmt = SimpleDateFormat("MMM d", Locale.getDefault())

    /** Relative timestamp for chat lists: "2:23 PM", "Yesterday", "Mon", "Jun 28". */
    fun relative(millis: Long, now: Long = System.currentTimeMillis()): String {
        val then = Calendar.getInstance().apply { timeInMillis = millis }
        val today = Calendar.getInstance().apply { timeInMillis = now }
        fun sameDay(a: Calendar, b: Calendar) =
            a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
                a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

        if (sameDay(then, today)) return timeFmt.format(Date(millis))
        val yesterday = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
        if (sameDay(then, yesterday)) return "Yesterday"
        val daysAgo = ((now - millis) / (24L * 60 * 60 * 1000)).toInt()
        if (daysAgo in 2..6) return dowFmt.format(Date(millis))
        return monthDayFmt.format(Date(millis))
    }

    /** Day-group bucket for chat lists: "Today", "Yesterday", "Earlier". */
    fun dayGroup(millis: Long, now: Long = System.currentTimeMillis()): String {
        val then = Calendar.getInstance().apply { timeInMillis = millis }
        val today = Calendar.getInstance().apply { timeInMillis = now }
        fun sameDay(a: Calendar, b: Calendar) =
            a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
                a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
        if (sameDay(then, today)) return "Today"
        val yesterday = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
        if (sameDay(then, yesterday)) return "Yesterday"
        return "Earlier"
    }

    /** A schedule as words: "Weekdays at 1:45 PM", "Every Thursday, 8:00 AM", "Daily at 7:00 AM". */
    fun scheduleInWords(hour: Int, minute: Int, daysMask: Int): String {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute)
        }
        val time = timeFmt.format(cal.time)
        val days = ScheduleTime.daysLabel(daysMask)
        return when {
            daysMask == 0 -> "Daily at $time"
            days == "Weekdays" -> "Weekdays at $time"
            days == "Weekends" -> "Weekends at $time"
            // A single day selected → "Every Thursday, 8:00 AM"
            Integer.bitCount(daysMask) == 1 -> {
                val names = listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")
                val idx = (0..6).first { (daysMask and (1 shl it)) != 0 }
                "Every ${names[idx]}, $time"
            }
            else -> "$days at $time"
        }
    }
}
