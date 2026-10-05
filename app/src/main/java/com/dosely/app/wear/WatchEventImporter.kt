package com.dosely.app.wear

import androidx.room.withTransaction
import com.dosely.app.data.db.*
import com.dosely.app.data.prefs.SettingsRepository
import com.dosely.app.domain.Medications
import com.dosely.sync.WatchEvent
import kotlinx.coroutines.flow.first

class WatchEventImporter(private val db: DoselyDb, private val settings: SettingsRepository) {
    suspend fun import(event: WatchEvent, now: Long = System.currentTimeMillis()): Boolean {
        if (!event.isValid(now)) return false
        if (event.kind == "dose" && Medications.all.none { it.id == event.medId }) return false
        db.withTransaction {
            if (!db.journalDao().received(event.id)) {
                when (event.kind) {
                    "dose" -> db.injectionDao().insert(InjectionEntity(epochDay = event.epochDay,
                        takenAtMillis = event.atMillis, medId = event.medId, doseMg = event.doseMg,
                        site = event.site, notes = "Logged on watch"))
                    "weight" -> {
                        val existing = db.weightDao().observeAllAsc().first().firstOrNull { it.epochDay == event.epochDay }
                        if (existing == null || existing.loggedAtMillis < event.atMillis)
                            db.weightDao().upsert(WeightEntryEntity(epochDay = event.epochDay, grams = event.grams, loggedAtMillis = event.atMillis))
                    }
                    "water" -> db.journalDao().upsert(JournalEntity(id = event.id, epochDay = event.epochDay,
                        loggedAtMillis = event.atMillis, waterMl = event.waterMl, notes = "Logged on watch"))
                }
                db.journalDao().receipt(WatchReceipt(event.id, now))
            }
        }
        if (event.kind == "dose") settings.consumeWatchPenOnce(event.id)
        return true
    }
}
