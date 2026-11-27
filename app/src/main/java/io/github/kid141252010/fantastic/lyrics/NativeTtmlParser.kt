package io.github.kid141252010.fantastic.lyrics

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import org.xmlpull.v1.XmlPullParserFactory
import java.io.IOException
import java.io.StringReader
import kotlin.math.floor

private const val APPLE_NS = "http://music.apple.com/lyric-ttml-internal"
private const val META_NS = "http://www.w3.org/ns/ttml#metadata"
private const val XML_NS = "http://www.w3.org/XML/1998/namespace"
private const val INVALID_XML = "无法解析 TTML：XML 格式不正确"
private val timeField = Regex("[0-9]+(?:\\.[0-9]+)?")
private val script = Regex("[a-z]{4}")
private val region = Regex("[a-z]{2}|[0-9]{3}")

private sealed interface TtmlChild {
    class Text(val value: String, val cdata: Boolean) : TtmlChild
}

private data class AttributeName(val namespace: String, val name: String)

private class TtmlElement(
    val namespace: String,
    val name: String,
    private val attributes: Map<AttributeName, String>,
) : TtmlChild {
    val children = ArrayList<TtmlChild>()

    fun attr(name: String, namespace: String = "", fallback: String = ""): String =
        attributes[AttributeName(namespace, name)]?.ifEmpty { fallback } ?: fallback

    val role: String get() = attr("role", META_NS)

    fun first(name: String): TtmlElement? = children.firstNotNullOfOrNull {
        (it as? TtmlElement)?.takeIf { element -> element.name == name }
    }

    fun elements(name: String): List<TtmlElement> = children.mapNotNull {
        (it as? TtmlElement)?.takeIf { element -> element.name == name }
    }

    fun appendText(out: StringBuilder) {
        for (child in children) when (child) {
            is TtmlChild.Text -> out.append(child.value)
            is TtmlElement -> child.appendText(out)
        }
    }

    fun textContent(): String = buildString { appendText(this) }
}

private suspend fun readTtml(xml: String): TtmlElement {
    val context = currentCoroutineContext()
    val stack = ArrayList<TtmlElement>()
    var root: TtmlElement? = null
    val text = StringBuilder()
    fun flushText() {
        if (text.isEmpty()) return
        val element = stack.lastOrNull()
        if (element == null) {
            require(text.all { it == ' ' || it == '\t' || it == '\r' || it == '\n' }) { INVALID_XML }
        } else {
            element.children.add(TtmlChild.Text(text.toString(), cdata = false))
        }
        text.setLength(0)
    }
    try {
        val parser = XmlPullParserFactory.newInstance().newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            if (getFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL)) setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
            if (getFeature(XmlPullParser.FEATURE_VALIDATION)) setFeature(XmlPullParser.FEATURE_VALIDATION, false)
            setInput(StringReader(xml))
        }
        var events = 0
        while (true) {
            if (++events % 256 == 0) context.ensureActive()
            val event = parser.nextToken()
            if (event == XmlPullParser.TEXT || event == XmlPullParser.ENTITY_REF) {
                text.append(requireNotNull(parser.text) { INVALID_XML })
                continue
            }
            flushText()
            when (event) {
                XmlPullParser.END_DOCUMENT -> break
                XmlPullParser.START_TAG -> {
                    // Namespace-aware readers omit xmlns attributes from attributeCount.
                    val namespaceStart = parser.getNamespaceCount(parser.depth - 1)
                    val namespaceEnd = parser.getNamespaceCount(parser.depth)
                    for (i in namespaceStart until namespaceEnd) {
                        for (j in namespaceStart until i) {
                            require(parser.getNamespacePrefix(i) != parser.getNamespacePrefix(j)) { INVALID_XML }
                        }
                    }
                    val attributes = HashMap<AttributeName, String>(parser.attributeCount * 2)
                    for (i in 0 until parser.attributeCount) {
                        val name = AttributeName(parser.getAttributeNamespace(i).orEmpty(), parser.getAttributeName(i))
                        require(attributes.put(name, parser.getAttributeValue(i)) == null) { INVALID_XML }
                    }
                    val element = TtmlElement(parser.namespace.orEmpty(), parser.name, attributes)
                    require(element.name != "parsererror" || parser.prefix != null) { INVALID_XML }
                    if (stack.isEmpty()) {
                        require(root == null && element.name == "tt") { INVALID_XML }
                        root = element
                    } else {
                        stack.last().children.add(element)
                    }
                    stack.add(element)
                }
                XmlPullParser.END_TAG -> stack.removeAt(stack.lastIndex)
                XmlPullParser.CDSECT -> requireNotNull(stack.lastOrNull()) { INVALID_XML }
                    .children.add(TtmlChild.Text(parser.text, cdata = true))
            }
        }
    } catch (e: XmlPullParserException) {
        throw IllegalArgumentException(INVALID_XML, e)
    } catch (e: IOException) {
        throw IllegalArgumentException(INVALID_XML, e)
    }
    return requireNotNull(root) { INVALID_XML }
}

