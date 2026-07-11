// SPDX-FileCopyrightText: 2026 skyTox contributors
//
// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.domain.feature

import android.content.Context
import androidx.core.content.edit
import javax.inject.Inject
import javax.inject.Singleton

private const val PREFS_NAME = "skytox_contact_name_cache"

@Singleton
class ContactNameCache @Inject constructor(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun remember(publicKey: String, name: String) {
        val trimmed = name.trim()
        if (publicKey.isBlank() || trimmed.isBlank()) return
        prefs.edit { putString(publicKey, trimmed) }
    }

    fun nameFor(publicKey: String): String = prefs.getString(publicKey, null).orEmpty()
}
