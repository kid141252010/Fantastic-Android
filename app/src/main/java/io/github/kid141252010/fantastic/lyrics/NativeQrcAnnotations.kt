package io.github.kid141252010.fantastic.lyrics

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

private const val MAX_ROWS = 768
private const val MAX_SEGMENTS = 256
private const val LYRIC_SPACE = "[\\u0009-\\u000d \\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff]"
private val lyricContentAttribute = Regex("LyricContent$LYRIC_SPACE*=$LYRIC_SPACE*([\"'])", RegexOption.IGNORE_CASE)
private val entities = Regex("&(?:amp|lt|gt|quot|apos|#[0-9]+|#x[0-9a-f]+);", RegexOption.IGNORE_CASE)
private val qrcRow = Regex("\\[([0-9]+),([0-9]+)]")
private val marker = Regex("\\(([^()]*)\\)")
private val tuple = Regex("([0-9]+),([0-9]+)")
private val offsetTag = Regex("\\[offset:([+-]?[0-9]+)]", RegexOption.IGNORE_CASE)
private val lrcPrefix = Regex("^(?:${lrcTimestamp.pattern}$LYRIC_SPACE*)+")
private val lineSeparator = Regex("\\r?\\n")

private fun Long.valid(): Boolean = this in 0..MAX_SAFE_LYRIC_INTEGER
private fun useful(value: String): String = value.collapseLyricSpace().takeUnless { it == "//" }.orEmpty()
private fun ParsedToken.visible(): Boolean = text.trimLyricSpace().let { it.isNotEmpty() && it != "//" }
private fun ParsedToken.timed(): Boolean = isTimed && visible() && begin.valid() && end.valid() && end >= begin

private fun decodeEntities(value: String): String = entities.replace(value) { match ->
    val name = match.value.substring(1, match.value.lastIndex).lowercase()
    when (name) {
        "amp" -> "&"
        "lt" -> "<"
        "gt" -> ">"
        "quot" -> "\""
        "apos" -> "'"
        else -> {
            val hex = name.startsWith("#x")
            val point = name.substring(if (hex) 2 else 1).toIntOrNull(if (hex) 16 else 10)
            if (point != null && point in 0..0x10ffff && point !in 0xd800..0xdfff) String(Character.toChars(point)) else match.value
        }
    }
}

private fun lyricContent(value: String): String {
    val attribute = lyricContentAttribute.find(value) ?: return value
    val start = attribute.range.last + 1
    val quote = attribute.groupValues[1].single()
    var cursor = start
    while (true) {
        val end = value.indexOf(quote, cursor)
        if (end < 0) return decodeEntities(value.substring(start))
        cursor = end + 1
        var tagEnd = cursor
        while (tagEnd < value.length && value[tagEnd].isLyricSpace()) tagEnd++
        if (cursor == value.length || value.startsWith("/>", tagEnd)) return decodeEntities(value.substring(start, end))
    }
}

private class Bounds(val begin: Long, val end: Long)
private fun MatchResult.bounds(): Bounds? {
    val begin = groupValues[1].toLongOrNull()?.takeIf { it.valid() } ?: return null
    val duration = groupValues[2].toLongOrNull()?.takeIf { it.valid() } ?: return null
    val end = begin + duration
    return if (end.valid()) Bounds(begin, end) else null
}

private class Source(
    val begin: Long,
    var end: Long,
    val text: String,
    val tokens: List<ParsedToken>,
    val hasDuration: Boolean,
)

