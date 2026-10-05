package com.dosely.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dosely.app.data.db.*
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @Test fun upgradesVersionOneWithoutLosingHealthHistory() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-${UUID.randomUUID()}.db"
        val path = context.getDatabasePath(name)
        path.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            old.execSQL("CREATE TABLE injections (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, epochDay INTEGER NOT NULL, takenAtMillis INTEGER NOT NULL, medId TEXT NOT NULL, doseMg REAL NOT NULL, site TEXT NOT NULL, notes TEXT NOT NULL, skipped INTEGER NOT NULL)")
            old.execSQL("CREATE TABLE weight_entries (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, epochDay INTEGER NOT NULL, grams INTEGER NOT NULL, loggedAtMillis INTEGER NOT NULL)")
            old.execSQL("CREATE UNIQUE INDEX index_weight_entries_epochDay ON weight_entries(epochDay)")
            old.execSQL("CREATE TABLE chat_messages (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, chatId TEXT NOT NULL, isUser INTEGER NOT NULL, text TEXT NOT NULL, createdAtMillis INTEGER NOT NULL)")
            old.execSQL("CREATE INDEX index_chat_messages_chatId ON chat_messages(chatId)")
            old.execSQL("INSERT INTO injections VALUES (1,20000,1728000000000,'semaglutide',0.5,'Abdomen','keep this',0)")
            old.execSQL("INSERT INTO weight_entries VALUES (1,20000,80000,1728000000000)")
            old.version = 1
        }
        val db = Room.databaseBuilder(context, DoselyDb::class.java, name).addMigrations(DoselyDb.MIGRATION_1_2, DoselyDb.MIGRATION_2_3).build()
        try {
            assertEquals("keep this", db.injectionDao().allAsc().single().notes)
            db.journalDao().upsert(JournalEntity(epochDay = 20000, loggedAtMillis = 1728000000000, waterMl = 250))
            db.journalDao().receipt(WatchReceipt("test", 1728000000000))
            db.journalDao().savePlan(DosePlanEntity(epochDay = 20007, medId = "semaglutide", doseMg = 0.5))
            assertTrue(db.journalDao().received("test"))
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
