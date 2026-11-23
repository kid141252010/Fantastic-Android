package io.github.kid141252010.fantastic.data

internal data class SongCredit(val name: String, val roles: List<String>)
internal data class SongCreditSection(val title: String?, val entries: List<SongCredit>)

internal fun songCreditRole(label: String): String? {
    val role = label.trim()
    return when (role) {
        "演唱者" -> "演唱"
        "词", "詞", "作詞" -> "作词"
        "曲" -> "作曲"
        "編曲" -> "编曲"
        "製作人" -> "制作人"
        "錄音" -> "录音"
        "演唱", "作词", "作曲", "编曲", "制作人", "配唱制作人", "监制", "音乐总监",
        "和声", "和声编写", "和声编曲", "吉他", "贝斯", "鼓", "键盘", "钢琴", "弦乐",
        "录音", "录音师", "录音工程", "录音工程师", "录音助理", "录音室",
        "混音", "混音师", "混音工程", "混音工程师", "混音室",
        "母带", "母带工程", "母带工程师", "音频编辑", "人声编辑" -> role
        else -> null
    }
}

private val lrcCreditTimestamps = Regex("""^(?:\[[0-9]+:[0-5][0-9](?:\.[0-9]{2,3})?\])++""")
private val creditSeparators = charArrayOf(':', '：')

internal fun lrcSongCredits(text: String): List<SongCredit> = buildSet {
    for (line in text.removePrefix("\uFEFF").lineSequence()) {
        val timestamps = lrcCreditTimestamps.find(line) ?: continue
        val start = timestamps.range.last + 1
        val separator = line.indexOfAny(creditSeparators, startIndex = start)
        if (separator < 0) continue
        val role = lrcCreditRole(line.substring(start, separator).trim()) ?: continue
        val name = line.substring(separator + 1).trim()
        if (name.isNotEmpty()) add(SongCredit(name, listOf(role)))
    }
}.toList()

private fun lrcCreditRole(label: String): String? {
    songCreditRole(label)?.let { return it }
    val space = label.indexOfFirst(Char::isWhitespace)
    if (space < 0) return null
    var suffix = space + 1
    while (suffix < label.length && label[suffix].isWhitespace()) suffix++
    val initial = label.getOrNull(suffix) ?: return null
    if (initial !in 'A'..'Z' && initial !in 'a'..'z') return null
    return songCreditRole(label.substring(0, space))
}
