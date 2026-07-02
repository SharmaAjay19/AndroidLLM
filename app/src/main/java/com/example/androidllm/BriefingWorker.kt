package com.example.androidllm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.example.androidllm.data.ChatDatabase
import com.example.androidllm.data.ChatEntity
import com.example.androidllm.data.MessageEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Long-lived scope for fire-and-forget background bookkeeping (alarm re-arming). */
object BriefingScope {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    fun launch(block: suspend CoroutineScope.() -> Unit) = scope.launch(block = block)
}

/**
 * Runs one scheduled prompt in the background: loads the model, runs the prompt through the
 * shared [ChatEngine] (tools optional), saves the result as a chat, posts a notification that
 * opens it, then re-arms the schedule's next occurrence.
 */
class BriefingWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val scheduleId = inputData.getLong(KEY_SCHEDULE_ID, -1L)
        if (scheduleId <= 0) return Result.failure()

        val db = ChatDatabase.get(applicationContext)
        val scheduleDao = db.scheduleDao()
        val chatDao = db.chatDao()
        val schedule = scheduleDao.getById(scheduleId) ?: return Result.success()
        if (!schedule.enabled) return Result.success()

        // Always re-arm the next occurrence, even if this run is skipped/fails.
        fun rearm() {
            val next = ScheduleAlarms.arm(applicationContext, schedule)
            BriefingScope.launch {
                scheduleDao.updateRunTimes(scheduleId, System.currentTimeMillis(), next)
            }
        }

        // Battery guard: skip heavy inference on low battery unless charging.
        if (isLowBatteryAndNotCharging()) {
            notify(schedule.name, "Skipped this run (low battery).", null)
            rearm()
            return Result.success()
        }

        runCatching { setForeground(foregroundInfo(schedule.name)) }

        val engine = ChatEngine.get(applicationContext)
        if (!engine.ensureModelLoaded(applicationContext)) {
            notify(schedule.name, "Couldn't run — model not downloaded yet.", null)
            rearm()
            return Result.success()
        }

        val now = System.currentTimeMillis()
        val chatId = chatDao.insertChat(
            ChatEntity(
                title = "\uD83D\uDD14 ${schedule.name}",
                createdAt = now, updatedAt = now
            )
        )
        chatDao.insertMessage(
            MessageEntity(chatId = chatId, role = "user", content = schedule.prompt, createdAt = now)
        )

        val result = runCatching {
            engine.run(
                chatId = chatId,
                userContent = schedule.prompt,
                config = ChatEngine.Config(
                    toolsEnabled = schedule.toolsEnabled,
                    disableThinking = true
                ),
                dispatch = { call -> dispatchHeadless(call) },
                sink = ChatEngine.StreamSink.NONE,
            )
        }.getOrElse {
            notify(schedule.name, "Run failed: ${it.message}", chatId)
            rearm()
            return Result.success()
        }

        val preview = result.finalText.take(240)
        notify(schedule.name, preview.ifBlank { "Done." }, chatId)
        rearm()
        return Result.success()
    }

    /** Headless tool routing: read/web/file tools run; interactive/write tools are declined. */
    private suspend fun dispatchHeadless(call: ToolCall): ToolResult {
        val browser = Browser(applicationContext)
        return when (call.name) {
            "web_search" -> browser.search(call.args.optString("query"))
            "fetch_url" -> browser.fetch(call.args.optString("url"), call.args.optInt("offset", 0))
            in PhoneTools.names -> {
                if (PhoneTools.isWrite(call.name)) {
                    ToolResult(
                        false,
                        "Tool '${call.name}' is not available in a scheduled background run " +
                            "(it needs the user present to confirm). Continue without it."
                    )
                } else {
                    withContext(Dispatchers.IO) { PhoneTools.execute(applicationContext, call) }
                }
            }
            else -> withContext(Dispatchers.IO) { Tools.execute(applicationContext, call) }
        }
    }

    private fun isLowBatteryAndNotCharging(): Boolean {
        val bm = applicationContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            ?: return false
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = bm.isCharging
        return level in 1..14 && !charging
    }

    private fun foregroundInfo(name: String): ForegroundInfo {
        ensureChannel()
        val n = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("Running: $name")
            .setContentText("Generating your briefing…")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                PROGRESS_NOTIF_ID, n,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(PROGRESS_NOTIF_ID, n)
        }
    }

    private fun notify(title: String, text: String, chatId: Long?) {
        ensureChannel()
        val nm = applicationContext
            .getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val openIntent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (chatId != null) putExtra(MainActivity.EXTRA_OPEN_CHAT_ID, chatId)
        }
        val pi = PendingIntent.getActivity(
            applicationContext, (chatId ?: 0L).toInt(), openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val n = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        nm.notify((chatId ?: System.currentTimeMillis()).toInt(), n)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = applicationContext
                .getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID, "Scheduled briefings", NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "Results from your scheduled prompts" }
            nm.createNotificationChannel(channel)
        }
    }

    companion object {
        const val KEY_SCHEDULE_ID = "schedule_id"
        const val CHANNEL_ID = "briefings"
        private const val PROGRESS_NOTIF_ID = 42_100
    }
}
