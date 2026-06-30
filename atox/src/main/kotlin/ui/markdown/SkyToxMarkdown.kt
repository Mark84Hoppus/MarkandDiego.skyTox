// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.atox.ui.markdown

import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.QuoteSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.URLSpan
import android.text.util.Linkify
import android.widget.TextView

object SkyToxMarkdown {
    const val ENABLED = true

    private val inlineRules = listOf(
        InlineRule(Regex("""\[(.+?)]\((https?://[^\s)]+)\)"""), InlineStyle.Link),
        InlineRule(Regex("""`([^`\n]+)`"""), InlineStyle.Code),
        InlineRule(Regex("""\*\*\*([^*\n]+)\*\*\*"""), InlineStyle.BoldItalic),
        InlineRule(Regex("""\*\*([^*\n]+)\*\*"""), InlineStyle.Bold),
        InlineRule(Regex("""~~([^~\n]+)~~"""), InlineStyle.Strike),
        InlineRule(Regex("""(?<!\*)\*([^*\n]+)\*(?!\*)"""), InlineStyle.Italic),
    )

    fun render(textView: TextView, rawMessage: String) {
        if (!ENABLED) {
            textView.text = rawMessage
            return
        }

        textView.text = format(rawMessage)
        Linkify.addLinks(textView, Linkify.WEB_URLS or Linkify.EMAIL_ADDRESSES)
        textView.movementMethod = LinkMovementMethod.getInstance()
    }

    fun format(rawMessage: String): SpannableStringBuilder {
        val out = SpannableStringBuilder()
        val lines = rawMessage.replace("\r\n", "\n").replace('\r', '\n').split('\n')

        lines.forEachIndexed { index, line ->
            val lineStart = out.length
            val prepared = prepareLine(line)
            appendInline(prepared.text, out)

            if (prepared.quote && out.length > lineStart) {
                out.setSpan(QuoteSpan(), lineStart, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }

            if (index != lines.lastIndex) out.append('\n')
        }

        return out
    }

    private fun prepareLine(line: String): PreparedLine {
        val leadingWhitespace = line.takeWhile { it == ' ' || it == '\t' }
        val trimmed = line.drop(leadingWhitespace.length)

        if (trimmed.startsWith(">")) {
            return PreparedLine(trimmed.removePrefix(">").trimStart(), quote = true)
        }

        if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
            return PreparedLine("$leadingWhitespace\u2022 ${trimmed.drop(2)}", quote = false)
        }

        return PreparedLine(line, quote = false)
    }

    private fun appendInline(input: String, out: SpannableStringBuilder) {
        var remaining = input
        while (remaining.isNotEmpty()) {
            val match = nextMatch(remaining)
            if (match == null) {
                out.append(remaining)
                return
            }

            out.append(remaining.substring(0, match.range.first))
            val spanStart = out.length
            val display = match.groupValues[1]
            out.append(display)
            val spanEnd = out.length
            applySpan(out, spanStart, spanEnd, match.rule, match.groupValues)
            remaining = remaining.substring(match.range.last + 1)
        }
    }

    private fun nextMatch(input: String): RuleMatch? =
        inlineRules.asSequence()
            .mapNotNull { rule ->
                rule.regex.find(input)?.let { match -> RuleMatch(rule, match) }
            }
            .minWithOrNull(compareBy<RuleMatch> { it.range.first }.thenBy { inlineRules.indexOf(it.rule) })

    private fun applySpan(
        out: SpannableStringBuilder,
        start: Int,
        end: Int,
        rule: InlineRule,
        groupValues: List<String>,
    ) {
        if (start >= end) return

        when (rule.style) {
            InlineStyle.Bold -> out.setSpan(StyleSpan(Typeface.BOLD), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            InlineStyle.Italic -> out.setSpan(StyleSpan(Typeface.ITALIC), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            InlineStyle.BoldItalic ->
                out.setSpan(StyleSpan(Typeface.BOLD_ITALIC), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            InlineStyle.Strike -> out.setSpan(StrikethroughSpan(), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            InlineStyle.Code -> out.setSpan(TypefaceSpan("monospace"), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            InlineStyle.Link -> out.setSpan(URLSpan(groupValues[2]), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private data class PreparedLine(val text: String, val quote: Boolean)
    private data class InlineRule(val regex: Regex, val style: InlineStyle)
    private data class RuleMatch(val rule: InlineRule, val match: MatchResult) {
        val range get() = match.range
        val groupValues get() = match.groupValues
    }

    private enum class InlineStyle {
        Bold,
        Italic,
        BoldItalic,
        Strike,
        Code,
        Link,
    }
}
