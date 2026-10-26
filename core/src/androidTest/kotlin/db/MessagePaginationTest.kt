// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.core.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import ltd.evilcorp.core.vo.Message
import ltd.evilcorp.core.vo.MessageType
import ltd.evilcorp.core.vo.Sender
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MessagePaginationTest {
    private lateinit var database: Database
    private lateinit var dao: MessageDao

    @BeforeTest
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            Database::class.java,
        ).allowMainThreadQueries().build()
        dao = database.messageDao()
    }

    @AfterTest
    fun tearDown() = database.close()

    @Test
    fun pagesUseStableIdsWithoutDuplicates() = runBlocking {
        val ids = (1..100).map { index ->
            dao.save(Message(CHAT, "message-$index", Sender.Received, MessageType.Normal, 0, 123L))
        }
        repeat(5) { dao.save(Message(OTHER_CHAT, "other-$it", Sender.Received, MessageType.Normal, 0, 123L)) }

        val latest = dao.pageLatest(CHAT, 30)
        assertEquals(ids.takeLast(30), latest.map { it.id })

        val before = dao.pageBefore(CHAT, latest.first().id, 40)
        assertEquals(ids.drop(30).take(40), before.map { it.id })

        val targetId = ids[49]
        assertEquals(targetId, dao.pageTarget(CHAT, targetId)?.id)
        assertNull(dao.pageTarget(OTHER_CHAT, targetId))
        assertEquals(ids.drop(50).take(30), dao.pageAfter(CHAT, targetId, 30).map { it.id })
        assertTrue(dao.hasBefore(CHAT, ids.first() + 1))
        assertFalse(dao.hasBefore(CHAT, ids.first()))
        assertTrue(dao.hasAfter(CHAT, ids.last() - 1))
        assertFalse(dao.hasAfter(CHAT, ids.last()))
    }

    @Test
    fun deletedIdsDoNotBreakKeysetPaging() = runBlocking {
        val ids = (1..31).map { index ->
            dao.save(Message(CHAT, "message-$index", Sender.Sent, MessageType.Normal, index, 42L))
        }
        dao.deleteMessage(ids[15])

        val all = dao.pageLatest(CHAT, 40)
        assertEquals(30, all.size)
        assertFalse(all.any { it.id == ids[15] })
        assertEquals(all.map { it.id }.distinct(), all.map { it.id })
    }

    private companion object {
        const val CHAT = "chat-a"
        const val OTHER_CHAT = "chat-b"
    }
}
