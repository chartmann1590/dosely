package com.dosely.app

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dosely.app.data.db.*
import com.dosely.app.data.prefs.SettingsRepository
import com.dosely.app.wear.WatchEventImporter
import com.dosely.sync.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class WatchImportTest {
    private lateinit var db: DoselyDb
    private lateinit var settings: SettingsRepository
    private lateinit var importer: WatchEventImporter
    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, DoselyDb::class.java).build()
        settings = SettingsRepository(context)
        importer = WatchEventImporter(db, settings)
    }
    @After fun close() { db.close() }
    private fun event(kind: String = "dose") = WatchEvent(kind = kind, atMillis = System.currentTimeMillis(),
        epochDay = LocalDate.now().toEpochDay(), medId = "semaglutide", doseMg = 0.5, site = WatchContract.sites.first(), grams = 80_000, waterMl = 250)

    @Test fun concurrentDeliveryConsumesOnePenAndCreatesOneShot() = runBlocking {
        settings.setPens(5)
        val shot = event()
        coroutineScope { repeat(8) { launch { assertTrue(importer.import(shot)) } } }
        assertEquals(1, db.injectionDao().count())
        assertEquals(4, settings.current().pensOnHand)
    }
    @Test fun replayDoesNotResurrectDeletedShot() = runBlocking {
        val shot = event()
        importer.import(shot)
        db.injectionDao().delete(db.injectionDao().allAsc().single())
        importer.import(shot)
        assertEquals(0, db.injectionDao().count())
    }
    @Test fun olderOfflineWeightCannotOverwriteNewerPhoneEntry() = runBlocking {
        val weight = event("weight")
        db.weightDao().upsert(WeightEntryEntity(epochDay = weight.epochDay, grams = 79_000, loggedAtMillis = weight.atMillis + 1000))
        importer.import(weight)
        assertEquals(79_000, db.weightDao().observeAllAsc().first().single().grams)
    }
    @Test fun waterReplayIsNotAddedTwice() = runBlocking {
        val water = event("water")
        importer.import(water); importer.import(water)
        assertEquals(250, db.journalDao().observeAll().first().sumOf { it.waterMl })
    }
    @Test fun malformedDoseIsRejectedWithoutReceipt() = runBlocking {
        val shot = event().copy(doseMg = -1.0)
        assertFalse(importer.import(shot))
        assertFalse(db.journalDao().received(shot.id))
        assertEquals(0, db.injectionDao().count())
    }
}
