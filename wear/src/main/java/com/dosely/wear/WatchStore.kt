package com.dosely.wear

import android.content.Context
import androidx.work.*
import com.dosely.sync.*
import com.google.android.gms.wearable.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.encodeToString

class WatchStore(context: Context) {
    private val prefs = context.getSharedPreferences("watch", Context.MODE_PRIVATE)
    val snapshot = MutableStateFlow(readSnapshot())
    val pending = MutableStateFlow(prefs.all.keys.count { it.startsWith("event:") })
    private fun readSnapshot() = runCatching {
        WatchContract.json.decodeFromString<WatchSnapshot>(prefs.getString("snapshot", "")!!)
    }.getOrDefault(WatchSnapshot())
    @Synchronized fun save(event: WatchEvent) {
        check(event.isValid(System.currentTimeMillis()))
        check(prefs.edit().putString("event:${event.id}", WatchContract.json.encodeToString(event)).commit())
        pending.value = events().size
    }
    @Synchronized fun events(): List<String> = prefs.all.filterKeys { it.startsWith("event:") }.values.filterIsInstance<String>()
    @Synchronized fun acknowledge(id: String) {
        check(prefs.edit().remove("event:$id").commit())
        pending.value = events().size
    }
    fun update(raw: String) {
        val decoded = runCatching { WatchContract.json.decodeFromString<WatchSnapshot>(raw) }.getOrNull() ?: return
        if (decoded.version != 1 || decoded.updatedAt < snapshot.value.updatedAt) return
        check(prefs.edit().putString("snapshot", raw).commit())
        snapshot.value = decoded
    }
    fun useDemoSnapshot() {
        val demo = WatchSnapshot(
            version = 1,
            onboarded = true,
            medId = "semaglutide",
            medName = "Semaglutide",
            doses = listOf(0.25, 0.5, 1.0, 1.7, 2.4),
            lastDoseMg = 0.25,
            lastSite = "Abdomen · left",
            nextDose = "Today",
            weightGrams = 88_500,
            imperial = false,
            updatedAt = System.currentTimeMillis(),
        )
        prefs.edit().putString("snapshot", WatchContract.json.encodeToString(demo)).apply()
        snapshot.value = demo
    }
    companion object {
        @Volatile private var instance: WatchStore? = null
        fun get(context: Context): WatchStore = instance ?: synchronized(this) {
            instance ?: WatchStore(context.applicationContext).also { instance = it }
        }
        fun sync(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork("watch-upload", ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<WatchSyncWorker>().build())
        }
    }
}

class WatchListener : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        if (events.any { it.type == DataEvent.TYPE_CHANGED &&
            (it.dataItem.uri.path == WatchContract.SNAPSHOT || it.dataItem.uri.path.orEmpty().startsWith(WatchContract.ACK)) }) {
            WatchStore.sync(this)
        }
    }
    override fun onPeerConnected(peer: Node) { WatchStore.sync(this) }
}

class WatchSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        val store = WatchStore.get(applicationContext)
        val client = Wearable.getDataClient(applicationContext)
        val items = client.dataItems.await()
        try {
            for (item in items) {
                when {
                    item.uri.path == WatchContract.SNAPSHOT -> DataMapItem.fromDataItem(item).dataMap
                        .getString(WatchContract.PAYLOAD)?.let(store::update)
                    item.uri.path.orEmpty().startsWith(WatchContract.ACK) -> {
                        val id = item.uri.lastPathSegment ?: continue
                        store.acknowledge(id)
                        // Remove acknowledged transport data; phone keeps a durable receipt.
                        client.deleteDataItems(android.net.Uri.parse("wear://*${WatchContract.EVENT}$id"), DataClient.FILTER_LITERAL).await()
                    }
                }
            }
        } finally { items.release() }
        for (raw in store.events()) {
            val event = WatchContract.json.decodeFromString<WatchEvent>(raw)
            client.putDataItem(PutDataMapRequest.create(WatchContract.EVENT + event.id).apply {
                dataMap.putString(WatchContract.PAYLOAD, raw)
            }.asPutDataRequest().setUrgent()).await()
        }
        Result.success()
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        Result.retry()
    }
}
