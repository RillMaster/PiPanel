package com.rillmaster.pipanel.update

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan

object MarkdownRenderer {

    /**
     * Rend un sous-ensemble de Markdown (titres #, gras **…**, puces - ou *)
     * dans un CharSequence stylé pour l'AlertDialog natif.
     */
    fun renderMarkdown(markdown: String): CharSequence {
        val spannable = SpannableStringBuilder()
        val lines = markdown.lines()
        lines.forEachIndexed { index, rawLine ->
            var line = rawLine
            var headingLevel = 0
            while (line.startsWith("#")) { headingLevel++; line = line.removePrefix("#") }
            line = line.trimStart()
            if (line.startsWith("- ") || line.startsWith("* ")) line = "• " + line.drop(2)

            val lineStart = spannable.length
            val boldRegex = Regex("""\*\*(.+?)\*\*""")
            var last = 0
            for (m in boldRegex.findAll(line)) {
                spannable.append(line.substring(last, m.range.first))
                val boldStart = spannable.length
                spannable.append(m.groupValues[1])
                spannable.setSpan(StyleSpan(Typeface.BOLD), boldStart, spannable.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                last = m.range.last + 1
            }
            spannable.append(line.substring(last))

            if (headingLevel > 0 && spannable.length > lineStart) {
                spannable.setSpan(StyleSpan(Typeface.BOLD), lineStart, spannable.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                val size = when (headingLevel) { 1 -> 1.3f; 2 -> 1.2f; else -> 1.1f }
                spannable.setSpan(RelativeSizeSpan(size), lineStart, spannable.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (index < lines.size - 1) spannable.append("\n")
        }
        return spannable
    }
}
