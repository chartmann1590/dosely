package com.dosely.app

import android.app.Application
import kotlinx.coroutines.launch
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.dosely.app.di.appModule
import com.dosely.app.reminder.Notifications
import com.dosely.app.reminder.ReminderWorker
import com.dosely.app.translate.LocalizerHolder
import com.dosely.app.translate.TranslationService
import com.dosely.app.widget.DoselyWidgetReceiver
import com.hartmann.crosspromo.HartmannCrossPromo
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.java.KoinJavaComponent.getKoin
import java.util.concurrent.TimeUnit

class DoselyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@DoselyApp)
            modules(appModule)
        }
        LocalizerHolder.init(getKoin().get<TranslationService>())
        Notifications.ensureChannels(this)
        ReminderWorker.scheduleDaily(this)
        DoselyWidgetReceiver.schedulePeriodicRefresh(this)
        com.dosely.app.wear.PhoneWatchSync.enqueue(this)
        val syncScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        syncScope.launch {
            val db = getKoin().get<com.dosely.app.data.db.DoselyDb>()
            kotlinx.coroutines.flow.combine(
                db.injectionDao().observeAll(), db.weightDao().observeAllAsc(),
                getKoin().get<com.dosely.app.data.prefs.SettingsRepository>().settings,
            ) { _, _, _ -> Unit }.collect { com.dosely.app.wear.PhoneWatchSync.enqueue(this@DoselyApp) }
        }

        // Hartmann Studios cross-promotion (dynamic catalog from the backend;
        // fails silently — never affects app functionality).
        HartmannCrossPromo.initialize(
            application = this,
            apiBaseUrl = "https://crosspromo.charleshartmann.com",
            enableFirebaseAnalytics = false,
            enableBackendAnalytics = true,
        )
    }
}
