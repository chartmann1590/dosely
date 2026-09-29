package com.dosely.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [InjectionEntity::class, WeightEntryEntity::class, ChatMessageEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class DoselyDb : RoomDatabase() {
    abstract fun injectionDao(): InjectionDao
    abstract fun weightDao(): WeightDao
    abstract fun chatDao(): ChatDao

    companion object {
        @Volatile private var instance: DoselyDb? = null

        fun get(context: Context): DoselyDb =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    DoselyDb::class.java,
                    "dosely.db",
                ).build().also { instance = it }
            }
    }
}
