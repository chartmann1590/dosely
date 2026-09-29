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
    suspend fun delete(entry: InjectionEntity)

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
