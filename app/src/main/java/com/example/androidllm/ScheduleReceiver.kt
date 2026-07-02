package com.example.androidllm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf

/**
 * Receives scheduled-prompt alarms (and re-arms all schedules after a reboot), then enqueues
 * the heavy [BriefingWorker] to actually run the model. Kept lightweight so it returns fast.
 */
class ScheduleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_FIRE -> {
                val id = intent.getLongExtra(ScheduleAlarms.EXTRA_SCHEDULE_ID, -1L)
                if (id <= 0) return
                enqueueBriefing(context, id)
            }
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> rearmAll(context)
        }
    }

    private fun enqueueBriefing(context: Context, scheduleId: Long) {
        val request = OneTimeWorkRequestBuilder<BriefingWorker>()
            .setInputData(workDataOf(BriefingWorker.KEY_SCHEDULE_ID to scheduleId))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork("briefing-$scheduleId", androidx.work.ExistingWorkPolicy.KEEP, request)
    }

    private fun rearmAll(context: Context) {
        val pending = goAsync()
        BriefingScope.launch {
            try {
                val dao = com.example.androidllm.data.ChatDatabase.get(context).scheduleDao()
                for (s in dao.enabledOnce()) {
                    val next = ScheduleAlarms.arm(context, s)
                    dao.updateRunTimes(s.id, s.lastRunAt, next)
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_FIRE = "com.example.androidllm.ACTION_SCHEDULE_FIRE"
    }
}