private suspend fun parseQrc(text: String): List<Source> {
    val context = currentCoroutineContext()
    val rows = ArrayList<Source>()
    val seen = HashSet<String>()
    val matches = qrcRow.findAll(text).iterator()
    var next = if (matches.hasNext()) matches.next() else null
    while (next != null && rows.size < MAX_ROWS) {
        context.ensureActive()
        val row = next
        next = if (matches.hasNext()) matches.next() else null
        val bounds = row.bounds() ?: continue
        val body = text.substring(row.range.last + 1, next?.range?.first ?: text.length)
        val tokens = ArrayList<ParsedToken>()
        val parts = StringBuilder(body.length)
        var cursor = 0
        for (match in marker.findAll(body)) {
            val segment = body.substring(cursor, match.range.first)
            parts.append(segment)
            val value = match.groupValues[1]
            val timing = tuple.matchEntire(value)?.bounds()
            if (timing != null) {
                if (tokens.size <= MAX_SEGMENTS && useful(segment).isNotEmpty()) {
                    tokens.add(ParsedToken(timing.begin, timing.end, segment, spaceAfter = segment.lastOrNull()?.isLyricSpace() == true))
                }
            } else if (',' !in value) {
                parts.append(match.value)
            }
            cursor = match.range.last + 1
        }
        val tail = body.substring(cursor)
        parts.append(tail)
        if (tokens.isNotEmpty() && tail.isNotEmpty()) {
            val last = tokens.last()
            tokens[tokens.lastIndex] = last.copy(text = last.text + tail)
        }
        val value = useful(parts.toString())
        if (value.isEmpty()) continue
        val kept = if (tokens.size > MAX_SEGMENTS) emptyList() else tokens
        val key = buildString {
            append(bounds.begin).append(',').append(bounds.end).append('|').append(value).append('|')
            kept.forEachIndexed { index, token ->
                if (index != 0) append('|')
                append(token.begin).append(',').append(token.end).append(',').append(token.text)
            }
        }
        if (seen.add(key)) rows.add(Source(bounds.begin, bounds.end, value, kept, hasDuration = true))
    }
    rows.sortBy { it.begin }
    return rows
}

private suspend fun parseLrc(text: String): List<Source> {
    val context = currentCoroutineContext()
    var offset = 0L
    val rows = ArrayList<Source>()
    val seen = HashSet<Pair<Long, String>>()
    for (raw in lineSeparator.splitToSequence(text)) {
        context.ensureActive()
        val metadata = offsetTag.matchEntire(raw.trimLyricSpace())
        if (metadata != null) {
            offset = metadata.groupValues[1].toLongOrNull()?.takeIf { it in -MAX_SAFE_LYRIC_INTEGER..MAX_SAFE_LYRIC_INTEGER } ?: 0
            continue
        }
        val prefix = lrcPrefix.find(raw)?.value ?: continue
        val body = useful(raw.substring(prefix.length))
        if (body.isEmpty()) continue
        for (match in lrcTimestamp.findAll(prefix)) {
            val timestamp = match.lrcMillisOrNull() ?: continue
            val begin = (timestamp - offset).coerceAtLeast(0)
            if (!begin.valid() || !seen.add(begin to body)) continue
            rows.add(Source(begin, begin, body, emptyList(), hasDuration = false))
            if (rows.size >= MAX_ROWS) return rows
        }
    }
    rows.sortBy { it.begin }
    for (i in rows.indices) rows[i].end = rows.getOrNull(i + 1)?.begin ?: (rows[i].begin + 5000)
    return rows
}

private fun MatchResult.lrcMillisOrNull(): Long? {
    val minutes = groupValues[1].toLongOrNull() ?: return null
    if (minutes > Long.MAX_VALUE / 60_000 - 1) return null
    return lrcMillis()
}

private class Target(val voice: ParsedVoice, val background: Boolean, val begin: Long, val end: Long)
private class Group(val begin: Long, val members: MutableList<Target>, var window: Double = 1000.0)

private fun voiceBounds(line: ParsedLine, voice: ParsedVoice, background: Boolean): Bounds {
    val begin = if (background) voice.qrcBegin else line.begin
    val end = if (background) voice.qrcEnd else line.end
    if (begin != null && end != null && begin.valid() && end.valid() && end >= begin) return Bounds(begin, end)
    var minimum = Long.MAX_VALUE
    var maximum = Long.MIN_VALUE
    for (token in voice.tokens) if (token.visible() && token.begin.valid() && token.end.valid() && token.end >= token.begin) {
        minimum = minOf(minimum, token.begin)
        maximum = maxOf(maximum, token.end)
    }
    return if (minimum == Long.MAX_VALUE) Bounds(line.begin, line.end) else Bounds(minimum, maximum)
}