private fun parseTime(value: String): Long {
    val fields = value.trimLyricSpace().split(':')
    require(fields.size <= 3 && fields.all(timeField::matches)) { "无效的时间：$value" }
    var seconds = 0.0
    for (field in fields) seconds = seconds * 60 + field.toDouble()
    val millis = seconds * 1000
    return if (millis - floor(millis) >= 0.5) millis.toLong() + 1 else millis.toLong()
}

private fun optionalTime(value: String): Long? = if (value.isEmpty()) null else {
    try {
        parseTime(value).takeIf { it in 0..MAX_SAFE_LYRIC_INTEGER }
    } catch (_: IllegalArgumentException) {
        null
    }
}

private fun normalizedLanguage(value: String): String = value.trimLyricSpace().replace('_', '-').lowercase()

private fun languageRank(candidate: String, preferred: List<String>): Int {
    val parts = normalizedLanguage(candidate).split('-').filter(String::isNotEmpty)
    if (preferred.isEmpty() || parts.isEmpty()) return 0
    if (parts == preferred) return 5
    if (parts.first() != preferred.first()) return 0
    val candidateScript = parts.firstOrNull(script::matches)
    val preferredScript = preferred.firstOrNull(script::matches)
    if (candidateScript != null && preferredScript != null) return if (candidateScript == preferredScript) 4 else 1
    val candidateRegion = parts.firstOrNull(region::matches)
    return if (candidateRegion != null && candidateRegion == preferred.firstOrNull(region::matches)) 3 else 2
}

private fun chooseLanguage(items: List<TtmlElement>, preferred: List<String>): TtmlElement? =
    items.maxByOrNull { languageRank(it.attr("lang", XML_NS), preferred) }

private class LocalizedRecords(node: TtmlElement?) {
    val metadata = ParsedAnnotation(
        node?.attr("lang", XML_NS).orEmpty(),
        node?.attr("type").orEmpty(),
        node?.attr("automaticallyCreated") == "true",
    )
    val entries = node?.elements("text")?.associateBy { it.attr("for") }.orEmpty()
}

private fun TtmlElement?.inlineRole(role: String, preferred: List<String>): TtmlElement? =
    chooseLanguage(this?.elements("span")?.filter { it.role == role }.orEmpty(), preferred)

private fun TtmlElement?.textWithoutRoles(): String {
    if (this == null) return ""
    return buildString {
        for (child in children) when (child) {
            is TtmlChild.Text -> if (!child.cdata) append(child.value) else Unit
            is TtmlElement -> if (child.role.isEmpty()) child.appendText(this) else Unit
        }
    }.collapseLyricSpace()
}

private fun TtmlElement?.wordSpans(): List<ParsedToken> {
    if (this == null) return emptyList()
    val tokens = ArrayList<ParsedToken>(children.size)
    for (child in children) when (child) {
        is TtmlChild.Text -> if (!child.cdata && child.value.isNotEmpty() && child.value.isLyricBlank()) {
            tokens.add(ParsedToken(0, 0, child.value, whitespace = true))
        } else Unit
        is TtmlElement -> if (child.name == "span" && child.role.isEmpty()) {
            val text = child.textContent()
            tokens.add(ParsedToken(parseTime(child.attr("begin", fallback = "0")), parseTime(child.attr("end", fallback = "0")), text, text.isLyricBlank()))
        } else Unit
    }
    return tokens
}

