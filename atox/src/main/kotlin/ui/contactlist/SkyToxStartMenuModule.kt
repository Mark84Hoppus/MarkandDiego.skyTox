// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.atox.ui.contactlist

import android.net.Uri
import androidx.core.view.doOnLayout
import ltd.evilcorp.atox.databinding.FragmentContactListBinding
import ltd.evilcorp.atox.ui.AvatarFactory
import ltd.evilcorp.atox.ui.Px
import ltd.evilcorp.core.vo.User

internal class SkyToxStartMenuModule(
    private val binding: FragmentContactListBinding,
    private val openChats: () -> Unit,
    private val openAddContact: () -> Unit,
    private val openSettings: () -> Unit,
    private val openProfile: () -> Unit,
    private val ownAvatarUri: () -> Uri?,
) {
    private var user: User? = null

    fun attach() = binding.run {
        startMenuChats.setOnClickListener { openChats() }
        startMenuAdd.setOnClickListener { openAddContact() }
        startMenuSettings.setOnClickListener { openSettings() }
        startMenuProfile.setOnClickListener { openProfile() }
    }

    fun renderUser(user: User) {
        this.user = user
        renderAvatar()
    }

    fun renderAvatar(): Unit = binding.startMenuProfileAvatar.run {
        val uri = ownAvatarUri()
        if (uri != null) {
            setImageURI(uri)
            return@run
        }

        val currentUser = user ?: return@run
        val size = width.takeIf { it > 0 } ?: height.takeIf { it > 0 }
        if (size == null) {
            doOnLayout { renderAvatar() }
            return@run
        }

        setImageBitmap(AvatarFactory.create(resources, currentUser.name, currentUser.publicKey, Px(size)))
    }
}
