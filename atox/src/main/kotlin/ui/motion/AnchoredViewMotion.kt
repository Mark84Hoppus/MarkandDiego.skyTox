package ltd.evilcorp.atox.ui.motion

import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator

fun View.expandFrom(source: View, enabled: Boolean) {
    visibility = View.VISIBLE
    if (!enabled || !source.isAttachedToWindow) return
    post {
        val sourceLocation = IntArray(2).also(source::getLocationOnScreen)
        val targetLocation = IntArray(2).also(::getLocationOnScreen)
        pivotX = sourceLocation[0] + source.width / 2f - targetLocation[0]
        pivotY = sourceLocation[1] + source.height / 2f - targetLocation[1]
        scaleX = (source.width.toFloat() / width.coerceAtLeast(1)).coerceAtLeast(0.04f)
        scaleY = (source.height.toFloat() / height.coerceAtLeast(1)).coerceAtLeast(0.03f)
        animate().scaleX(1f).scaleY(1f).setDuration(300L)
            .setInterpolator(AccelerateDecelerateInterpolator()).start()
    }
}

fun View.slideOutRight(enabled: Boolean, finished: () -> Unit) {
    if (!enabled) return finished()
    animate().translationX(width.toFloat()).setDuration(250L)
        .setInterpolator(AccelerateDecelerateInterpolator()).withEndAction {
            translationX = 0f
            finished()
        }.start()
}
