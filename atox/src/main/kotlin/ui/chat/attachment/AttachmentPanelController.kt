// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.atox.ui.chat.attachment

import android.view.View
import androidx.core.view.isVisible
import androidx.interpolator.view.animation.FastOutSlowInInterpolator

class AttachmentPanelController(
    private val panel: View,
    private val dismissLayer: View,
    fileAction: View,
    cameraAction: View,
    private val onFile: () -> Unit,
    private val onCamera: () -> Unit,
) {
    val isVisible: Boolean get() = panel.isVisible

    init {
        dismissLayer.setOnClickListener { hide() }
        panel.setOnClickListener { /* Consume taps inside the panel. */ }
        fileAction.setOnClickListener {
            hide()
            onFile()
        }
        cameraAction.setOnClickListener {
            hide()
            onCamera()
        }
    }

    fun toggle() {
        if (isVisible) hide() else show()
    }

    fun show() {
        panel.animate().cancel()
        dismissLayer.isVisible = true
        panel.apply {
            isVisible = true
            alpha = 0f
            scaleX = 0.88f
            scaleY = 0.88f
            translationY = 12f * resources.displayMetrics.density
            animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .translationY(0f)
                .setDuration(210L)
                .setInterpolator(FastOutSlowInInterpolator())
                .start()
        }
    }

    fun hide() {
        if (!isVisible) {
            dismissLayer.isVisible = false
            return
        }
        panel.animate().cancel()
        panel.animate()
            .alpha(0f)
            .scaleX(0.94f)
            .scaleY(0.94f)
            .translationY(8f * panel.resources.displayMetrics.density)
            .setDuration(140L)
            .setInterpolator(FastOutSlowInInterpolator())
            .withEndAction {
                panel.isVisible = false
                dismissLayer.isVisible = false
                panel.alpha = 1f
                panel.scaleX = 1f
                panel.scaleY = 1f
                panel.translationY = 0f
            }
            .start()
    }

    fun destroy() {
        panel.animate().cancel()
        panel.isVisible = false
        dismissLayer.isVisible = false
    }
}