private suspend fun targetGroups(song: ParsedSong): List<Group> {
    val context = currentCoroutineContext()
    val records = ArrayList<Target>(song.lines.size)
    fun add(line: ParsedLine, voice: ParsedVoice, background: Boolean) {
        if (useful(voice.text).isEmpty() && voice.tokens.none { it.visible() }) return
        val bounds = voiceBounds(line, voice, background)
        if (bounds.begin.valid() && bounds.end.valid() && bounds.end >= bounds.begin) {
            records.add(Target(voice, background, bounds.begin, bounds.end))
        }
    }
    for (line in song.lines) {
        context.ensureActive()
        add(line, line.main, false)
        add(line, line.background, true)
    }
    records.sortBy { it.begin }
    val groups = ArrayList<Group>(records.size)
    for (record in records) {
        val previous = groups.lastOrNull()
        if (previous == null || previous.begin != record.begin) groups.add(Group(record.begin, arrayListOf(record)))
        else previous.members.add(record)
    }
    for ((i, group) in groups.withIndex()) {
        val before = groups.getOrNull(i - 1)?.let { group.begin - it.begin } ?: Long.MAX_VALUE
        val after = groups.getOrNull(i + 1)?.let { it.begin - group.begin } ?: Long.MAX_VALUE
        group.window = minOf(1000.0, minOf(before, after) * 0.4)
    }
    return groups
}

private class Match(val sourceIndex: Int, val groupIndex: Int, val member: Target, val residual: Double)
private data class Projection(val scale: Double, val offset: Double)
private val identity = Projection(1.0, 0.0)

private fun chooseMember(source: Source, group: Group): Target? {
    val duration = source.end - source.begin
    var candidate: Target? = null
    var main: Target? = null
    var count = 0
    var mainCount = 0
    for (member in group.members) {
        if (source.hasDuration && abs(member.end - member.begin - duration) > 20) continue
        candidate = member
        count++
        if (!member.background) {
            main = member
            mainCount++
        }
    }
    return when {
        count == 1 -> candidate
        mainCount == 1 -> main
        else -> null
    }
}

private class MemberMatches(var best: Match, var runnerUpResidual: Double = Double.POSITIVE_INFINITY) {
    fun add(match: Match) {
        if (match.residual < best.residual) {
            runnerUpResidual = best.residual
            best = match
        } else {
            runnerUpResidual = minOf(runnerUpResidual, match.residual)
        }
    }
}

private suspend fun projectMatches(sources: List<Source>, groups: List<Group>, projection: Projection): List<Match> {
    val context = currentCoroutineContext()
    val byMember = HashMap<Target, MemberMatches>(sources.size)
    for ((sourceIndex, source) in sources.withIndex()) {
        context.ensureActive()
        val projected = source.begin * projection.scale + projection.offset
        var low = 0
        var high = groups.size
        // Each window occupies at most 40% of either adjacent gap. Only the
        // groups immediately before and after the projection can contain it.
        while (low < high) {
            val middle = (low + high) ushr 1
            if (groups[middle].begin < projected) low = middle + 1 else high = middle
        }
        for (index in maxOf(0, low - 1) until minOf(low + 1, groups.size)) {
            val group = groups[index]
            val residual = abs(group.begin - projected)
            if (residual > group.window) continue
            val member = chooseMember(source, group) ?: continue
            val match = Match(sourceIndex, index, member, residual)
            val existing = byMember[member]
            if (existing == null) byMember[member] = MemberMatches(match) else existing.add(match)
            break
        }
    }
    val matches = ArrayList<Match>(byMember.size)
    for (entry in byMember.values) {
        if (entry.best.residual + 10 < entry.runnerUpResidual) matches.add(entry.best)
    }
    matches.sortBy { it.sourceIndex }
    return matches
}

