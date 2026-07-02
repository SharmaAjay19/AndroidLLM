package com.example.androidllm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.androidllm.data.ScheduleEntity

/**
 * Arms exact-time alarms for scheduled prompts. On fire, [ScheduleReceiver] enqueues the
 * heavy inference work. Exact alarms are used so a "daily 8am briefing" lands on time
 * (the SCHEDULE_EXACT_ALARM / USE_EXACT_ALARM permissions are already declared).
 */
object ScheduleAlarms {

    const val EXTRA_SCHEDULE_ID = "schedule_id"

    private fun pendingIntent(context: Context, scheduleId: Long): PendingIntent {
        val intent = Intent(context, ScheduleReceiver::class.java).apply {
            action = ScheduleReceiver.ACTION_FIRE
            putExtra(EXTRA_SCHEDULE_ID, scheduleId)
        }
        return PendingIntent.getBroadcast(
            context, scheduleId.toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** A PendingIntent that opens the app, shown if the user taps the scheduled-alarm entry. */
    private fun showIntent(context: Context): PendingIntent {
        val open = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            context, 0, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Schedule (or reschedule) the alarm for [schedule] at its computed next run time. */
    fun arm(context: Context, schedule: ScheduleEntity): Long {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val next = ScheduleTime.nextRun(
            System.currentTimeMillis(), schedule.hour, schedule.minute, schedule.daysMask
        )
        val pi = pendingIntent(context, schedule.id)
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        // setAlarmClock is the most reliable across OEM battery managers (OnePlus/OPPO/Xiaomi
        // etc.) and is exempt from Doze — it's treated like a user alarm. Fall back to an
        // allow-while-idle alarm only when exact alarms aren't permitted.
        if (canExact) {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(next, showIntent(context)), pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
        }
        return next
    }

    /** Whether the app can currently schedule exact alarms (always true below Android 12). */
    fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return am.canScheduleExactAlarms()
    }

    /** Cancel any pending alarm for the given schedule. */
    fun cancel(context: Context, scheduleId: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pendingIntent(context, scheduleId))
    }
}
