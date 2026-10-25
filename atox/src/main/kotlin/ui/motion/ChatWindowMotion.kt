// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.atox.ui.motion

import android.provider.Settings
import android.view.View
import androidx.fragment.app.Fragment
import androidx.navigation.NavOptions
import ltd.evilcorp.atox.R

const val CHAT_WINDOW_MOTION = "chatWindowMotion"

interface ChatWindowMotion {
    fun transitionName(publicKey: String): String?
    fun open(source: View?, plain: () -> Unit, animated: (NavOptions) -> Unit)
    fun prepareDestination(fragment: Fragment, publicKey: String, containerColor: Int)
    fun bindDestination(view: View, publicKey: String)
}

object NoChatWindowMotion : ChatWindowMotion {
    override fun transitionName(publicKey: String): String? = null
    override fun open(source: View?, plain: () -> Unit, animated: (NavOptions) -> Unit) = plain()
    override fun prepareDestination(fragment: Fragment, publicKey: String, containerColor: Int) = Unit
    override fun bindDestination(view: View, publicKey: String) = Unit
}

class NavigationChatWindowMotion : ChatWindowMotion {
    override fun transitionName(publicKey: String): String? = null

    override fun open(source: View?, plain: () -> Unit, animated: (NavOptions) -> Unit) {
        if (source == null || !source.isAttachedToWindow || source.width == 0 || source.height == 0) {
            plain()
            return
        }
        val location = IntArray(2)
        source.getLocationInWindow(location)
        val rootHeight = source.rootView.height.coerceAtLeast(1)
        val centerFraction = (location[1] + source.height / 2f) / rootHeight
        val animation = when {
            centerFraction < 0.23f -> R.anim.window_expand_from_chat_15
            centerFraction < 0.38f -> R.anim.window_expand_from_chat_30
            centerFraction < 0.53f -> R.anim.window_expand_from_chat_45
            centerFraction < 0.68f -> R.anim.window_expand_from_chat_60
            centerFraction < 0.83f -> R.anim.window_expand_from_chat_75
            else -> R.anim.window_expand_from_chat_90
        }
        animated(
            NavOptions.Builder()
                .setEnterAnim(animation)
                .setExitAnim(R.anim.window_hold)
                .setPopEnterAnim(animation)
                .setPopExitAnim(R.anim.window_hold)
                .build(),
        )
    }

    override fun prepareDestination(fragment: Fragment, publicKey: String, containerColor: Int) = Unit
    override fun bindDestination(view: View, publicKey: String) = Unit
}

fun chatWindowMotion(fragment: Fragment, enabled: Boolean): ChatWindowMotion {
    if (!enabled) return NoChatWindowMotion
    val scale = runCatching {
        Settings.Global.getFloat(fragment.requireContext().contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }.getOrDefault(1f)
    return if (scale == 0f) NoChatWindowMotion else NavigationChatWindowMotion()
}
