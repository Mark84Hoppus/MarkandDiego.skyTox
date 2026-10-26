// SPDX-FileCopyrightText: 2019-2020 aTox contributors
//
// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.core.repository

import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import ltd.evilcorp.core.db.MessageDao
import ltd.evilcorp.core.vo.Message

@Singleton
class MessageRepository @Inject internal constructor(
    private val messageDao: MessageDao,
    private val contactRepository: ContactRepository,
) {
    fun add(message: Message): Long {
        val id = messageDao.save(message)
        contactRepository.setLastMessage(message.publicKey, Date().time)
        return id
    }

    fun addLocal(message: Message): Long {
        return messageDao.save(message)
    }

    fun get(conversation: String): Flow<List<Message>> = messageDao.load(conversation)
    fun getRecent(conversation: String, limit: Int): Flow<List<Message>> = messageDao.loadRecent(conversation, limit)
    fun getFromContextToLatest(conversation: String, messageId: Long, contextLimit: Int): Flow<List<Message>> =
        messageDao.loadFromContextToLatest(conversation, messageId, contextLimit)

    suspend fun pageLatest(conversation: String, limit: Int) = messageDao.pageLatest(conversation, limit)
    suspend fun pageBefore(conversation: String, beforeId: Long, limit: Int) =
        messageDao.pageBefore(conversation, beforeId, limit)
    suspend fun pageAfter(conversation: String, afterId: Long, limit: Int) =
        messageDao.pageAfter(conversation, afterId, limit)
    suspend fun pageTarget(conversation: String, targetId: Long) = messageDao.pageTarget(conversation, targetId)
    suspend fun pageRange(conversation: String, fromId: Long, toId: Long) =
        messageDao.pageRange(conversation, fromId, toId)
    suspend fun hasBefore(conversation: String, id: Long) = messageDao.hasBefore(conversation, id)
    suspend fun hasAfter(conversation: String, id: Long) = messageDao.hasAfter(conversation, id)
    fun observeConversationChanges(conversation: String): Flow<Int> = messageDao.observeConversationChanges(conversation)

    fun getAll(): Flow<List<Message>> = messageDao.loadAll()

    fun getPending(conversation: String): List<Message> = messageDao.loadPending(conversation)

    fun setCorrelationId(id: Long, correlationId: Int) = messageDao.setCorrelationId(id, correlationId)

    fun delete(conversation: String) = messageDao.delete(conversation)

    fun deleteAll() = messageDao.deleteAll()

    fun deleteMessage(id: Long) = messageDao.deleteMessage(id)

    fun setReceipt(conversation: String, correlationId: Int, timestamp: Long) =
        messageDao.setReceipt(conversation, correlationId, timestamp)
}
