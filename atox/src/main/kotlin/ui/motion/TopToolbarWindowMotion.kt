// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.atox.ui.motion

import android.provider.Settings
import android.view.View
import androidx.navigation.NavController
import androidx.navigation.NavOptions
import ltd.evilcorp.atox.R

fun openTopToolbarWindow(
    navController: NavController,
    actionId: Int,
    sourceItemId: Int,
    anchor: View,
    enabled: Boolean,
) {
    val animatorScale = runCatching {
        Settings.Global.getFloat(anchor.context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }.getOrDefault(1f)
    val enterAnimation = when (sourceItemId) {
        R.id.open_ai_assistant -> R.anim.window_expand_from_ai
        R.id.open_games -> R.anim.window_expand_from_games
        else -> null
    }
    val animate = enabled && animatorScale != 0f && anchor.isAttachedToWindow && enterAnimation != null
    val options = NavOptions.Builder().apply {
        if (animate) {
            setEnterAnim(enterAnimation)
            setExitAnim(R.anim.window_hold)
            setPopEnterAnim(R.anim.window_expand_from_chats)
            setPopExitAnim(R.anim.window_hold)
        } else {
            setEnterAnim(0)
            setExitAnim(0)
            setPopEnterAnim(0)
            setPopExitAnim(0)
        }
    }.build()
    navController.navigate(actionId, null, options)
}
