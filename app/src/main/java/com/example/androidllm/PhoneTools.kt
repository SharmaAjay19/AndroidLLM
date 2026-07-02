package com.example.androidllm

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.ClipboardManager
import android.net.Uri
import android.os.Build
import android.provider.CalendarContract
import android.provider.ContactsContract
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Phone-native agent tools: clipboard, contacts, calendar, reminders, and message drafts.
 * Registered alongside the file/web tools and dispatched from [MainViewModel] (they need a
 * Context, runtime permissions, and — for state-changing tools — a user confirmation step).
 *
 * State-changing tools (create_event, create_reminder, compose_message) never act silently:
 * the ViewModel requires an explicit user confirmation before [execute] runs them, and
 * compose_message only opens a pre-filled draft (the user sends it).
 */
object PhoneTools {

    val names = setOf(
        "read_clipboard", "find_contact", "read_calendar",
        "create_event", "create_reminder", "compose_message"
    )

    /** Tools that change device state and therefore require a user confirmation first. */
    private val writeTools = setOf("create_event", "create_reminder", "compose_message")

    fun isWrite(name: String): Boolean = name in writeTools

    /** Runtime permissions required for a tool (requested on first use). */
    fun requiredPermissions(name: String): List<String> = when (name) {
        "find_contact" -> listOf(Manifest.permission.READ_CONTACTS)
        "read_calendar" -> listOf(Manifest.permission.READ_CALENDAR)
        "create_event" -> listOf(Manifest.permission.WRITE_CALENDAR)
        "create_reminder" ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                listOf(Manifest.permission.POST_NOTIFICATIONS)
            else emptyList()
        else -> emptyList() // read_clipboard, compose_message need no runtime permission
    }

    val systemInstructions: String = """
You can also act on the phone with these tools:
- read_clipboard — read the current clipboard text. args: {}
- find_contact — look up a contact by name. args: {"name": "<partial name>"}
- read_calendar — list upcoming events. args:
  {"start": "YYYY-MM-DD" (optional), "end": "YYYY-MM-DD" (optional), "days": <lookahead if no end, default 7>}
- create_event — add a calendar event (asks the user to confirm). args:
  {"title": "<text>", "start": "YYYY-MM-DD HH:MM", "end": "YYYY-MM-DD HH:MM"}
- create_reminder — schedule a reminder notification (asks the user to confirm). args:
  {"text": "<reminder>", "time": "YYYY-MM-DD HH:MM"}
- compose_message — open a pre-filled SMS or email draft for the user to review and send. args:
  {"to": "<phone or email>", "body": "<message>", "channel": "sms" | "email", "subject": "<email subject, optional>"}
Use the current date/time given in this system prompt to compute absolute times for events and reminders.
""".trim()

    fun label(call: ToolCall): String = when (call.name) {
        "read_clipboard" -> "read_clipboard()"
        "find_contact" -> "find_contact(\"${call.args.optString("name")}\")"
        "read_calendar" -> "read_calendar()"
        "create_event" -> "create_event(\"${call.args.optString("title")}\")"
        "create_reminder" -> "create_reminder(\"${call.args.optString("text")}\")"
        "compose_message" -> "compose_message(\"${call.args.optString("to")}\")"
        else -> call.name
    }

    /** Confirmation prompt shown before a state-changing tool runs. */
    fun confirmText(call: ToolCall): Pair<String, String> = when (call.name) {
        "create_event" -> "Add calendar event?" to
            "\"${call.args.optString("title")}\"\n${call.args.optString("start")} – ${call.args.optString("end")}"
        "create_reminder" -> "Set reminder?" to
            "\"${call.args.optString("text")}\"\nat ${call.args.optString("time")}"
        "compose_message" -> "Open ${call.args.optString("channel", "message")} draft?" to
            "To: ${call.args.optString("to")}\n\n${call.args.optString("body")}"
        else -> "Proceed?" to label(call)
    }

    // ---- Execution ----

