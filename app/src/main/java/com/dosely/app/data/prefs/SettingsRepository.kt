package com.dosely.app.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "dosely_settings")

data class Settings(
    val onboarded: Boolean = false,
    val medId: String = "semaglutide",
    val intervalDays: Int = 7,
    val reminderHour: Int = 9,
    val reminderMinute: Int = 0,
    val firstDoseEpochDay: Long = 0,
    val pensOnHand: Int = 2,
    val lowStockThreshold: Int = 1,
    val startWeightGrams: Int = 0,
    val goalWeightGrams: Int = 0,
    val useImperial: Boolean = false,
    /** One of: system, light, dark. */
    val themeMode: String = "system",
    /** BCP-47 tag of the selected app language, empty = system default. */
    val appLanguageTag: String = "",
    val remindersEnabled: Boolean = true,
    val refillRemindersEnabled: Boolean = true,
    val weeklyWeighInEnabled: Boolean = true,
    val coachDisclaimerAck: Boolean = false,
)

class SettingsRepository(private val context: Context) {

    private object K {
        val ONBOARDED = booleanPreferencesKey("onboarded")
        val MED_ID = stringPreferencesKey("med_id")
        val INTERVAL = intPreferencesKey("interval_days")
        val REM_HOUR = intPreferencesKey("rem_hour")
        val REM_MIN = intPreferencesKey("rem_min")
        val FIRST_DOSE = stringPreferencesKey("first_dose_epoch_day")
        val PENS = intPreferencesKey("pens_on_hand")
        val LOW_STOCK = intPreferencesKey("low_stock_threshold")
        val START_W = intPreferencesKey("start_weight_grams")
        val GOAL_W = intPreferencesKey("goal_weight_grams")
        val IMPERIAL = booleanPreferencesKey("use_imperial")
        val THEME = stringPreferencesKey("theme_mode")
        val LANG = stringPreferencesKey("app_language_tag")
        val REM_ENABLED = booleanPreferencesKey("reminders_enabled")
        val REFILL_ENABLED = booleanPreferencesKey("refill_enabled")
        val WEEKLY_ENABLED = booleanPreferencesKey("weekly_enabled")
        val COACH_ACK = booleanPreferencesKey("coach_disclaimer_ack")
    }

    val settings: Flow<Settings> = context.dataStore.data.map { p ->
        Settings(
            onboarded = p[K.ONBOARDED] ?: false,
            medId = p[K.MED_ID] ?: "semaglutide",
            intervalDays = p[K.INTERVAL] ?: 7,
            reminderHour = p[K.REM_HOUR] ?: 9,
            reminderMinute = p[K.REM_MIN] ?: 0,
            firstDoseEpochDay = p[K.FIRST_DOSE]?.toLongOrNull() ?: 0L,
            pensOnHand = p[K.PENS] ?: 2,
            lowStockThreshold = p[K.LOW_STOCK] ?: 1,
            startWeightGrams = p[K.START_W] ?: 0,
            goalWeightGrams = p[K.GOAL_W] ?: 0,
            useImperial = p[K.IMPERIAL] ?: false,
            themeMode = p[K.THEME] ?: "system",
            appLanguageTag = p[K.LANG] ?: "",
            remindersEnabled = p[K.REM_ENABLED] ?: true,
            refillRemindersEnabled = p[K.REFILL_ENABLED] ?: true,
            weeklyWeighInEnabled = p[K.WEEKLY_ENABLED] ?: true,
            coachDisclaimerAck = p[K.COACH_ACK] ?: false,
        )
    }

    suspend fun current(): Settings = settings.first()

    suspend fun setOnboarded(value: Boolean) = edit { it[K.ONBOARDED] = value }
    suspend fun setMed(id: String) = edit { it[K.MED_ID] = id }
    suspend fun setInterval(days: Int) = edit { it[K.INTERVAL] = days.coerceIn(1, 365) }
    suspend fun setReminderTime(hour: Int, minute: Int) {
        edit {
            it[K.REM_HOUR] = hour
            it[K.REM_MIN] = minute
        }
        com.dosely.app.reminder.ReminderWorker.scheduleDaily(context, hour, minute)
    }
    suspend fun setFirstDoseDay(epochDay: Long) = edit { it[K.FIRST_DOSE] = epochDay.toString() }
    suspend fun setPens(count: Int) = edit { it[K.PENS] = count }
    suspend fun adjustPens(delta: Int) = edit { it[K.PENS] = ((it[K.PENS] ?: 2) + delta).coerceAtLeast(0) }
    /** Atomic inventory adjustment and marker; safe to retry after process death. */
    suspend fun consumeWatchPenOnce(eventId: String) = edit {
        val marker = booleanPreferencesKey("watch_stock_$eventId")
        if (it[marker] != true) {
            it[K.PENS] = ((it[K.PENS] ?: 2) - 1).coerceAtLeast(0)
            it[marker] = true
        }
    }
    suspend fun setLowStockThreshold(n: Int) = edit { it[K.LOW_STOCK] = n }
    suspend fun setStartWeightGrams(g: Int) = edit { it[K.START_W] = g }
    suspend fun setGoalWeightGrams(g: Int) = edit { it[K.GOAL_W] = g }
    suspend fun setImperial(value: Boolean) = edit { it[K.IMPERIAL] = value }
    suspend fun setTheme(mode: String) = edit { it[K.THEME] = mode }
    suspend fun setAppLanguage(tag: String) = edit { it[K.LANG] = tag }
    suspend fun setRemindersEnabled(value: Boolean) = edit { it[K.REM_ENABLED] = value }
    suspend fun setRefillEnabled(value: Boolean) = edit { it[K.REFILL_ENABLED] = value }
    suspend fun setWeeklyEnabled(value: Boolean) = edit { it[K.WEEKLY_ENABLED] = value }
    suspend fun setCoachDisclaimerAck(value: Boolean) = edit { it[K.COACH_ACK] = value }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit { block(it) }
    }
}
