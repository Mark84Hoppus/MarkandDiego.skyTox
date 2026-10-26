// SPDX-FileCopyrightText: 2019-2021 aTox contributors
//
// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.core.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import ltd.evilcorp.core.vo.Message
import ltd.evilcorp.core.vo.Sender

@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun save(message: Message): Long

    @Query("SELECT * FROM messages WHERE conversation == :conversation")
    fun load(conversation: String): Flow<List<Message>>

    @Query(
        "SELECT * FROM (SELECT * FROM messages WHERE conversation == :conversation " +
            "ORDER BY id DESC LIMIT :limit) ORDER BY id ASC",
    )
    fun loadRecent(conversation: String, limit: Int): Flow<List<Message>>

    @Query(
        "SELECT * FROM messages WHERE conversation == :conversation AND id >= COALESCE((" +
            "SELECT MIN(id) FROM (SELECT id FROM messages " +
            "WHERE conversation == :conversation AND id <= :messageId " +
            "ORDER BY id DESC LIMIT :contextLimit)), :messageId) ORDER BY id ASC",
    )
    fun loadFromContextToLatest(conversation: String, messageId: Long, contextLimit: Int): Flow<List<Message>>

    @Query(
        "SELECT * FROM (SELECT * FROM messages WHERE conversation == :conversation " +
            "ORDER BY id DESC LIMIT :limit) ORDER BY id ASC",
    )
    suspend fun pageLatest(conversation: String, limit: Int): List<Message>

    @Query(
        "SELECT * FROM (SELECT * FROM messages WHERE conversation == :conversation AND id < :beforeId " +
            "ORDER BY id DESC LIMIT :limit) ORDER BY id ASC",
    )
    suspend fun pageBefore(conversation: String, beforeId: Long, limit: Int): List<Message>

    @Query(
        "SELECT * FROM messages WHERE conversation == :conversation AND id > :afterId " +
            "ORDER BY id ASC LIMIT :limit",
    )
    suspend fun pageAfter(conversation: String, afterId: Long, limit: Int): List<Message>

    @Query("SELECT * FROM messages WHERE conversation == :conversation AND id == :targetId LIMIT 1")
    suspend fun pageTarget(conversation: String, targetId: Long): Message?

    @Query(
        "SELECT * FROM messages WHERE conversation == :conversation AND id >= :fromId AND id <= :toId " +
            "ORDER BY id ASC",
    )
    suspend fun pageRange(conversation: String, fromId: Long, toId: Long): List<Message>

    @Query("SELECT EXISTS(SELECT 1 FROM messages WHERE conversation == :conversation AND id < :id)")
    suspend fun hasBefore(conversation: String, id: Long): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM messages WHERE conversation == :conversation AND id > :id)")
    suspend fun hasAfter(conversation: String, id: Long): Boolean

    @Query("SELECT COUNT(*) FROM messages WHERE conversation == :conversation")
    fun observeConversationChanges(conversation: String): Flow<Int>

    @Query("SELECT * FROM messages")
    fun loadAll(): Flow<List<Message>>

    @Query(
        "SELECT * FROM messages " +
            "WHERE conversation == :conversation AND sender == :sender AND correlation_id == :pendingCorrelationId",
    )
    fun loadPending(
        conversation: String,
        sender: Sender = Sender.Sent,
        pendingCorrelationId: Int = Int.MIN_VALUE,
    ): List<Message>

    @Query("UPDATE messages SET correlation_id = :correlationId WHERE id == :id")
    fun setCorrelationId(id: Long, correlationId: Int)

    @Query("DELETE FROM messages WHERE conversation == :conversation")
    fun delete(conversation: String)

    @Query("DELETE FROM messages")
    fun deleteAll()

    @Suppress("ktlint:standard:max-line-length")
    @Query(
        "UPDATE messages SET timestamp = :timestamp WHERE conversation == :conversation AND correlation_id == :correlationId AND timestamp == 0",
    )
    fun setReceipt(conversation: String, correlationId: Int, timestamp: Long)

    @Query("DELETE FROM messages WHERE id = :id")
    fun deleteMessage(id: Long)
}
