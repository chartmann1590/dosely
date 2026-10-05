package com.dosely.app.data.repo

import com.dosely.app.data.db.ChatMessageEntity
import com.dosely.app.data.db.DoselyDb
import com.dosely.app.data.db.InjectionEntity
import com.dosely.app.data.db.WeightEntryEntity
import com.dosely.app.data.prefs.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

class DoselyRepository(
    db: DoselyDb,
    val settings: SettingsRepository,
) {
    private val injectionDao = db.injectionDao()
    private val weightDao = db.weightDao()
    private val chatDao = db.chatDao()
    private val journalDao = db.journalDao()

    val injections: Flow<List<InjectionEntity>> = injectionDao.observeAll()
    val weights: Flow<List<WeightEntryEntity>> = weightDao.observeAllAsc()
    val journalEntries: Flow<List<com.dosely.app.data.db.JournalEntity>> = journalDao.observeAll()

    suspend fun addWater(ml: Int, epochDay: Long = java.time.LocalDate.now().toEpochDay()) {
        val today = journalDao.observeAll().first().firstOrNull { it.epochDay == epochDay }
        if (today != null) {
            journalDao.upsert(today.copy(waterMl = today.waterMl + ml, loggedAtMillis = System.currentTimeMillis()))
        } else {
            journalDao.upsert(com.dosely.app.data.db.JournalEntity(epochDay = epochDay, loggedAtMillis = System.currentTimeMillis(), waterMl = ml))
        }
    }

    suspend fun addProtein(grams: Int, epochDay: Long = java.time.LocalDate.now().toEpochDay()) {
        val today = journalDao.observeAll().first().firstOrNull { it.epochDay == epochDay }
        if (today != null) {
            journalDao.upsert(today.copy(proteinGrams = today.proteinGrams + grams, loggedAtMillis = System.currentTimeMillis()))
        } else {
            journalDao.upsert(com.dosely.app.data.db.JournalEntity(epochDay = epochDay, loggedAtMillis = System.currentTimeMillis(), proteinGrams = grams))
        }
    }

    suspend fun logQuickSymptom(symptom: String, epochDay: Long = java.time.LocalDate.now().toEpochDay()) {
        journalDao.upsert(com.dosely.app.data.db.JournalEntity(epochDay = epochDay, loggedAtMillis = System.currentTimeMillis(), symptom = symptom, severity = 1))
    }

    suspend fun logInjection(entry: InjectionEntity) {
        injectionDao.insert(entry)
        // One pen per injection unless skipped.
        if (!entry.skipped) {
            settings.adjustPens(-1)
        }
    }

    suspend fun deleteInjection(entry: InjectionEntity) {
        val deleted = injectionDao.delete(entry)
        if (deleted > 0 && !entry.skipped) {
            settings.adjustPens(1)
        }
    }

    suspend fun updateInjection(entry: InjectionEntity) = injectionDao.insert(entry)

    suspend fun setPens(count: Int) = settings.setPens(count)

    suspend fun upsertWeight(entry: WeightEntryEntity) = weightDao.upsert(entry)
    suspend fun deleteWeight(id: Long) = weightDao.deleteById(id)

    val chat: Flow<List<ChatMessageEntity>> = chatDao.observeChat("default")
    suspend fun addChatMessage(isUser: Boolean, text: String) =
        chatDao.insert(ChatMessageEntity(isUser = isUser, text = text, createdAtMillis = System.currentTimeMillis()))
    suspend fun clearChat() = chatDao.clear("default")
    suspend fun deleteChatMessage(id: Long) = chatDao.deleteById(id)
}
