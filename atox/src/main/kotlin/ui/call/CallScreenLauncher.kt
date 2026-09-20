// SPDX-FileCopyrightText: 2026 skyTox contributors
//
// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.atox.ui.call

import android.content.Context
import android.content.Intent
import ltd.evilcorp.atox.MainActivity
import ltd.evilcorp.core.vo.PublicKey

const val EXTRA_OPEN_CALL_PUBLIC_KEY = "openCallPublicKey"

fun openCallScreen(context: Context, publicKey: PublicKey) {
    context.startActivity(
        Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_OPEN_CALL_PUBLIC_KEY, publicKey.string())
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            ),
    )
}
