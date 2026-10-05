package com.dosely.app.wear

import android.content.Context
import androidx.room.withTransaction
import androidx.work.*
import com.dosely.app.data.db.*
import com.dosely.app.data.prefs.SettingsRepository
import com.dosely.app.domain.DoseEngine
import com.dosely.app.domain.Medications
import com.dosely.sync.*
import com.google.android.gms.wearable.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.encodeToString
import java.time.LocalDate

class PhoneWatchListener : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        if (events.any { it.type == DataEvent.TYPE_CHANGED && it.dataItem.uri.path.orEmpty().startsWith(WatchContract.EVENT) }) {
            PhoneWatchSync.enqueue(this)
        }
    }
    override fun onPeerConnected(peer: Node) { PhoneWatchSync.enqueue(this) }
}

object PhoneWatchSync {
    fun enqueue(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            "watch-sync", ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<PhoneWatchWorker>().build(),
        )
    }
}

class PhoneWatchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        val db = DoselyDb.get(applicationContext)
        val settings = SettingsRepository(applicationContext)
        val client = Wearable.getDataClient(applicationContext)
        val items = client.dataItems.await()
        try {
            for (item in items) {
                if (!item.uri.path.orEmpty().startsWith(WatchContract.EVENT)) continue
                val raw = DataMapItem.fromDataItem(item).dataMap.getString(WatchContract.PAYLOAD) ?: continue
                val event = runCatching { WatchContract.json.decodeFromString<WatchEvent>(raw) }.getOrNull() ?: continue
                if (item.uri.path != WatchContract.EVENT + event.id) continue
                if (!WatchEventImporter(db, settings).import(event)) continue
                // Only acknowledge after the transaction and inventory update have completed.
                client.putDataItem(PutDataMapRequest.create(WatchContract.ACK + event.id).apply {
                    dataMap.putString("id", event.id)
                }.asPutDataRequest().setUrgent()).await()
            }
        } finally { items.release() }
        val s = settings.current()
        val history = db.injectionDao().allAsc()
        val last = history.lastOrNull { !it.skipped && it.medId == s.medId }
        val med = Medications.byId(s.medId)
        val snapshot = WatchSnapshot(onboarded = s.onboarded, medId = med.id, medName = med.brand,
            doses = med.doses, lastDoseMg = last?.doseMg, lastSite = last?.site.orEmpty(),
            nextDose = DoseEngine.nextDose(LocalDate.now(), s.intervalDays, s.firstDoseEpochDay, history.filter { it.medId == s.medId }).date.toString(),
            weightGrams = db.weightDao().observeAllAsc().first().lastOrNull()?.grams ?: s.startWeightGrams,
            imperial = s.useImperial, updatedAt = System.currentTimeMillis())
        client.putDataItem(PutDataMapRequest.create(WatchContract.SNAPSHOT).apply {
            dataMap.putString(WatchContract.PAYLOAD, WatchContract.json.encodeToString(snapshot))
        }.asPutDataRequest().setUrgent()).await()
        Result.success()
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        if (runAttemptCount < 5) Result.retry() else Result.failure()
    }
}
