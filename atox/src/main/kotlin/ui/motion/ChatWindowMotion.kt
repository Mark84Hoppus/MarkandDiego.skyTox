// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.atox.ui.motion

import android.graphics.Color
import android.provider.Settings
import android.view.View
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.FragmentNavigator
import com.google.android.material.transition.MaterialContainerTransform
import ltd.evilcorp.atox.R

const val CHAT_WINDOW_MOTION = "chatWindowMotion"

interface ChatWindowMotion {
    fun transitionName(publicKey: String): String?
    fun open(source: View?, publicKey: String, plain: () -> Unit, animated: (FragmentNavigator.Extras) -> Unit)
    fun prepareDestination(fragment: Fragment, publicKey: String, containerColor: Int)
    fun bindDestination(view: View, publicKey: String)
}

object NoChatWindowMotion : ChatWindowMotion {
    override fun transitionName(publicKey: String): String? = null
    override fun open(
        source: View?,
        publicKey: String,
        plain: () -> Unit,
        animated: (FragmentNavigator.Extras) -> Unit,
    ) = plain()
    override fun prepareDestination(fragment: Fragment, publicKey: String, containerColor: Int) = Unit
    override fun bindDestination(view: View, publicKey: String) = Unit
}

class MaterialChatWindowMotion : ChatWindowMotion {
    override fun transitionName(publicKey: String) = "skytox-chat-$publicKey"

    override fun open(
        source: View?,
        publicKey: String,
        plain: () -> Unit,
        animated: (FragmentNavigator.Extras) -> Unit,
    ) {
        val name = transitionName(publicKey)
        if (source == null || !source.isAttachedToWindow || source.width == 0 || source.height == 0) {
            plain()
            return
        }
        ViewCompat.setTransitionName(source, name)
        animated(FragmentNavigator.Extras.Builder().addSharedElement(source, name).build())
    }

    override fun prepareDestination(fragment: Fragment, publicKey: String, containerColor: Int) {
        fragment.sharedElementEnterTransition = MaterialContainerTransform().apply {
            drawingViewId = R.id.nav_host_fragment
            duration = 300L
            scrimColor = Color.TRANSPARENT
            setAllContainerColors(containerColor)
        }
    }

    override fun bindDestination(view: View, publicKey: String) {
        ViewCompat.setTransitionName(view, transitionName(publicKey))
    }
}

fun chatWindowMotion(fragment: Fragment, enabled: Boolean): ChatWindowMotion {
    if (!enabled) return NoChatWindowMotion
    val scale = runCatching {
        Settings.Global.getFloat(fragment.requireContext().contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }.getOrDefault(1f)
    return if (scale == 0f) NoChatWindowMotion else MaterialChatWindowMotion()
}
