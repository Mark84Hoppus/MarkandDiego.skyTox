// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.atox.tox

object SkyToxIncomingTextSanitizer {
    private const val REPLACEMENT_CHAR = '\uFFFD'

    fun shouldIgnore(message: String): Boolean {
        if (message.isBlank()) return false
        val replacements = message.count { it == REPLACEMENT_CHAR }
        if (replacements == 0) return false
        val controls = message.count { it.code < 32 && it !in listOf('\n', '\r', '\t') }
        val noisy = replacements + controls
        return replacements >= 2 && noisy.toFloat() / message.length.toFloat() > 0.08f
    }
}
