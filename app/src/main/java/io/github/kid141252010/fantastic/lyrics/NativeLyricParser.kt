package io.github.kid141252010.fantastic.lyrics

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.max

internal data class ParsedToken(
    val begin: Long,
    val end: Long,
    val text: String,
    val whitespace: Boolean = false,
    val spaceAfter: Boolean = false,
    val pronunciation: String = "",
) {
    val isTimed: Boolean get() = begin != 0L || end != 0L
}

internal class ParsedVoice(
    var text: String = "",
    var tokens: List<ParsedToken> = emptyList(),
    var translation: String = "",
    var pronunciation: String = "",
    var pronunciationTokens: List<ParsedToken> = emptyList(),
    var qrcBegin: Long? = null,
    var qrcEnd: Long? = null,
    var qrcReadingClock: QrcReadingClock? = null,
)

internal data class QrcReadingClock(
    val sourceBegin: Long,
    val sourceEnd: Long,
    val targetBegin: Long,
    val targetEnd: Long,
)

internal class ParsedLine(
    val begin: Long,
    var end: Long,
    val main: ParsedVoice,
    val background: ParsedVoice = ParsedVoice(),
    val paragraphBegin: Long? = null,
    val paragraphEnd: Long? = null,
    val key: String = "",
    val agent: String = "",
)

internal class ParsedSection(val begin: Long, val end: Long, val type: String, val lines: List<ParsedLine>)
internal class ParsedAgent(val type: String, val name: String)
internal class ParsedAnnotation(val language: String, val type: String, val automatic: Boolean)
internal class DuetLineLayout(val duetRightAligned: Boolean, val narrow: Boolean)

internal class ParsedSong(
    val timing: String,
    val duration: Long,
    val lines: List<ParsedLine>,
    val sections: List<ParsedSection> = emptyList(),
    val agents: Map<String, ParsedAgent> = emptyMap(),
    val credits: List<String> = emptyList(),
    val audioOffsets: Map<String, Long> = emptyMap(),
    val translation: ParsedAnnotation? = null,
    val pronunciation: ParsedAnnotation? = null,
    val language: String = "",
    val authors: List<String> = emptyList(),
)

// Match ECMAScript whitespace, including BOM and excluding NEL and U+0085-like controls.
internal fun Char.isLyricSpace(): Boolean = when (this) {
    in '\u0009'..'\u000d', ' ', '\u00a0', '\u1680', in '\u2000'..'\u200a',
    '\u2028', '\u2029', '\u202f', '\u205f', '\u3000', '\ufeff' -> true
    else -> false
}

internal fun String.trimLyricSpace(): String = trim(Char::isLyricSpace)
internal fun String.isLyricBlank(): Boolean = all(Char::isLyricSpace)

internal fun String.collapseLyricSpace(): String = buildString(length) {
    var gap = false
    for (character in this@collapseLyricSpace) {
        if (character.isLyricSpace()) {
            gap = isNotEmpty()
        } else {
            if (gap) append(' ')
            append(character)
            gap = false
        }
    }
}

internal fun ParsedToken.tokenText(): String =
    if (spaceAfter && text.lastOrNull()?.isLyricSpace() != true) "$text " else text

internal const val MAX_SAFE_LYRIC_INTEGER = 9_007_199_254_740_991L

private val neteaseSources = arrayOf("amll", "yrc", "lrc")
private val qqSources = arrayOf("amll", "qrc", "lrc")

