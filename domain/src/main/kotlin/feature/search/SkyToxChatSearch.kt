// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.domain.feature.search

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import ltd.evilcorp.core.repository.ContactRepository
import ltd.evilcorp.core.repository.MessageRepository
import ltd.evilcorp.core.vo.MessageType

@Singleton
class SkyToxChatSearch @Inject constructor(
    private val contactRepository: ContactRepository,
    private val messageRepository: MessageRepository,
) {
    suspend fun search(query: String): List<Result> {
        if (!ENABLED) return emptyList()
        val normalized = query.trim()
        if (normalized.length < MIN_QUERY_LENGTH) return emptyList()

        val contacts = contactRepository.getAll().first().associateBy { it.publicKey }
        return messageRepository.getAll().first()
            .asSequence()
            .filter { it.type != MessageType.FileTransfer }
            .filter { it.message.contains(normalized, ignoreCase = true) }
            .sortedByDescending { it.timestamp }
            .take(MAX_RESULTS)
            .map {
                val contact = contacts[it.publicKey]
                Result(
                    publicKey = it.publicKey,
                    contactName = contact?.name?.ifEmpty { it.publicKey.take(8) } ?: it.publicKey.take(8),
                    message = it.message,
                    timestamp = it.timestamp,
                )
            }
            .toList()
    }

    data class Result(
        val publicKey: String,
        val contactName: String,
        val message: String,
        val timestamp: Long,
    )

    companion object {
        const val ENABLED = true
        private const val MIN_QUERY_LENGTH = 2
        private const val MAX_RESULTS = 80
    }
}
