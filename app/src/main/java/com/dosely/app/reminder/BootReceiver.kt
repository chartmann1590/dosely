package com.dosely.app.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Re-schedules the daily reminder work after device restarts. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            ReminderWorker.scheduleDaily(context)
        }
    }
}