internal fun finalLyricParenthesisGroup(text: String): IntRange? {
    val close = text.indexOfLast { !it.isLyricSpace() }
    if (close < 0 || text[close] != ')' && text[close] != '）') return null
    val stack = StringBuilder()
    var open = -1
    for (index in 0..close) when (val ch = text[index]) {
        '(', '（' -> {
            if (stack.isEmpty()) open = index
            stack.append(if (ch == '(') ')' else '）')
        }
        ')', '）' -> {
            if (stack.isEmpty() || stack.last() != ch) return null
            stack.setLength(stack.length - 1)
            if (index == close && stack.isEmpty() && !text.substring(open + 1, close).isLyricBlank()) return open..close
        }
    }
    return null
}

private fun backingWrapper(text: String): IntRange? =
    finalLyricParenthesisGroup(text)?.takeIf { text.substring(0, it.first).isLyricBlank() }

internal fun unwrapBackingText(text: String): String = backingWrapper(text)?.let {
    text.substring(it.first + 1, it.last).trimLyricSpace()
} ?: text

internal fun unwrapBackingTokens(tokens: List<ParsedToken>): List<ParsedToken> {
    val first = tokens.firstNotNullOfOrNull { token -> token.text.firstOrNull { !it.isLyricSpace() } }
    if (first != '(' && first != '（') return tokens
    val group = backingWrapper(tokens.joinToString("") { it.text }) ?: return tokens
    var from = 0
    return tokens.map { token ->
        var value = token.text
        val until = from + value.length
        if (group.last in from until until) value = value.removeRange(group.last - from, group.last - from + 1)
        if (group.first in from until until) value = value.removeRange(group.first - from, group.first - from + 1)
        from = until
        if (value === token.text) token else token.copy(text = value)
    }
}

private fun ParsedVoice.setQrcBounds(node: TtmlElement, fallbackBegin: Long, fallbackEnd: Long) {
    val begin = optionalTime(node.attr("begin"))
    val end = optionalTime(node.attr("end"))
    if (begin != null && end != null && end >= begin) {
        qrcBegin = begin
        qrcEnd = end
        return
    }
    var minimum = Long.MAX_VALUE
    var maximum = Long.MIN_VALUE
    for (token in tokens) if (!token.whitespace && token.begin in 0..MAX_SAFE_LYRIC_INTEGER && token.end in token.begin..MAX_SAFE_LYRIC_INTEGER) {
        minimum = minOf(minimum, token.begin)
        maximum = maxOf(maximum, token.end)
    }
    qrcBegin = if (minimum == Long.MAX_VALUE) fallbackBegin else minimum
    qrcEnd = if (maximum == Long.MIN_VALUE) fallbackEnd else maximum
}

private fun voice(
    node: TtmlElement?,
    translationNode: TtmlElement?,
    romanizationNode: TtmlElement?,
    preferred: List<String>,
): ParsedVoice {
    val translation = translationNode ?: node.inlineRole("x-translation", preferred)
    val romanization = romanizationNode ?: node.inlineRole("x-roman", preferred)
    val readings = romanization.wordSpans()
    return ParsedVoice(
        text = node.textWithoutRoles(),
        tokens = node.wordSpans(),
        translation = translation?.textContent()?.trimLyricSpace().orEmpty(),
        pronunciation = if (readings.isEmpty()) romanization.textWithoutRoles() else "",
        pronunciationTokens = readings,
    )
}

private fun chineseScript(language: String): String {
    val parts = normalizedLanguage(language).split('-')
    if (parts.first() != "zh") return ""
    return parts.firstOrNull(script::matches) ?: when (parts.firstOrNull { it.length == 2 }) {
        "cn", "sg" -> "hans"
        "tw", "hk", "mo" -> "hant"
        else -> ""
    }
}

