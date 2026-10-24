// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.atox.ui.motion

import android.provider.Settings
import android.view.View
import androidx.navigation.NavController
import androidx.navigation.NavOptions
import ltd.evilcorp.atox.R

interface TopLevelWindowMotion {
    fun open(navController: NavController, destinationId: Int, source: View)
}

object NoTopLevelWindowMotion : TopLevelWindowMotion {
    override fun open(navController: NavController, destinationId: Int, source: View) {
        navController.openTopLevel(destinationId, null, false)
    }
}

class ExpandOnlyTopLevelWindowMotion : TopLevelWindowMotion {
    override fun open(navController: NavController, destinationId: Int, source: View) {
        val enterAnimation = when (source.id) {
            R.id.bottomNavChats -> R.anim.window_expand_from_chats
            R.id.bottomNavAdd -> R.anim.window_expand_from_add
            R.id.bottomNavSettings -> R.anim.window_expand_from_settings
            R.id.bottomNavExport -> R.anim.window_expand_from_export
            R.id.bottomNavProfile -> R.anim.window_expand_from_profile
            else -> null
        }
        navController.openTopLevel(destinationId, enterAnimation, true)
    }
}

fun topLevelWindowMotion(source: View, enabled: Boolean): TopLevelWindowMotion {
    if (!enabled || !source.isAttachedToWindow || source.width == 0 || source.height == 0) {
        return NoTopLevelWindowMotion
    }
    val scale = runCatching {
        Settings.Global.getFloat(source.context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }.getOrDefault(1f)
    return if (scale == 0f) NoTopLevelWindowMotion else ExpandOnlyTopLevelWindowMotion()
}

private fun NavController.openTopLevel(
    destinationId: Int,
    enterAnimation: Int?,
    animateReturnToChats: Boolean,
) {
    if (currentDestination?.id == destinationId) return
    val returningToChats = destinationId == R.id.contactListFragment
    val options = NavOptions.Builder()
        .setLaunchSingleTop(true)
        .setPopUpTo(R.id.contactListFragment, returningToChats)
        .apply {
            if (enterAnimation != null) {
                setEnterAnim(enterAnimation)
                setExitAnim(R.anim.window_hold)
            }
            if (animateReturnToChats && destinationId != R.id.contactListFragment) {
                setPopEnterAnim(R.anim.window_expand_from_chats)
                setPopExitAnim(R.anim.window_hold)
            }
        }
        .build()
    navigate(destinationId, null, options)
}
