// SPDX-FileCopyrightText: 2026 skyTox contributors
//
// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.domain.feature

const val SKYTOX_SELF_CHAT_PUBLIC_KEY = "0000000000000000000000000000000000000000000000000000000000000001"

fun isSkyToxSelfChat(publicKey: String) = publicKey.equals(SKYTOX_SELF_CHAT_PUBLIC_KEY, ignoreCase = true)
