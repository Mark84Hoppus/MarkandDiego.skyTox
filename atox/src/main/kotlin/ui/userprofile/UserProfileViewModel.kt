// SPDX-FileCopyrightText: 2020 aTox contributors
//
// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.atox.ui.userprofile

import android.net.Uri
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import javax.inject.Inject
import ltd.evilcorp.core.vo.User
import ltd.evilcorp.core.vo.UserStatus
import ltd.evilcorp.domain.feature.avatar.SkyToxAvatarManager
import ltd.evilcorp.domain.feature.UserManager
import ltd.evilcorp.domain.tox.Tox

class UserProfileViewModel @Inject constructor(
    private val userManager: UserManager,
    private val avatarManager: SkyToxAvatarManager,
    private val tox: Tox,
) :
    ViewModel() {
    val publicKey by lazy { tox.publicKey }
    val toxId by lazy { tox.toxId }
    val user: LiveData<User> = userManager.get(publicKey).asLiveData()
    val avatarUri = MutableLiveData(avatarManager.ownAvatarUri())

    fun setName(name: String) = userManager.setName(name)
    fun setStatusMessage(statusMessage: String) = userManager.setStatusMessage(statusMessage)
    fun setStatus(status: UserStatus) = userManager.setStatus(status)
    fun setAvatar(uri: Uri) = avatarManager.setOwnAvatar(uri) { avatarUri.postValue(it) }
    fun deleteAvatar() = avatarManager.deleteOwnAvatar { avatarUri.postValue(null) }
}
