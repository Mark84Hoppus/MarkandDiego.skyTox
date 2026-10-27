// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.atox.ui.chat

internal data class AnimatedEmojiMatch(val start: Int, val end: Int, val asset: String)

internal class AnimatedEmojiIndex(val paths: Map<String, String>) {
    val orderedKeys: List<String> = paths.keys.sortedByDescending(String::length)
}

internal fun findAnimatedEmojiMatches(text: String, index: AnimatedEmojiIndex?): List<AnimatedEmojiMatch> {
    if (index == null || index.paths.isEmpty() || text.isEmpty()) return emptyList()
    val result = mutableListOf<AnimatedEmojiMatch>()
    var offset = 0
    while (offset < text.length) {
        val key = index.orderedKeys.firstOrNull { text.startsWith(it, offset) }
        if (key == null) {
            offset += Character.charCount(Character.codePointAt(text, offset))
        } else {
            result += AnimatedEmojiMatch(offset, offset + key.length, index.paths.getValue(key))
            offset += key.length
        }
    }
    return result
}

internal fun isSupportedEmojiOnly(text: String, matches: List<AnimatedEmojiMatch>): Boolean {
    val trimmed = text.trim()
    if (trimmed.isEmpty() || matches.isEmpty()) return false
    var offset = text.indexOf(trimmed)
    val end = offset + trimmed.length
    for (match in matches) {
        if (match.end <= offset || match.start >= end) continue
        if (match.start < offset || text.substring(offset, match.start).any { !it.isWhitespace() }) return false
        offset = match.end
    }
    return offset == end || text.substring(offset, end).all(Char::isWhitespace)
}
