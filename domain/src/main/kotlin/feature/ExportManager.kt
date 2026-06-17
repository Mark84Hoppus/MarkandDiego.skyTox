// SPDX-FileCopyrightText: 2022 Akito <the@akito.ooo>
// SPDX-FileCopyrightText: 2023-2024 Robin Lindén <dev@robinlinden.eu>
//
// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.domain.feature

import java.lang.IllegalArgumentException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import ltd.evilcorp.core.repository.ContactRepository
import ltd.evilcorp.core.repository.MessageRepository
import ltd.evilcorp.core.vo.Message
import ltd.evilcorp.core.vo.MessageType
import ltd.evilcorp.core.vo.Sender
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

private const val FORMAT = "skytox-text-chat-history"
private const val VERSION = 1
private const val SCOPE_ALL = "all-chats"
private const val SCOPE_SINGLE = "single-chat"

enum class TextChatImportResult {
    Ok,
    InvalidJson,
    WrongScope,
    WrongContact,
    MissingContact,
}

class ExportManager @Inject constructor(
    private val messageRepository: MessageRepository,
    private val contactRepository: ContactRepository,
) {
    fun generateExportMessagesJString(publicKey: String): String {
        SkyToxPublicFolders.ensureDirectories()
        val messages = runBlocking { messageRepository.get(publicKey).first() }
        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())

        val root = JSONObject()
        root.put("format", FORMAT)
        root.put("version", VERSION)
        root.put("scope", SCOPE_SINGLE)
        root.put("exported_at", dateFormat.format(Date()))
        root.put("contact_public_key", publicKey)
        root.put("contains", "text-only")

        val entries = JSONArray()
        for (message in messages.textOnly()) entries.put(message.toJson(dateFormat))
        root.put("entries", entries)
        return root.toString(2)
    }

    fun generateAllTextChatsJString(): String {
        SkyToxPublicFolders.ensureDirectories()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
        val contacts = runBlocking { contactRepository.getAll().first() }

        val root = JSONObject()
        root.put("format", FORMAT)
        root.put("version", VERSION)
        root.put("scope", SCOPE_ALL)
        root.put("exported_at", dateFormat.format(Date()))
        root.put("contains", "text-only")

        val chats = JSONArray()
        for (contact in contacts) {
            val messages = runBlocking { messageRepository.get(contact.publicKey).first() }.textOnly()
            if (messages.isEmpty()) continue
            val chat = JSONObject()
            chat.put("contact_public_key", contact.publicKey)
            chat.put("contact_name", contact.name)
            val entries = JSONArray()
            for (message in messages) entries.put(message.toJson(dateFormat))
            chat.put("entries", entries)
            chats.put(chat)
        }
        root.put("chats", chats)
        return root.toString(2)
    }

    fun importSingleTextChat(publicKey: String, jsonString: String): TextChatImportResult {
        return try {
            SkyToxPublicFolders.ensureDirectories()
            val root = parseRoot(jsonString, SCOPE_SINGLE) ?: return TextChatImportResult.WrongScope
            if (root.getString("contact_public_key") != publicKey) return TextChatImportResult.WrongContact
            if (!contactRepository.exists(publicKey)) return TextChatImportResult.MissingContact

            val messages = parseEntries(publicKey, root.getJSONArray("entries"))
            messageRepository.delete(publicKey)
            messages.forEach(messageRepository::add)
            contactRepository.setLastMessage(publicKey, messages.maxOfOrNull { it.timestamp } ?: 0)
            TextChatImportResult.Ok
        } catch (_: Exception) {
            TextChatImportResult.InvalidJson
        }
    }

    fun importAllTextChats(jsonString: String): TextChatImportResult {
        return try {
            SkyToxPublicFolders.ensureDirectories()
            val root = parseRoot(jsonString, SCOPE_ALL) ?: return TextChatImportResult.WrongScope
            val chats = root.getJSONArray("chats")
            val pending = mutableMapOf<String, List<Message>>()

            for (i in 0 until chats.length()) {
                val chat = chats.getJSONObject(i)
                val publicKey = chat.getString("contact_public_key")
                validatePublicKey(publicKey)
                if (!contactRepository.exists(publicKey)) return TextChatImportResult.MissingContact
                pending[publicKey] = parseEntries(publicKey, chat.getJSONArray("entries"))
            }

            val contacts = runBlocking { contactRepository.getAll().first() }
            messageRepository.deleteAll()
            contacts.forEach { contactRepository.setLastMessage(it.publicKey, 0) }
            pending.forEach { (publicKey, messages) ->
                messages.forEach(messageRepository::add)
                contactRepository.setLastMessage(publicKey, messages.maxOfOrNull { it.timestamp } ?: 0)
            }
            TextChatImportResult.Ok
        } catch (_: Exception) {
            TextChatImportResult.InvalidJson
        }
    }

    private fun parseRoot(jsonString: String, expectedScope: String): JSONObject? {
        val root = try {
            JSONObject(jsonString)
        } catch (_: JSONException) {
            throw IllegalArgumentException("Invalid JSON")
        }
        if (root.getString("format") != FORMAT) throw IllegalArgumentException("Unexpected format")
        if (root.getInt("version") != VERSION) throw IllegalArgumentException("Unsupported version")
        if (root.getString("scope") != expectedScope) return null
        if (root.optString("contains") != "text-only") throw IllegalArgumentException("Unexpected content")
        return root
    }

    private fun parseEntries(publicKey: String, entries: JSONArray): List<Message> {
        validatePublicKey(publicKey)
        val messages = mutableListOf<Message>()
        for (i in 0 until entries.length()) {
            val entry = entries.getJSONObject(i)
            val type = MessageType.valueOf(entry.getString("type"))
            if (type != MessageType.Normal && type != MessageType.Action) {
                throw IllegalArgumentException("Only text messages can be imported")
            }
            messages.add(
                Message(
                    publicKey = publicKey,
                    message = entry.getString("message"),
                    sender = Sender.valueOf(entry.getString("sender")),
                    type = type,
                    correlationId = 0,
                    timestamp = entry.getLong("timestamp"),
                ),
            )
        }
        return messages
    }

    private fun List<Message>.textOnly() = filter {
        it.timestamp > 0 && (it.type == MessageType.Normal || it.type == MessageType.Action)
    }

    private fun Message.toJson(dateFormat: SimpleDateFormat) = JSONObject().apply {
        put("message", message)
        put("sender", sender.toString())
        put("type", type.toString())
        put("timestamp", timestamp)
        put("timestamp_iso", dateFormat.format(Date(timestamp)))
    }

    private fun validatePublicKey(publicKey: String) {
        if (!publicKey.matches(Regex("[0-9A-Fa-f]{64}"))) {
            throw IllegalArgumentException("Invalid public key")
        }
    }
}
