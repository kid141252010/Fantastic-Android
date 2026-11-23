package io.github.kid141252010.fantastic.lyrics

import android.icu.text.BreakIterator
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ScaleXSpan

/** Applies AMLL's render-only CJK/Latin autospace rules without changing lyric source text. */
internal object LyricTextAutospace {
    internal const val GAP_CHAR = '\u3000'

    private enum class Kind { CJK, LATIN, NUMBER, WAVE, OTHER }

    internal class Result internal constructor(
        val text: String,
        private val sourceOffsets: IntArray?,
        private val gapOffsets: IntArray?,
    ) {
        fun displayOffset(sourceOffset: Int): Int =
            sourceOffsets?.getOrNull(sourceOffset) ?: sourceOffset
        fun displayEndOffset(sourceOffset: Int): Int =
            displayOffset(sourceOffset) - if (hasGapBefore(sourceOffset)) 1 else 0

        fun hasGapBefore(sourceOffset: Int): Boolean = sourceOffsets != null && sourceOffset > 0 &&
            sourceOffset < sourceOffsets.size && sourceOffsets[sourceOffset] > sourceOffsets[sourceOffset - 1] + 1

        fun layoutText(): CharSequence {
            val offsets = gapOffsets ?: return text
            return SpannableString(text).apply {
                for (offset in offsets) setSpan(ScaleXSpan(.125f), offset, offset + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }

    fun apply(source: String): Result {
        if (source.isEmpty()) return Result(source, null, null)

        val iterator = BreakIterator.getCharacterInstance().apply { setText(source) }
        var output: StringBuilder? = null
        var offsets: IntArray? = null
        var gaps: ArrayList<Int>? = null
        var previous = Kind.OTHER
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            val current = classify(source, start, end)
            if (needsGap(previous, current)) {
                if (output == null) {
                    output = StringBuilder(source.length + 8).append(source, 0, start)
                    offsets = IntArray(source.length + 1)
                    for (index in 0..start) offsets[index] = index
                }
                output.append(GAP_CHAR)
                val gapOffsets = gaps ?: ArrayList<Int>().also { gaps = it }
                gapOffsets += output.length - 1
            }
            output?.let { builder ->
                val displayStart = builder.length
                val sourceOffsets = checkNotNull(offsets)
                for (index in start..end) sourceOffsets[index] = displayStart + index - start
                builder.append(source, start, end)
            }
            previous = current
            start = end
            end = iterator.next()
        }
        return output?.let { Result(it.toString(), offsets, gaps?.toIntArray()) } ?: Result(source, null, null)
    }

    private fun classify(text: String, start: Int, end: Int): Kind {
        var index = start
        val base = text.codePointAt(start)
        while (index < end) {
            val codePoint = text.codePointAt(index)
            if (codePoint == 0x200D || codePoint == 0x20E3 ||
                codePoint in 0x1F1E6..0x1F1FF || codePoint in 0x1F3FB..0x1F3FF
            ) return Kind.OTHER
            index += Character.charCount(codePoint)
        }

        val script = Character.UnicodeScript.of(base)
        if (Character.isLetterOrDigit(base) &&
            (script == Character.UnicodeScript.HAN || script == Character.UnicodeScript.HIRAGANA ||
                script == Character.UnicodeScript.KATAKANA || base == 0x30FC || base == 0xFF70)
        ) return Kind.CJK
        if (Character.isLetter(base) && script == Character.UnicodeScript.LATIN &&
            base !in 0xFF21..0xFF3A && base !in 0xFF41..0xFF5A
        ) return Kind.LATIN
        if (base in '0'.code..'9'.code) return Kind.NUMBER
        if (base == '~'.code || base == 0x301C || base == 0xFF5E) return Kind.WAVE
        return Kind.OTHER
    }

    private fun needsGap(previous: Kind, next: Kind): Boolean =
        (previous == Kind.CJK && (next == Kind.LATIN || next == Kind.NUMBER)) ||
            (next == Kind.CJK && (previous == Kind.LATIN || previous == Kind.NUMBER)) ||
            (previous == Kind.NUMBER && next == Kind.WAVE) ||
            (previous == Kind.WAVE && next == Kind.NUMBER)
}