internal suspend fun parseNativeTtml(xml: String, preferredLanguage: String, qrcBackgroundTiming: Boolean = false): ParsedSong {
    val context = currentCoroutineContext()
    val root = readTtml(xml)
    val body = requireNotNull(root.first("body")) { "TTML 缺少 body" }
    val head = root.first("head")
    val headMetadata = head?.first("metadata")
    val metadata = headMetadata?.first("iTunesMetadata")
    val agents = LinkedHashMap<String, ParsedAgent>()
    for (agent in head?.elements("agent").orEmpty() + headMetadata?.elements("agent").orEmpty()) {
        if (agent.namespace != META_NS) continue
        val id = agent.attr("id", XML_NS)
        if (id.isEmpty()) continue
        val name = agent.elements("name").firstOrNull { it.namespace == META_NS }?.textContent()?.trimLyricSpace().orEmpty()
        agents[id] = ParsedAgent(agent.attr("type"), name)
    }

    val preferred = normalizedLanguage(preferredLanguage).split('-').filter(String::isNotEmpty)
    val translationNodes = ArrayList<TtmlElement>()
    for (block in headMetadata?.elements("iTunesMetadata").orEmpty()) {
        for (group in block.elements("translations")) {
            translationNodes.addAll(group.elements("translation"))
        }
    }
    val (replacements, translations) = translationNodes.partition { it.attr("type") == "replacement" }
    val translation = LocalizedRecords(chooseLanguage(translations, preferred))
    val replacement = LocalizedRecords(chooseLanguage(replacements, preferred))
    val pronunciation = LocalizedRecords(chooseLanguage(metadata?.first("transliterations")?.elements("transliteration").orEmpty(), preferred))
    val wantedScript = chineseScript(preferredLanguage)
    val replacementScript = chineseScript(replacement.metadata.language)
    val allowReplacement = wantedScript.isEmpty() || replacementScript.isEmpty() || wantedScript == replacementScript

    val audioOffsets = LinkedHashMap<String, Long>()
    for (audio in metadata?.elements("audio").orEmpty()) {
        val name = audio.attr("role")
        val offset = audio.attr("lyricOffset").trimLyricSpace().toDoubleOrNull()
        if (name.isNotEmpty() && offset != null && offset.isFinite() && offset != 0.0) {
            val millis = offset * 1000
            val roundedDown = floor(millis)
            audioOffsets[name] = (if (millis - roundedDown >= 0.5) roundedDown + 1 else roundedDown).toLong()
        }
    }

    val lines = ArrayList<ParsedLine>()
    val sections = body.elements("div").mapIndexed { sectionIndex, section ->
        val sectionBegin = parseTime(section.attr("begin", fallback = "0"))
        val sectionEnd = parseTime(section.attr("end", fallback = "0"))
        val sectionLines = section.elements("p").mapIndexed { lineIndex, paragraph ->
            context.ensureActive()
            val rawBegin = paragraph.attr("begin")
            val rawEnd = paragraph.attr("end")
            val begin = parseTime(rawBegin.ifEmpty { "0" })
            val end = parseTime(rawEnd.ifEmpty { "0" })
            val hasParagraphTiming = rawBegin.isNotEmpty() && rawEnd.isNotEmpty() && end > begin
            val key = paragraph.attr("key", APPLE_NS, "$sectionIndex:$lineIndex")
            val translationNode = translation.entries[key] ?: paragraph.inlineRole("x-translation", preferred)
            val pronunciationNode = pronunciation.entries[key] ?: paragraph.inlineRole("x-roman", preferred)
            val backgroundNode = paragraph.elements("span").firstOrNull { it.role == "x-bg" }
            val backgroundTranslations = translationNode?.elements("span")?.filter { it.role == "x-bg" }.orEmpty()
            val background = voice(
                backgroundNode,
                backgroundTranslations.firstOrNull { it.textWithoutRoles().isNotEmpty() },
                pronunciationNode?.elements("span")?.firstOrNull { it.role == "x-bg" && it.textWithoutRoles().isNotEmpty() },
                preferred,
            )
            val main = voice(paragraph, translationNode, pronunciationNode, preferred)
            if (qrcBackgroundTiming && backgroundNode != null) background.setQrcBounds(backgroundNode, begin, end)
            if (backgroundTranslations.isNotEmpty()) main.translation = translationNode.textWithoutRoles()
            if (allowReplacement) replacement.entries[key]?.let { node ->
                main.text = node.textWithoutRoles()
                main.tokens = node.wordSpans()
                node.elements("span").firstOrNull { it.role == "x-bg" }?.let {
                    background.text = it.textWithoutRoles()
                    background.tokens = it.wordSpans()
                }
            }
            background.text = unwrapBackingText(background.text)
            background.tokens = unwrapBackingTokens(background.tokens)
            background.pronunciation = unwrapBackingText(background.pronunciation)
            background.pronunciationTokens = unwrapBackingTokens(background.pronunciationTokens)
            background.translation = unwrapBackingText(background.translation)
            if (background.text.isNotEmpty() && backgroundTranslations.isEmpty() && background.translation.isEmpty()) {
                finalLyricParenthesisGroup(main.translation)?.let { group ->
                    val mainText = main.translation.substring(0, group.first).trimLyricSpace()
                    if (mainText.isNotEmpty()) {
                        background.translation = main.translation.substring(group.first + 1, group.last).trimLyricSpace()
                        main.translation = mainText
                    }
                }
            }
            ParsedLine(begin, end, main, background, begin.takeIf { hasParagraphTiming }, end.takeIf { hasParagraphTiming }, key, paragraph.attr("agent", META_NS))
                .also(lines::add)
        }
        ParsedSection(sectionBegin, sectionEnd, section.attr("songPart", APPLE_NS, "Verse").lowercase(), sectionLines)
    }
    val timing = root.attr("timing", APPLE_NS).ifEmpty {
        when {
            lines.any { line -> line.main.tokens.any { !it.whitespace } || line.background.tokens.any { !it.whitespace } } -> "Word"
            lines.any { it.begin != 0L || it.end != 0L } -> "Line"
            else -> "None"
        }
    }
    val duration = parseTime(body.attr("dur", fallback = "0")).takeUnless { it == 0L } ?: (lines.maxOfOrNull { it.end } ?: 0L)
    return ParsedSong(
        timing, duration, lines, sections, agents,
        credits = metadata?.first("songwriters")?.elements("songwriter")?.map { it.textWithoutRoles() }.orEmpty(),
        audioOffsets = audioOffsets,
        translation = translation.metadata,
        pronunciation = pronunciation.metadata,
        language = root.attr("lang", XML_NS, "en"),
        authors = head?.elements("meta")?.filter { it.attr("key") == "ttmlAuthorGithubLogin" }?.map { it.attr("value") }.orEmpty(),
    )
}

