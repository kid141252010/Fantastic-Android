package io.github.kid141252010.fantastic.lyrics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

internal class LyricParser {
    internal suspend fun parse(kind: String, payload: String, language: String = "zh-Hans"): LyricSong? = onParserThread {
        if (kind == "apple") {
            parseNativeTtml(payload, language).takeUnless { it.lines.isEmpty() }?.toLyricSong()
        } else {
            val data = Json.parseToJsonElement(payload)
            check(data != JsonNull) { "歌词 JSON 为空" }
            (data as? JsonObject)?.let { parseCandidates(kind, it, language) }
        }
    }

    internal suspend fun parse(kind: String, payload: JsonObject, language: String = "zh-Hans"): LyricSong? = onParserThread {
        parseCandidates(kind, payload, language)
    }

    private suspend fun parseCandidates(kind: String, data: JsonObject, language: String): LyricSong? =
        selectNativeLyrics(kind, data, language)?.toLyricSong()

    private suspend fun onParserThread(parse: suspend () -> LyricSong?): LyricSong? = withTimeout(15_000) {
        withContext(Dispatchers.Default) { parse() }
    }
}