private inline fun median(size: Int, value: (Int) -> Long): Double {
    val sorted = LongArray(size)
    for (index in sorted.indices) sorted[index] = value(index)
    sorted.sort()
    val middle = size / 2
    return if (size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2.0 else sorted[middle].toDouble()
}

private fun fit(matches: List<Match>, sources: List<Source>, groups: List<Group>): Projection? {
    if (matches.size < 6) return null
    val count = matches.size / 3
    val last = matches.size - count
    val t0 = median(count) { sources[matches[it].sourceIndex].begin }
    val t1 = median(count) { sources[matches[last + it].sourceIndex].begin }
    val d0 = median(count) { groups[matches[it].groupIndex].begin - sources[matches[it].sourceIndex].begin }
    val d1 = median(count) { groups[matches[last + it].groupIndex].begin - sources[matches[last + it].sourceIndex].begin }
    if (t1 - t0 < 10000) return null
    val slope = (d1 - d0) / (t1 - t0)
    if (abs(slope) > 0.02) return null
    return Projection(1 + slope, d0 - slope * t0)
}

private class Evaluation(
    val projection: Projection,
    val matches: List<Match>,
    val count: Int,
    val residual: Double,
    val signature: List<Triple<Int, Int, Boolean>>,
)

private fun jsRound(value: Double): Long {
    val lower = floor(value)
    return if (value - lower >= 0.5) lower.toLong() + 1 else lower.toLong()
}

private suspend fun alignSources(sources: List<Source>, groups: List<Group>): List<Match> {
    if (sources.isEmpty() || groups.isEmpty()) return emptyList()
    val context = currentCoroutineContext()
    val projections = ArrayList<Projection>(52)
    projections.add(identity)
    for (size in intArrayOf(50, 250)) {
        val counts = HashMap<Long, Int>()
        val rowGrids = HashSet<Long>(groups.size)
        for (source in sources) {
            context.ensureActive()
            rowGrids.clear()
            for (group in groups) rowGrids.add(jsRound((group.begin - source.begin).toDouble() / size) * size)
            for (offset in rowGrids) counts[offset] = (counts[offset] ?: 0) + 1
        }
        counts.entries.sortedWith(compareByDescending<Map.Entry<Long, Int>> { it.value }.thenBy { abs(it.key) }.thenBy { it.key })
            .take(6).forEach { projections.add(Projection(1.0, it.key.toDouble())) }
    }
    val initial = projections.size
    for (index in 0 until initial) {
        context.ensureActive()
        val projection = projections[index]
        val matches = projectMatches(sources, groups, projection)
        if (matches.isEmpty()) continue
        projections.add(Projection(projection.scale, median(matches.size) {
            val match = matches[it]
            groups[match.groupIndex].begin - sources[match.sourceIndex].begin
        }))
        val fitted = fit(matches, sources, groups) ?: continue
        projections.add(fitted)
        val refined = projectMatches(sources, groups, fitted).filter { it.residual <= 80 }
        fit(refined, sources, groups)?.let(projections::add)
    }
    val required = maxOf(3, ceil(0.6 * minOf(sources.size, groups.size)).toInt())
    val evaluated = ArrayList<Evaluation>()
    for (projection in projections.distinctBy { Projection(it.scale, it.offset + 0.0) }) {
        context.ensureActive()
        val matches = projectMatches(sources, groups, projection)
        val anchors = matches.filter { it.residual <= 80 }
        val count = anchors.mapTo(HashSet()) { it.groupIndex }.size
        if (count < required) continue
        val first = anchors.first().sourceIndex
        val last = anchors.last().sourceIndex
        evaluated.add(Evaluation(projection, matches.filter { it.sourceIndex in first..last }, count, anchors.sumOf { it.residual },
            anchors.map { Triple(it.sourceIndex, it.groupIndex, it.member.background) }))
    }
    val best = evaluated.minWithOrNull(compareByDescending<Evaluation> { it.count }.thenBy { it.residual }
        .thenBy { abs(it.projection.scale - 1) }.thenBy { abs(it.projection.offset) }
        .thenBy { it.projection.scale }.thenBy { it.projection.offset })
        ?: return projectMatches(sources, groups, identity).filter { it.residual <= 20 }
    return if (evaluated.any { it.count == best.count && it.residual == best.residual && it.signature != best.signature }) emptyList()
    else best.matches
}

private class ReadingProjection(val sourceBegin: Long, val targetBegin: Long, val scale: Double?) {
    fun project(time: Long): Long = if (scale == null) time + targetBegin - sourceBegin
        else jsRound(targetBegin + (time - sourceBegin) * scale)
}

internal fun pairQrcReadings(tokens: List<ParsedToken>, readings: List<ParsedToken>, clock: QrcReadingClock): List<ParsedToken>? {
    val main = tokens.indices.filter { tokens[it].timed() }
    val source = readings.filter { it.timed() }
    if (main.isEmpty() || source.isEmpty()) return null
    val projections = ArrayList<ReadingProjection>(3)
    projections.add(ReadingProjection(clock.sourceBegin, clock.targetBegin, null))
    val sourceDuration = clock.sourceEnd - clock.sourceBegin
    val targetDuration = clock.targetEnd - clock.targetBegin
    if (sourceDuration > 0 && targetDuration > 0) {
        val ratio = targetDuration.toDouble() / sourceDuration
        if (ratio in 0.5..2.0) projections.add(ReadingProjection(clock.sourceBegin, clock.targetBegin, ratio))
    }
    projections.add(ReadingProjection(source.first().begin, tokens[main.first()].begin, null))
    for (projection in projections) {
        val spans = arrayOfNulls<ReadingSpan>(tokens.size)
        var previous = -1
        var distinct = 0
        var exactCoverage = source.size == main.size
        for (reading in source) {
            val begin = projection.project(reading.begin)
            val end = projection.project(reading.end)
            val length = (end - begin).coerceAtLeast(0)
            var exactIndex = -1
            var exactCount = 0
            var containedIndex = -1
            var containedCount = 0
            for (index in main) {
                val token = tokens[index]
                if (begin == token.begin && end == token.end) {
                    exactIndex = index
                    exactCount++
                } else if (begin >= token.begin - 10 && end <= token.end + 10) {
                    val overlap = (minOf(end, token.end) - maxOf(begin, token.begin)).coerceAtLeast(0)
                    if (if (length == 0L) begin <= token.end + 10 else overlap * 2 >= length) {
                        containedIndex = index
                        containedCount++
                    }
                }
            }
            val index = when {
                exactCount == 1 -> exactIndex
                exactCount == 0 && containedCount == 1 -> containedIndex
                else -> -1
            }
            if (index < 0 || index < previous) {
                previous = -1
                break
            }
            previous = index
            exactCoverage = exactCoverage && exactCount == 1
            val span = spans[index] ?: ReadingSpan(begin, end).also { spans[index] = it; distinct++ }
            span.begin = minOf(span.begin, begin)
            span.end = maxOf(span.end, end)
            span.reading.append(reading.tokenText())
        }
        if (previous < 0 || (distinct < 3 && !exactCoverage)) continue
        var accurate = 0
        for (index in main) {
            val span = spans[index] ?: continue
            val token = tokens[index]
            if (abs(span.begin - token.begin) <= 10 && abs(span.end - token.end) <= 10) accurate++
        }
        if (accurate * 2 < distinct) continue
        return tokens.mapIndexed { index, token ->
            val pronunciation = spans[index]?.reading?.toString().orEmpty()
            if (pronunciation == token.pronunciation) token else token.copy(pronunciation = pronunciation)
        }
    }
    return null
}

private class ReadingSpan(var begin: Long, var end: Long, val reading: StringBuilder = StringBuilder())

private suspend fun applyCategory(raw: String?, groups: List<Group>, translation: Boolean) {
    if (raw == null || raw.isLyricBlank()) return
    val text = lyricContent(raw)
    val sources = parseQrc(text).ifEmpty { parseLrc(text) }
    if (sources.isEmpty()) return
    for (group in groups) for (target in group.members) {
        if (translation) target.voice.translation = "" else {
            target.voice.pronunciation = ""
            target.voice.pronunciationTokens = emptyList()
            target.voice.qrcReadingClock = null
        }
    }
    for (match in alignSources(sources, groups)) {
        val target = match.member
        val source = sources[match.sourceIndex]
        val value = if (target.background) unwrapBackingText(source.text) else source.text
        if (translation) target.voice.translation = value else {
            target.voice.pronunciation = value
            target.voice.pronunciationTokens = if (target.background) unwrapBackingTokens(source.tokens) else source.tokens
            target.voice.qrcReadingClock = QrcReadingClock(source.begin, source.end, target.begin, target.end)
        }
    }
}

internal suspend fun applyQrcAnnotations(song: ParsedSong, romans: String?, translations: String?) {
    val groups = targetGroups(song)
    if (groups.isEmpty() || groups.size > MAX_ROWS) return
    applyCategory(romans, groups, translation = false)
    applyCategory(translations, groups, translation = true)
}
