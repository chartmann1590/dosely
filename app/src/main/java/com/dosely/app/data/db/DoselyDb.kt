package com.dosely.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [InjectionEntity::class, WeightEntryEntity::class, ChatMessageEntity::class, JournalEntity::class, WatchReceipt::class, DosePlanEntity::class],
    version = 3,
    exportSchema = true,
)
abstract class DoselyDb : RoomDatabase() {
    abstract fun injectionDao(): InjectionDao
    abstract fun weightDao(): WeightDao
    abstract fun chatDao(): ChatDao
    abstract fun journalDao(): JournalDao

    companion object {
        @Volatile private var instance: DoselyDb? = null

        fun get(context: Context): DoselyDb =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    DoselyDb::class.java,
                    "dosely.db",
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { instance = it }
            }

        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS journal_entries (id TEXT NOT NULL PRIMARY KEY, epochDay INTEGER NOT NULL, loggedAtMillis INTEGER NOT NULL, symptom TEXT NOT NULL, severity INTEGER NOT NULL, waterMl INTEGER NOT NULL, calories INTEGER NOT NULL, proteinGrams INTEGER NOT NULL, appetite INTEGER NOT NULL, foodNoise INTEGER NOT NULL, notes TEXT NOT NULL, photoUri TEXT NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS watch_receipts (id TEXT NOT NULL PRIMARY KEY, receivedAt INTEGER NOT NULL)")
            }
        }

        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS dose_plans (id TEXT NOT NULL PRIMARY KEY, epochDay INTEGER NOT NULL, medId TEXT NOT NULL, doseMg REAL NOT NULL, notes TEXT NOT NULL)")
            }
        }
    }
}
