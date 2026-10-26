package ltd.evilcorp.atox.ui.motion

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.R as AppCompatR
import com.google.android.material.color.MaterialColors
import ltd.evilcorp.atox.R

fun AlertDialog.showRounded(animationStyle: Int): AlertDialog {
    if (animationStyle != 0) window?.setWindowAnimations(animationStyle)
    show()
    window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    window?.decorView?.findViewById<android.view.View>(AppCompatR.id.parentPanel)?.apply {
        background = GradientDrawable().apply {
        setColor(MaterialColors.getColor(context, com.google.android.material.R.attr.colorSurface, 0xff444444.toInt()))
        cornerRadius = 28f * context.resources.displayMetrics.density
        }
        clipToOutline = true
    }
    return this
}
