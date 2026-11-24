package io.github.kid141252010.fantastic.lyrics

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonObject
import kotlin.math.abs

internal val lrcTimestamp = Regex("\\[([0-9]+):([0-9]{2})(?:[.:]([0-9]{1,3}))?]")
private val lrcOffset = Regex("\\[offset:([+-]?[0-9]+)]", RegexOption.IGNORE_CASE)
private val lrcTag = Regex("\\[[^]]*]")
private val yrcLine = Regex("^\\[([0-9]+),([0-9]+)](.*)$")
private const val YRC_MARKER = "\\(([0-9]+),([0-9]+)(?:,[0-9]+)?\\)"
private val yrcWord = Regex("$YRC_MARKER([\\s\\S]*?)(?=$YRC_MARKER|\\z)")
private val lineSeparator = Regex("\\r?\\n")

internal fun MatchResult.lrcMillis(): Long =
    groups[1]!!.value.toLong() * 60_000 + groups[2]!!.value.toLong() * 1000 +
        (groups[3]?.value.orEmpty()).padEnd(3, '0').toLong()

internal suspend fun parseNativeTimedText(text: String, word: Boolean): ParsedSong {
    val context = currentCoroutineContext()
    val lines = ArrayList<ParsedLine>()
    val offset = lrcOffset.find(text)?.groups?.get(1)?.value?.toLong() ?: 0L
    for (raw in lineSeparator.splitToSequence(text)) {
        context.ensureActive()
        if (word) {
            val row = yrcLine.matchEntire(raw.trimLyricSpace()) ?: continue
            val begin = row.groupValues[1].toLong()
            var end = begin + row.groupValues[2].toLong()
            val tokens = ArrayList<ParsedToken>()
            val sung = buildString {
                for (match in yrcWord.findAll(row.groupValues[3])) {
                    val start = match.groupValues[1].toLong()
                    val finish = start + match.groupValues[2].toLong()
                    val part = match.groupValues[3]
                    end = maxOf(end, finish)
                    append(part)
                    tokens.add(ParsedToken(start, finish, part))
                }
            }
            if (!sung.isLyricBlank()) lines.add(ParsedLine(begin, end, ParsedVoice(sung, tokens)))
        } else {
            val sung = lrcTag.replace(raw, "").trimLyricSpace()
            if (sung.isEmpty()) continue
            for (match in lrcTimestamp.findAll(raw)) {
                lines.add(ParsedLine((match.lrcMillis() + offset).coerceAtLeast(0), 0, ParsedVoice(sung)))
            }
        }
    }
    lines.sortBy { it.begin }
    if (!word) for (i in lines.indices) lines[i].end = lines.getOrNull(i + 1)?.begin ?: (lines[i].begin + 5000)
    return ParsedSong(if (word) "Word" else "Line", lines.lastOrNull()?.end ?: 0, lines)
}

private fun nearest(lines: List<ParsedLine>, time: Long): ParsedVoice? {
    var distance = 501L
    var result: ParsedVoice? = null
    for (line in lines) {
        val delta = abs(line.begin - time)
        if (delta < distance) {
            distance = delta
            result = line.main
        }
    }
    return result
}

internal suspend fun applyTimedTextAnnotations(song: ParsedSong, data: JsonObject) {
    val translations = parseNativeTimedText(data.annotationText("rawTransText"), false).lines
    val romans = parseNativeTimedText(data.annotationText("rawRomaText"), false).lines
    val romanWords = parseNativeTimedText(data.annotationText("rawYromaText"), true).lines
    val context = currentCoroutineContext()
    for (line in song.lines) {
        context.ensureActive()
        line.main.translation = nearest(translations, line.begin)?.text.orEmpty()
        line.main.pronunciationTokens = nearest(romanWords, line.begin)?.tokens.orEmpty()
        line.main.pronunciation = nearest(romans, line.begin)?.text.orEmpty()
    }
}
