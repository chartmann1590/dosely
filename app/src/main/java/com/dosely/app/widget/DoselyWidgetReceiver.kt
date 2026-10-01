package com.dosely.app.widget

import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import com.dosely.app.data.db.DoselyDb
import com.dosely.app.data.prefs.SettingsRepository
import com.dosely.app.domain.DoseEngine
import com.dosely.app.domain.Medications
import com.dosely.app.reminder.ReminderWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Receiver for the Dosely home-screen widget. */
class DoselyWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = DoselyWidget

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            val pending = goAsync()
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                try {
                    ReminderWorker.scheduleDaily(context)
                    DoselyWidget.updateAll(context)
                } catch (_: Throwable) {
                } finally {
                    pending.finish()
                }
            }
        }
    }

    companion object {
        const val ACTION_REFRESH = "com.dosely.app.ACTION_WIDGET_REFRESH"

        fun schedulePeriodicRefresh(context: Context) {
            val request = androidx.work.PeriodicWorkRequestBuilder<WidgetRefreshWorker>(6, java.util.concurrent.TimeUnit.HOURS)
                .build()
            androidx.work.WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "dosely-widget-refresh",
                androidx.work.ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }
    }
}

/** Recomposes the widget periodically so counters stay fresh. */
class WidgetRefreshWorker(
    context: Context,
    params: androidx.work.WorkerParameters,
) : androidx.work.CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        DoselyWidget.updateAll(applicationContext)
        return Result.success()
    }
}

data class WidgetSnapshot(
    val onboarded: Boolean,
    val nextLabel: String,
    val pens: String,
    val weight: String,
)

object WidgetData {
    suspend fun load(context: Context): WidgetSnapshot {
        val db = DoselyDb.get(context)
        val settingsRepo = SettingsRepository(context)
        val s = settingsRepo.current()
        if (!s.onboarded) {
            return WidgetSnapshot(onboarded = false, nextLabel = "Open Dosely", pens = "—", weight = "—")
        }

        val today = LocalDate.now()
        val injections = db.injectionDao().observeAll().first()
        val next = DoseEngine.nextDose(today, s.intervalDays, s.firstDoseEpochDay, injections)
        val med = Medications.byId(s.medId)
        val weights = db.weightDao().observeAllAsc().first()

        val nextLabel = when {
            next.takenToday -> "Today ✓"
            next.date == today -> "Today"
            next.date == today.plusDays(1) -> "Tomorrow"
            next.overdue -> "Overdue"
            else -> next.date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
        }
        return WidgetSnapshot(
            onboarded = true,
            nextLabel = nextLabel,
            pens = "${s.pensOnHand} · " + med.brand,
            weight = weights.lastOrNull()?.grams?.let { com.dosely.app.domain.Units.format(it / 1000.0, s.useImperial) } ?: "—",
        )
    }
}
