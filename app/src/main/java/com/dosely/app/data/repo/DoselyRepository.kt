package com.dosely.app.data.repo

import com.dosely.app.data.db.ChatMessageEntity
import com.dosely.app.data.db.DoselyDb
import com.dosely.app.data.db.InjectionEntity
import com.dosely.app.data.db.WeightEntryEntity
import com.dosely.app.data.prefs.SettingsRepository
import kotlinx.coroutines.flow.Flow

class DoselyRepository(
    db: DoselyDb,
    val settings: SettingsRepository,
) {
    private val injectionDao = db.injectionDao()
    private val weightDao = db.weightDao()
    private val chatDao = db.chatDao()

    val injections: Flow<List<InjectionEntity>> = injectionDao.observeAll()
    val weights: Flow<List<WeightEntryEntity>> = weightDao.observeAllAsc()

    suspend fun logInjection(entry: InjectionEntity) {
        injectionDao.insert(entry)
        // One pen per injection unless skipped.
        if (!entry.skipped) {
            val s = settings.current()
            if (s.pensOnHand > 0) settings.setPens(s.pensOnHand - 1)
        }
    }

    suspend fun deleteInjection(entry: InjectionEntity) {
        injectionDao.delete(entry)
        if (!entry.skipped) {
            val s = settings.current()
            settings.setPens(s.pensOnHand + 1)
        }
    }

    suspend fun setPens(count: Int) = settings.setPens(count)

    suspend fun upsertWeight(entry: WeightEntryEntity) = weightDao.upsert(entry)
    suspend fun deleteWeight(id: Long) = weightDao.deleteById(id)

    val chat: Flow<List<ChatMessageEntity>> = chatDao.observeChat("default")
    suspend fun addChatMessage(isUser: Boolean, text: String) =
        chatDao.insert(ChatMessageEntity(isUser = isUser, text = text, createdAtMillis = System.currentTimeMillis()))
    suspend fun clearChat() = chatDao.clear("default")
    suspend fun deleteChatMessage(id: Long) = chatDao.deleteById(id)
}
