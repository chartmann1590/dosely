package com.dosely.app.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "injections")
data class InjectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Day the dose was taken, as epoch day (LocalDate.toEpochDay). */
    val epochDay: Long,
    /** Exact moment the user tapped "log dose". */
    val takenAtMillis: Long,
    val medId: String,
    val doseMg: Double,
    /** One of InjectionSite names. */
    val site: String,
    val notes: String = "",
    /** True if the user marked a scheduled dose as skipped (no pen used). */
    val skipped: Boolean = false,
)

@Entity(
    tableName = "weight_entries",
    indices = [Index(value = ["epochDay"], unique = true)],
)
data class WeightEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val epochDay: Long,
    /** Weight stored in grams to avoid float issues. */
    val grams: Int,
    val loggedAtMillis: Long,
)

@Entity(
    tableName = "chat_messages",
    indices = [Index(value = ["chatId"])],
)
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chatId: String = "default",
    val isUser: Boolean,
    val text: String,
    val createdAtMillis: Long,
)