    fun execute(context: Context, call: ToolCall): ToolResult = try {
        when (call.name) {
            "read_clipboard" -> readClipboard(context)
            "find_contact" -> findContact(context, call.args.optString("name").trim())
            "read_calendar" -> readCalendar(context, call.args)
            "create_event" -> createEvent(context, call.args)
            "create_reminder" -> createReminder(context, call.args)
            "compose_message" -> composeMessage(context, call.args)
            else -> ToolResult(false, "Unknown tool '${call.name}'")
        }
    } catch (e: Exception) {
        ToolResult(false, "Error running ${call.name}: ${e.message}")
    }

    private fun readClipboard(context: Context): ToolResult {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return ToolResult(false, "Clipboard unavailable")
        val clip = cm.primaryClip
        if (clip == null || clip.itemCount == 0) return ToolResult(true, "(clipboard is empty)")
        val text = clip.getItemAt(0).coerceToText(context)?.toString().orEmpty()
        return if (text.isBlank()) ToolResult(true, "(clipboard is empty)")
        else ToolResult(true, "Clipboard contents:\n$text")
    }

    private fun findContact(context: Context, name: String): ToolResult {
        if (name.isEmpty()) return ToolResult(false, "Provide a name to search for.")
        val resolver = context.contentResolver
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val results = LinkedHashMap<String, MutableSet<String>>()
        resolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection, selection, arrayOf("%$name%"), null
        )?.use { c ->
            val nameIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (c.moveToNext() && results.size < 20) {
                val n = c.getString(nameIdx) ?: continue
                val num = c.getString(numIdx)?.trim().orEmpty()
                results.getOrPut(n) { linkedSetOf() }.apply { if (num.isNotEmpty()) add(num) }
            }
        }
        if (results.isEmpty()) return ToolResult(true, "No contacts found matching \"$name\".")
        val body = results.entries.joinToString("\n") { (n, nums) ->
            if (nums.isEmpty()) n else "$n — ${nums.joinToString(", ")}"
        }
        return ToolResult(true, body)
    }

    private fun readCalendar(context: Context, args: JSONObject): ToolResult {
        val startDay = parseDate(args.optString("start"))
        val start = startDay ?: System.currentTimeMillis()
        val endArg = parseDate(args.optString("end"))
        val end = when {
            endArg != null -> endArg + DAY_MS // inclusive of the end day
            else -> start + args.optInt("days", 7).coerceIn(1, 60) * DAY_MS
        }

        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, start)
        ContentUris.appendId(builder, end)
        val projection = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.EVENT_LOCATION
        )
        val out = StringBuilder()
        var count = 0
        context.contentResolver.query(
            builder.build(), projection, null, null, CalendarContract.Instances.BEGIN + " ASC"
        )?.use { c ->
            val tIdx = c.getColumnIndex(CalendarContract.Instances.TITLE)
            val bIdx = c.getColumnIndex(CalendarContract.Instances.BEGIN)
            val lIdx = c.getColumnIndex(CalendarContract.Instances.EVENT_LOCATION)
            while (c.moveToNext() && count < 50) {
                val title = c.getString(tIdx)?.ifBlank { "(untitled)" } ?: "(untitled)"
                val begin = c.getLong(bIdx)
                val loc = c.getString(lIdx)?.trim().orEmpty()
                out.append("• ").append(formatDateTime(begin)).append(" — ").append(title)
                if (loc.isNotEmpty()) out.append(" @ ").append(loc)
                out.append('\n')
                count++
            }
        }
        return if (count == 0) ToolResult(true, "No events found in that range.")
        else ToolResult(true, "Upcoming events:\n$out".trimEnd())
    }

    private fun createEvent(context: Context, args: JSONObject): ToolResult {
        val title = args.optString("title").trim()
        val start = parseDateTime(args.optString("start"))
            ?: return ToolResult(false, "Invalid start time. Use \"YYYY-MM-DD HH:MM\".")
        val end = parseDateTime(args.optString("end")) ?: (start + 60 * 60 * 1000L)
        if (title.isEmpty()) return ToolResult(false, "Event needs a title.")

        val calId = primaryCalendarId(context)
            ?: return ToolResult(false, "No writable calendar found on this device.")
        val values = ContentValues().apply {
            put(CalendarContract.Events.DTSTART, start)
            put(CalendarContract.Events.DTEND, end)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.CALENDAR_ID, calId)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
        }
        val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            ?: return ToolResult(false, "Failed to create the event.")
        val id = ContentUris.parseId(uri)
        return ToolResult(
            true,
            "Created event #$id: \"$title\" on ${formatDateTime(start)}."
        )
    }

    private fun primaryCalendarId(context: Context): Long? {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL
        )
        var fallback: Long? = null
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI, projection, null, null, null
        )?.use { c ->
            val idIdx = c.getColumnIndex(CalendarContract.Calendars._ID)
            val primIdx = c.getColumnIndex(CalendarContract.Calendars.IS_PRIMARY)
            val accIdx = c.getColumnIndex(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL)
            while (c.moveToNext()) {
                val access = if (accIdx >= 0) c.getInt(accIdx) else 0
                if (access < CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) continue
                val id = c.getLong(idIdx)
                if (primIdx >= 0 && c.getInt(primIdx) == 1) return id
                if (fallback == null) fallback = id
            }
        }
        return fallback
    }

    private fun createReminder(context: Context, args: JSONObject): ToolResult {
        val text = args.optString("text").trim().ifEmpty { "Reminder" }
        val time = parseDateTime(args.optString("time"))
            ?: return ToolResult(false, "Invalid time. Use \"YYYY-MM-DD HH:MM\".")
        if (time <= System.currentTimeMillis()) {
            return ToolResult(false, "That time is in the past. Pick a future time.")
        }

        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val requestCode = (time % Int.MAX_VALUE).toInt()
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra(ReminderReceiver.EXTRA_TEXT, text)
            putExtra(ReminderReceiver.EXTRA_ID, requestCode)
        }
        val pi = PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        if (canExact) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, pi)
        }
        val note = if (canExact) "" else " (approximate — exact alarms not permitted)"
        return ToolResult(true, "Reminder set for ${formatDateTime(time)}$note: \"$text\".")
    }

    private fun composeMessage(context: Context, args: JSONObject): ToolResult {
        val to = args.optString("to").trim()
        val body = args.optString("body")
        val channel = args.optString("channel").trim().lowercase(Locale.US)
        if (to.isEmpty()) return ToolResult(false, "Provide a recipient (\"to\").")

        val intent = when (channel) {
            "email" -> Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$to")).apply {
                val subject = args.optString("subject")
                if (subject.isNotBlank()) putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, body)
            }
            else -> Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$to")).apply {
                putExtra("sms_body", body)
            }
        }.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }

        return try {
            context.startActivity(intent)
            val kind = if (channel == "email") "email" else "SMS"
            ToolResult(true, "Opened a $kind draft to $to. The user can review and send it.")
        } catch (e: Exception) {
            ToolResult(false, "No app available to send a ${channel.ifEmpty { "message" }}.")
        }
    }

    // ---- Pure date/time helpers (unit-testable) ----

    const val DAY_MS = 24L * 60 * 60 * 1000

    private val dateTimePatterns = listOf(
        "yyyy-MM-dd HH:mm", "yyyy-MM-dd'T'HH:mm", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ss"
    )

    /** Parse a local date-time string to epoch millis, or null. */
    fun parseDateTime(s: String?): Long? {
        val v = s?.trim().orEmpty()
        if (v.isEmpty()) return null
        for (p in dateTimePatterns) {
            runCatching {
                val fmt = SimpleDateFormat(p, Locale.US).apply { isLenient = false }
                return fmt.parse(v)?.time
            }
        }
        return null
    }

    /** Parse a local date string (YYYY-MM-DD) to the start-of-day epoch millis, or null. */
    fun parseDate(s: String?): Long? {
        val v = s?.trim().orEmpty()
        if (v.isEmpty()) return null
        return runCatching {
            val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false }
            fmt.parse(v)?.time
        }.getOrNull()
    }

    private fun formatDateTime(millis: Long): String =
        SimpleDateFormat("EEE MMM d, h:mm a", Locale.US).format(Date(millis))

    /** Human-readable current date/time line injected into the system prompt. */
    fun nowLine(): String =
        "Current date/time: " + SimpleDateFormat("EEE yyyy-MM-dd HH:mm", Locale.US).format(Date()) +
            " (${TimeZone.getDefault().id})."
}
