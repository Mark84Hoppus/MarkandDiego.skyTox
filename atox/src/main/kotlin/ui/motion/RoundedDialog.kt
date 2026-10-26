package ltd.evilcorp.atox.ui.motion

import android.graphics.drawable.GradientDrawable
import androidx.appcompat.app.AlertDialog
import com.google.android.material.color.MaterialColors
import ltd.evilcorp.atox.R

fun AlertDialog.showRounded(animationStyle: Int): AlertDialog {
    if (animationStyle != 0) window?.setWindowAnimations(animationStyle)
    show()
    val panelId = context.resources.getIdentifier("parentPanel", "id", "android")
    window?.decorView?.findViewById<android.view.View>(panelId)?.background = GradientDrawable().apply {
        setColor(MaterialColors.getColor(context, com.google.android.material.R.attr.colorSurface, 0xff444444.toInt()))
        cornerRadius = 28f * context.resources.displayMetrics.density
    }
    return this
}
