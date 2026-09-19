// SPDX-FileCopyrightText: 2026 skyTox contributors
//
// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.domain.feature

import android.os.Environment
import java.io.File

private const val SKYTOX_ROOT = "skyTox files"
private const val SKYTOX_IMAGE = "skyTox image"
private const val SKYTOX_VIDEO = "skyTox video"
private const val SKYTOX_RECORDER = "skyTox recorder"
private const val SKYTOX_DOCUMENTS = "skyTox documents"
private const val SKYTOX_INCOMING_IMAGES = "incoming images"
private const val SKYTOX_SENT_IMAGES = "sent images"
private const val SKYTOX_INCOMING_VIDEOS = "incoming videos"
private const val SKYTOX_SENT_VIDEOS = "sent videos"
private const val SKYTOX_INCOMING_VOICE = "incoming voice messages"
private const val SKYTOX_SENT_VOICE = "sent voice messages"
private const val SKYTOX_INCOMING_FILES = "incoming files"
private const val SKYTOX_SENT_FILES = "sent files"
private const val SKYTOX_THUMBS = "skyTox thumbs"
private const val SKYTOX_ALL_CHAT = "skyTox all chat"
private const val SKYTOX_USER_CHAT = "skyTox user chat"
private const val SKYTOX_PROFILE = "skyTox profile"
private const val SKYTOX_APP = "skyTox app"
private const val SKYTOX_LOGS = "skyTox logs"
private const val SKYTOX_GAMES = "skyTox games"

object SkyToxPublicFolders {
    @Suppress("DEPRECATION")
    val root: File get() = File(Environment.getExternalStorageDirectory(), SKYTOX_ROOT)
    val imageDir: File get() = File(root, SKYTOX_IMAGE)
    val videoDir: File get() = File(root, SKYTOX_VIDEO)
    val recorderDir: File get() = File(root, SKYTOX_RECORDER)
    val documentDir: File get() = File(root, SKYTOX_DOCUMENTS)
    val incomingImageDir: File get() = File(imageDir, SKYTOX_INCOMING_IMAGES)
    val sentImageDir: File get() = File(imageDir, SKYTOX_SENT_IMAGES)
    val incomingVideoDir: File get() = File(videoDir, SKYTOX_INCOMING_VIDEOS)
    val sentVideoDir: File get() = File(videoDir, SKYTOX_SENT_VIDEOS)
    val incomingRecorderDir: File get() = File(recorderDir, SKYTOX_INCOMING_VOICE)
    val sentRecorderDir: File get() = File(recorderDir, SKYTOX_SENT_VOICE)
    val incomingDocumentDir: File get() = File(documentDir, SKYTOX_INCOMING_FILES)
    val sentDocumentDir: File get() = File(documentDir, SKYTOX_SENT_FILES)
    val thumbDir: File get() = File(root, SKYTOX_THUMBS)
    val allChatDir: File get() = File(root, SKYTOX_ALL_CHAT)
    val userChatDir: File get() = File(root, SKYTOX_USER_CHAT)
    val profileDir: File get() = File(root, SKYTOX_PROFILE)
    val appDir: File get() = File(root, SKYTOX_APP)
    val logsDir: File get() = File(root, SKYTOX_LOGS)
    val gamesDir: File get() = File(root, SKYTOX_GAMES)

    fun ensureDirectories() {
        listOf(
            root,
            imageDir,
            videoDir,
            recorderDir,
            documentDir,
            incomingImageDir,
            sentImageDir,
            incomingVideoDir,
            sentVideoDir,
            incomingRecorderDir,
            sentRecorderDir,
            incomingDocumentDir,
            sentDocumentDir,
            thumbDir,
            allChatDir,
            userChatDir,
            profileDir,
            appDir,
            logsDir,
            gamesDir,
        )
            .forEach { it.mkdirs() }
    }
}