private fun JsonObject.stringValue(key: String): String? =
    (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonPrimitive.isFalsy(): Boolean = if (isString) content.isEmpty() else
    content == "false" || content.toDoubleOrNull() == 0.0

internal fun JsonObject.annotationText(key: String): String {
    val value = get(key)
    if (value == null || value == JsonNull) return ""
    if (value is JsonPrimitive) {
        if (value.isString) return value.content
        if (value.isFalsy()) return ""
    }
    error("歌词注释不是文本：$key")
}

internal suspend fun selectNativeLyrics(kind: String, data: JsonObject, language: String): ParsedSong? {
    val context = currentCoroutineContext()
    val candidates = when (val value = data["candidates"]) {
        null, JsonNull -> return null
        is JsonArray -> value
        is JsonPrimitive -> if (value.isString || value.isFalsy()) return null else error("歌词候选不是数组")
        else -> error("歌词候选不是数组")
    }
    val order = if (kind == "ncm") neteaseSources else qqSources
    for (source in order) for (element in candidates) {
        context.ensureActive()
        check(element != JsonNull) { "歌词候选为空" }
        val candidate = element as? JsonObject ?: continue
        if (candidate.stringValue("source") != source) continue
        val text = candidate.stringValue("text")?.takeUnless { it.isLyricBlank() } ?: continue
        val rawNetease = kind == "ncm" && source != "amll"
        val rawQrc = kind == "qq" && source == "qrc"
        try {
            val song = if (rawNetease) parseNativeTimedText(text, word = source == "yrc")
                else parseNativeTtml(text, language, qrcBackgroundTiming = rawQrc)
            if (song.lines.isEmpty()) continue
            if (rawNetease) applyTimedTextAnnotations(song, data)
            if (rawQrc) applyQrcAnnotations(song, data.stringValue("rawRomaText"), data.stringValue("rawTransText"))
            return song
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A malformed candidate must not suppress the next upstream source.
        }
    }
    return null
}

internal suspend fun ParsedSong.toLyricSong(): LyricSong {
    val context = currentCoroutineContext()
    val layout = duetLineLayout(this)
    return LyricSong(
        timing = timing,
        duration = duration,
        lines = lines.mapIndexed { index, line ->
            context.ensureActive()
            var earliest = Long.MAX_VALUE
            var latest = line.end
            for (token in line.main.tokens) if (token.isTimed) {
                earliest = minOf(earliest, token.begin)
                latest = max(latest, token.end)
            }
            for (token in line.background.tokens) if (token.isTimed) {
                earliest = minOf(earliest, token.begin)
                latest = max(latest, token.end)
            }
            val begin = if (line.begin != 0L) line.begin else earliest.takeUnless { it == Long.MAX_VALUE } ?: 0L
            val duet = layout?.get(index)
            val rightAligned = duet?.duetRightAligned == true
            LyricLine(
                begin = begin,
                end = max(begin, latest),
                main = line.main.toLyricVoice(timing == "Word", rightAligned),
                background = line.background.toLyricVoice(timing == "Word", rightAligned),
                duetRightAligned = duet?.duetRightAligned,
                narrow = duet?.narrow == true,
                paragraphBegin = line.paragraphBegin,
                paragraphEnd = line.paragraphEnd,
            )
        },
        credits = credits,
        audioOffsets = audioOffsets,
        translationAutomatic = translation?.automatic == true,
        pronunciationAutomatic = pronunciation?.automatic == true,
    )
}

private fun normalizeContainerTokens(tokens: List<ParsedToken>, rightAligned: Boolean): List<ParsedToken> {
    if (tokens.isEmpty()) return emptyList()
    val normalized = ArrayList<ParsedToken>(tokens.size)
    var pending = false
    for (token in tokens) {
        val value = token.text
        val first = value.indexOfFirst { !it.isLyricSpace() }
        if (first < 0) {
            pending = pending || value.isNotEmpty() || token.spaceAfter
            continue
        }
        val end = value.indexOfLast { !it.isLyricSpace() } + 1
        val gap = pending || first != 0
        var text = value.substring(first, end)
        if (gap && normalized.isNotEmpty()) {
            if (rightAligned) text = " $text"
            else {
                val previous = normalized.last()
                normalized[normalized.lastIndex] = previous.copy(text = "${previous.text} ")
            }
        }
        normalized.add(if (text == value && !token.whitespace && !token.spaceAfter) token else
            token.copy(text = text, whitespace = false, spaceAfter = false))
        pending = end != value.length || token.spaceAfter
    }
    return normalized
}

private fun readingText(tokens: List<ParsedToken>): String = tokens.joinToString("") { it.text }.trimLyricSpace()

private fun pairReadings(tokens: List<ParsedToken>, readings: List<ParsedToken>): List<ParsedToken>? {
    if (readings.isEmpty() || tokens.none(ParsedToken::isTimed)) return null
    val positional = readings.size == tokens.size && readings.none(ParsedToken::isTimed)
    val starts = if (positional) emptyMap() else tokens.indices.filter { tokens[it].isTimed }.groupBy { tokens[it].begin }
    val paired = arrayOfNulls<StringBuilder>(tokens.size)
    var previous = -1
    for ((readingIndex, reading) in readings.withIndex()) {
        val index = if (positional) readingIndex else {
            if (!reading.isTimed) return null
            val sameStart = starts[reading.begin]
            when {
                sameStart == null -> tokens.indices.singleOrNull {
                    val token = tokens[it]
                    token.begin < reading.begin && reading.begin < token.end && reading.end <= token.end
                }
                sameStart.size == 1 -> sameStart[0]
                else -> sameStart.singleOrNull { tokens[it].end == reading.end }
            } ?: return null
        }
        if (index < previous) return null
        previous = index
        val text = paired[index] ?: StringBuilder().also { paired[index] = it }
        text.append(reading.text)
    }
    return tokens.mapIndexed { index, token -> paired[index]?.let { token.copy(pronunciation = it.toString()) } ?: token }
}

private fun ParsedVoice.toLyricVoice(wordTimed: Boolean, rightAligned: Boolean): LyricVoice {
    val normalized = normalizeContainerTokens(tokens, rightAligned)
    val readings = normalizeContainerTokens(pronunciationTokens, rightAligned)
    if (readings.isEmpty()) return LyricVoice(
        text = text,
        tokens = normalized.toLyricTokens(),
        translation = translation,
        pronunciation = pronunciation.takeUnless { it.isNotEmpty() && it.collapseLyricSpace() ==
            text.collapseLyricSpace().ifEmpty { readingText(normalized).collapseLyricSpace() } }.orEmpty(),
    )
    val original = text.collapseLyricSpace().ifEmpty { readingText(normalized).collapseLyricSpace() }
    val timed = readingText(readings)
    val timedReading = timed.takeUnless { original.isNotEmpty() && it.collapseLyricSpace() == original }.orEmpty()
    val plainReading = pronunciation.takeUnless { original.isNotEmpty() && it.collapseLyricSpace() == original }.orEmpty()
    val paired = if (wordTimed && timedReading.isNotEmpty()) {
        val clock = qrcReadingClock
        if (clock == null) pairReadings(normalized, readings) else pairQrcReadings(normalized, readings, clock)
    } else null
    return LyricVoice(
        text = text,
        tokens = (paired ?: normalized).toLyricTokens(),
        translation = translation,
        pronunciation = if (paired == null) plainReading.ifEmpty { timedReading } else "",
    )
}

private fun List<ParsedToken>.toLyricTokens(): List<LyricToken> =
    map { LyricToken(it.begin, it.end, it.text, pronunciation = it.pronunciation) }
