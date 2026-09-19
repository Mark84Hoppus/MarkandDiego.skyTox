// SPDX-FileCopyrightText: 2020 aTox contributors
//
// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.atox.ui.userprofile

import android.net.Uri
import android.content.Context
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import androidx.preference.PreferenceManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ltd.evilcorp.core.db.Database
import ltd.evilcorp.core.vo.User
import ltd.evilcorp.core.vo.UserStatus
import ltd.evilcorp.domain.feature.ExportManager
import ltd.evilcorp.domain.feature.SkyToxPublicFolders
import ltd.evilcorp.domain.feature.avatar.SkyToxAvatarManager
import ltd.evilcorp.domain.feature.UserManager
import ltd.evilcorp.atox.tox.ToxStarter
import ltd.evilcorp.domain.tox.SaveManager
import ltd.evilcorp.domain.tox.Tox

class UserProfileViewModel @Inject constructor(
    private val context: Context,
    private val userManager: UserManager,
    private val avatarManager: SkyToxAvatarManager,
    private val exportManager: ExportManager,
    private val toxStarter: ToxStarter,
    private val saveManager: SaveManager,
    private val database: Database,
    private val tox: Tox,
) :
    ViewModel() {
    val publicKey by lazy { tox.publicKey }
    val toxId by lazy { tox.toxId }
    val user: LiveData<User> = userManager.get(publicKey).asLiveData()
    val avatarUri = MutableLiveData(avatarManager.ownAvatarUri())
    val logoutComplete = MutableLiveData<Boolean>()
    val logoutError = MutableLiveData<String?>()

    fun setName(name: String) = userManager.setName(name)
    fun setStatusMessage(statusMessage: String) = userManager.setStatusMessage(statusMessage)
    fun setStatus(status: UserStatus) = userManager.setStatus(status)
    fun setAvatar(uri: Uri) = avatarManager.setOwnAvatar(uri) { avatarUri.postValue(it) }
    fun deleteAvatar() = avatarManager.deleteOwnAvatar { avatarUri.postValue(null) }

    fun logoutAndReset() = viewModelScope.launch(Dispatchers.IO) {
        try {
            SkyToxPublicFolders.ensureDirectories()
            val stamp = timestamp()
            uniqueFile(SkyToxPublicFolders.profileDir, "skytox-profile-$stamp.tox")
                .writeBytes(tox.getSaveData())
            uniqueFile(SkyToxPublicFolders.allChatDir, "skytox-all-text-chats_$stamp.json")
                .writeText(exportManager.generateAllTextChatsJString(), Charsets.UTF_8)

            toxStarter.stopTox()
            while (tox.started) delay(100)

            database.clearAllTables()
            saveManager.deleteAll()
            PreferenceManager.getDefaultSharedPreferences(context).edit().clear().apply()
            context.getSharedPreferences("skytox_push", Context.MODE_PRIVATE).edit().clear().apply()

            withContext(Dispatchers.Main) {
                logoutComplete.value = true
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                logoutError.value = e.message ?: e.toString()
            }
        }
    }

    private fun timestamp(): String =
        SimpleDateFormat("""yyyy-MM-dd'T'HH-mm-ss""", Locale.getDefault()).format(Date())

    private fun uniqueFile(dir: File, name: String): File {
        dir.mkdirs()
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        var file = File(dir, name)
        var counter = 1
        while (file.exists()) {
            file = File(dir, if (ext.isBlank()) "$base-$counter" else "$base-$counter.$ext")
            counter++
        }
        return file
    }
}
