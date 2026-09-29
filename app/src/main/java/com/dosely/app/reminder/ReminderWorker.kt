package com.dosely.app.reminder

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.dosely.app.data.prefs.SettingsRepository
import com.dosely.app.data.repo.DoselyRepository
import com.dosely.app.domain.DoseEngine
import com.dosely.app.domain.Medications
import com.dosely.app.translate.L
import kotlinx.coroutines.flow.first
import org.koin.core.context.GlobalContext
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/**
 * A lightweight daily worker that checks whether reminders are due
 * (dose due, stock low/out, weekly weigh-in) and posts notifications.
 */
class ReminderWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val koin = GlobalContext.get()
        val settingsRepo = koin.get<SettingsRepository>()
        val repo = koin.get<DoselyRepository>()
        val ctx = applicationContext
        val s = settingsRepo.current()
        if (!s.onboarded) return Result.success()

        val today = LocalDate.now()
        val injections = repo.injections.first()
        val next = DoseEngine.nextDose(today, s.intervalDays, s.firstDoseEpochDay, injections)
        val stock = DoseEngine.stockStatus(today, s.pensOnHand, s.lowStockThreshold, s.intervalDays, injections)
        val med = Medications.byId(s.medId)

        // Dose reminder
        if (s.remindersEnabled) {
            val dueToday = next.date == today || next.overdue
            val alreadyTaken = injections.any { it.epochDay == today.toEpochDay() && !it.skipped }
            if (dueToday && !alreadyTaken && Notifications.canNotify(ctx)) {
                Notifications.post(
                    ctx, Notifications.CHANNEL_DOSE, ID_DOSE,
                    L(ctx, "notif_dose_title"),
                    L(ctx, "notif_dose_body", med.brand),
                    "doses",
                )
            }
        }

        // Stock alerts
        if (s.refillRemindersEnabled && Notifications.canNotify(ctx)) {
            if (stock.out) {
                Notifications.post(
                    ctx, Notifications.CHANNEL_STOCK, ID_STOCK,
                    L(ctx, "notif_out_title"),
                    L(ctx, "notif_out_body", next.date.toString()),
                    "home",
                )
            } else if (stock.low) {
                Notifications.post(
                    ctx, Notifications.CHANNEL_STOCK, ID_STOCK,
                    L(ctx, "notif_refill_title"),
                    L(ctx, "notif_refill_body", stock.pens),
                    "home",
                )
            }
        }

        // Weekly weigh-in on Sundays
        if (s.weeklyWeighInEnabled && today.dayOfWeek == DayOfWeek.SUNDAY && Notifications.canNotify(ctx)) {
            Notifications.post(
                ctx, Notifications.CHANNEL_WEEKLY, ID_WEEKLY,
                L(ctx, "notif_weekly_title"),
                L(ctx, "notif_weekly_body"),
                "weight",
            )
        }

        return Result.success()
    }

    companion object {
        const val ID_DOSE = 2001
        const val ID_STOCK = 2002
        const val ID_WEEKLY = 2003
        const val TAG = "dosely-reminders"

        fun scheduleDaily(context: Context) {
            val request = PeriodicWorkRequestBuilder<ReminderWorker>(1, TimeUnit.DAYS)
                .setInitialDelay(delayToNextNineAm(), TimeUnit.MILLISECONDS)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .addTag(TAG)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "dosely-daily-reminders",
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }

        private fun delayToNextNineAm(): Long {
            val now = LocalDateTime.now()
            var next = LocalDateTime.of(LocalDate.now(), LocalTime.of(9, 0))
            if (!next.isAfter(now)) next = next.plusDays(1)
            return Duration.between(now, next).toMillis()
        }
    }
}
