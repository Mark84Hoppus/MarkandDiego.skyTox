// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.atox.ui.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AnimatedEmojiMatcherTest {
    private val index = AnimatedEmojiIndex(
        mapOf(
            "❤" to "heart.json",
            "❤️" to "heart_vs16.json",
            "👨‍👩‍👧" to "family.json",
            "😂" to "laugh.json",
        ),
    )

    @Test fun `uses longest variation-selector match`() {
        assertEquals("heart_vs16.json", findAnimatedEmojiMatches("❤️", index).single().asset)
    }

    @Test fun `matches zwj sequence as one emoji`() {
        assertEquals(1, findAnimatedEmojiMatches("👨‍👩‍👧", index).size)
    }

    @Test fun `advances safely over surrogate pairs and finds later emoji`() {
        val match = findAnimatedEmojiMatches("🙂x😂", index).single()
        assertEquals("😂", "🙂x😂".substring(match.start, match.end))
    }

    @Test fun `returns all supported emoji in mixed text`() {
        assertEquals(2, findAnimatedEmojiMatches("hello 😂 ❤️", index).size)
    }

    @Test fun `detects emoji-only messages including whitespace`() {
        val text = "  😂 ❤️  "
        assertTrue(isSupportedEmojiOnly(text, findAnimatedEmojiMatches(text, index)))
        assertFalse(isSupportedEmojiOnly("hello 😂", findAnimatedEmojiMatches("hello 😂", index)))
    }

    @Test fun `unsupported text has no matches`() {
        assertTrue(findAnimatedEmojiMatches("plain text", index).isEmpty())
    }
}
