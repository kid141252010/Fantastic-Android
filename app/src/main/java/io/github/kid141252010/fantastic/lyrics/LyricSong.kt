package io.github.kid141252010.fantastic.lyrics

import kotlinx.serialization.Serializable

internal data class LyricShareLine(val text: String, val timestampMs: Long?)

/** Milliseconds in the audio timeline. Candidate selection and TTML dialect parsing are separate from drawing. */
@Serializable
internal data class LyricSong(
    val timing: String = "None",
    val duration: Long = 0,
    val lines: List<LyricLine> = emptyList(),
    val credits: List<String> = emptyList(),
    val audioOffsets: Map<String, Long> = emptyMap(),
    val translationAutomatic: Boolean = false,
    val pronunciationAutomatic: Boolean = false,
)

@Serializable
internal data class LyricLine(
    val begin: Long,
    val end: Long,
    val main: LyricVoice,
    val background: LyricVoice = LyricVoice(),
    val duetRightAligned: Boolean? = null,
    val narrow: Boolean = false,
    val paragraphBegin: Long? = null,
    val paragraphEnd: Long? = null,
)

@Serializable
internal data class LyricVoice(
    val text: String = "",
    val tokens: List<LyricToken> = emptyList(),
    val translation: String = "",
    val pronunciation: String = "",
)

@Serializable
internal data class LyricToken(
    val begin: Long,
    val end: Long,
    val text: String,
    val spaceAfter: Boolean = false,
    val pronunciation: String = "",
) {
    val isTimed: Boolean get() = begin != 0L || end != 0L
}