internal fun startsRtl(text: String): Boolean {
    var index = 0
    while (index < text.length) {
        val point = text.codePointAt(index)
        if (Character.isLetter(point)) return point in 0x0590..0x06ff
        index += Character.charCount(point)
    }
    return false
}

internal fun duetLineLayout(song: ParsedSong): List<DuetLineLayout>? {
    if (song.timing != "Word") return null
    val people = song.agents.values.count { it.type == "person" }
    if (people + song.agents.values.count { it.type == "other" } < 2) return null
    var previous = -1
    var opposite = false
    var hasOpposite = false
    val layout = song.lines.mapIndexed { index, line ->
        val type = song.agents[line.agent]?.type
        var lineOpposite = false
        if (line.agent.isNotEmpty() && type != "group" && (previous >= 0 || type == "person" || type == "other")) {
            if (previous < 0) {
                opposite = type == "other"
            } else if (line.agent != song.lines[previous].agent) {
                // The first row after the previous singer is used even when it is neutral.
                if (startsRtl(song.lines[previous].main.text) == startsRtl(song.lines[previous + 1].main.text)) opposite = !opposite
            }
            previous = index
            lineOpposite = opposite
            hasOpposite = hasOpposite || opposite
        }
        DuetLineLayout(startsRtl(line.main.text) != lineOpposite, type != "person" || people > 1)
    }
    return layout.takeIf { hasOpposite }
}
