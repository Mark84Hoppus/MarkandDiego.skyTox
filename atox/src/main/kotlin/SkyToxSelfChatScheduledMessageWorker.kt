// SPDX-FileCopyrightText: 2026 skyTox contributors
//
// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.atox

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import javax.inject.Inject
import ltd.evilcorp.atox.ui.NotificationHelper
import ltd.evilcorp.core.repository.MessageRepository
import ltd.evilcorp.core.vo.Message
import ltd.evilcorp.core.vo.MessageType
import ltd.evilcorp.core.vo.Sender
import ltd.evilcorp.domain.feature.SKYTOX_SELF_CHAT_PUBLIC_KEY

const val SELF_CHAT_WORK_PREFIX = "skytox_self_chat_"
const val SELF_CHAT_INPUT_MESSAGE = "message"
const val SELF_CHAT_INPUT_PENDING_ID = "pending_id"

class SkyToxSelfChatScheduledMessageWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    @Inject
    lateinit var messageRepository: MessageRepository

    @Inject
    lateinit var notificationHelper: NotificationHelper

    override suspend fun doWork(): Result {
        (applicationContext as App).component.inject(this)
        val text = inputData.getString(SELF_CHAT_INPUT_MESSAGE).orEmpty()
        if (text.isBlank()) return Result.success()

        val pendingId = inputData.getLong(SELF_CHAT_INPUT_PENDING_ID, 0L)
        if (pendingId > 0L) {
            messageRepository.deleteMessage(pendingId)
        }
        messageRepository.addLocal(
            Message(
                publicKey = SKYTOX_SELF_CHAT_PUBLIC_KEY,
                message = text,
                sender = Sender.Sent,
                type = MessageType.Normal,
                correlationId = 0,
                timestamp = System.currentTimeMillis(),
            ),
        )
        notificationHelper.showSelfChatNotification(text)
        return Result.success()
    }
}
