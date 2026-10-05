package com.dosely.app.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface InjectionDao {
    @Query("SELECT * FROM injections ORDER BY epochDay DESC, takenAtMillis DESC")
    fun observeAll(): Flow<List<InjectionEntity>>

    @Query("SELECT * FROM injections ORDER BY epochDay ASC, takenAtMillis ASC")
    suspend fun allAsc(): List<InjectionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: InjectionEntity): Long

    @Delete
    suspend fun delete(entry: InjectionEntity): Int

    @Query("SELECT COUNT(*) FROM injections")
    suspend fun count(): Int
}

@Dao
interface WeightDao {
    @Query("SELECT * FROM weight_entries ORDER BY epochDay ASC")
    fun observeAllAsc(): Flow<List<WeightEntryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: WeightEntryEntity): Long

    @Query("DELETE FROM weight_entries WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface ChatDao {
    @Query("SELECT * FROM chat_messages WHERE chatId = :chatId ORDER BY createdAtMillis ASC, id ASC")
    fun observeChat(chatId: String): Flow<List<ChatMessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: ChatMessageEntity): Long

    @Query("DELETE FROM chat_messages WHERE chatId = :chatId")
    suspend fun clear(chatId: String)

    @Query("DELETE FROM chat_messages WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface JournalDao {
    @Query("SELECT * FROM dose_plans ORDER BY epochDay ASC")
    fun observePlans(): Flow<List<DosePlanEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun savePlan(plan: DosePlanEntity)

    @Query("DELETE FROM dose_plans WHERE id = :id")
    suspend fun deletePlan(id: String)

    @Query("SELECT * FROM journal_entries ORDER BY epochDay DESC, loggedAtMillis DESC")
    fun observeAll(): Flow<List<JournalEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: JournalEntity)

    @Query("DELETE FROM journal_entries WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT EXISTS(SELECT 1 FROM watch_receipts WHERE id = :id)")
    suspend fun received(id: String): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun receipt(receipt: WatchReceipt): Long
}
