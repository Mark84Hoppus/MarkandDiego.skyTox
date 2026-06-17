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
private const val SKYTOX_THUMBS = "skyTox thumbs"
private const val SKYTOX_ALL_CHAT = "skyTox all chat"
private const val SKYTOX_USER_CHAT = "skyTox user chat"

object SkyToxPublicFolders {
    @Suppress("DEPRECATION")
    val root: File get() = File(Environment.getExternalStorageDirectory(), SKYTOX_ROOT)
    val imageDir: File get() = File(root, SKYTOX_IMAGE)
    val videoDir: File get() = File(root, SKYTOX_VIDEO)
    val recorderDir: File get() = File(root, SKYTOX_RECORDER)
    val documentDir: File get() = File(root, SKYTOX_DOCUMENTS)
    val thumbDir: File get() = File(root, SKYTOX_THUMBS)
    val allChatDir: File get() = File(root, SKYTOX_ALL_CHAT)
    val userChatDir: File get() = File(root, SKYTOX_USER_CHAT)

    fun ensureDirectories() {
        listOf(root, imageDir, videoDir, recorderDir, documentDir, thumbDir, allChatDir, userChatDir)
            .forEach { it.mkdirs() }
    }
}
