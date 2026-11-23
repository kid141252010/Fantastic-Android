package io.github.kid141252010.fantastic.data

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import java.util.Locale

internal data class QualityPresentation(
    val badgeLabel: String,
    val title: String,
    val lossless: Boolean,
)

/** Formats only the quality label and codec/format segments actually reported by playback. */
internal fun qualityPresentation(source: Source?, qualityLabel: String?, audioDetails: String?): QualityPresentation? {
    val label = qualityLabel?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val title = if (label == "Hi-Res 无损") "高解析度无损" else label
    val codec = audioDetails?.substringBefore('·')?.trimEnd()
    val lossless = (title == "无损" || title == "高解析度无损") &&
        (codec == "PCM" || codec?.endsWith("（无损）") == true)
    val badgeLabel = when (title) {
        "无损", "高解析度无损" -> if (lossless) title else ""
        "杜比全景声" -> ""
        else -> if (source == Source.QQ || source == Source.Netease) title else ""
    }
    return QualityPresentation(badgeLabel, title, lossless)
}

/** Uses the selected sample format and codec metadata reported by Media3. */
@OptIn(UnstableApi::class)
internal fun nativeAudioPresentation(format: Format): Pair<String?, String?> {
    val reportedCodec = format.codecs?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
    val codec = when (val mime = format.sampleMimeType?.lowercase(Locale.ROOT)) {
        MimeTypes.AUDIO_AAC, "audio/aac" -> when (reportedCodec) {
            "mp4a.40.2", "mp4a.40.02", "mp4a.40.5", "mp4a.40.05", "mp4a.40.29" -> reportedCodec
            else -> MimeTypes.AUDIO_AAC
        }
        MimeTypes.AUDIO_E_AC3 -> if (reportedCodec == MimeTypes.CODEC_E_AC3_JOC) MimeTypes.AUDIO_E_AC3_JOC else mime
        MimeTypes.AUDIO_DTS_EXPRESS -> "dts-express"
        MimeTypes.AUDIO_DTS_X -> "dts:x"
        null, MimeTypes.AUDIO_UNKNOWN, MimeTypes.AUDIO_MP4 -> reportedCodec ?: mime
        else -> mime
    }
    val sampleRate = format.sampleRate
    val label = when (codec) {
        "flac", "x-flac", MimeTypes.AUDIO_FLAC, "audio/x-flac",
        "alac", MimeTypes.AUDIO_ALAC, "audio/x-alac",
        MimeTypes.AUDIO_RAW, "audio/pcm", "audio/x-pcm", "audio/l16", "audio/l24", "l16", "l24" ->
            if (sampleRate > 48_000) "高解析度无损" else "无损"
        "eac3-joc", MimeTypes.CODEC_E_AC3_JOC, MimeTypes.AUDIO_E_AC3_JOC -> "杜比全景声"
        "ec-3", "ec3", "eac3", MimeTypes.AUDIO_E_AC3 -> "E-AC-3"
        "ac-3", "ac3", MimeTypes.AUDIO_AC3 -> "AC-3"
        "ac-4", "ac4", MimeTypes.AUDIO_AC4 -> "AC-4"
        "mp3", MimeTypes.AUDIO_MPEG -> "MP3"
        "aac", "audio/aac", MimeTypes.AUDIO_AAC -> "AAC"
        "mp4a.40.2", "mp4a.40.02" -> "AAC-LC"
        "mp4a.40.5", "mp4a.40.05" -> "HE-AAC"
        "mp4a.40.29" -> "HE-AAC v2"
        "opus", MimeTypes.AUDIO_OPUS -> "Opus"
        "vorbis", MimeTypes.AUDIO_VORBIS -> "Vorbis"
        "dts", MimeTypes.AUDIO_DTS -> "DTS"
        "dts-hd", MimeTypes.AUDIO_DTS_HD -> "DTS-HD"
        "dts-express" -> "DTS Express"
        "dts:x" -> "DTS:X"
        else -> null
    }
    val bitDepth = reportedBitDepth(codec, format.pcmEncoding, format.initializationData)
    val averageBitrate = format.averageBitrate.takeIf { it > 0 }
    val bitrate = averageBitrate ?: format.peakBitrate
    val channels = format.channelCount.takeIf { it > 0 }
    if (codec == null && sampleRate == Format.NO_VALUE && bitDepth == 0 &&
        channels == null && bitrate == Format.NO_VALUE) return null to null
    return label to reportedAudioDetails(
        codec = codec,
        sampleRate = sampleRate,
        bitDepth = bitDepth,
        channels = channels?.toString(),
        bitrate = bitrate.toLong(),
        bitrateLabel = if (averageBitrate != null) "平均码率" else "峰值码率",
    )
}

/** 只格式化调用方读取到的音轨/变体元数据；绝不从所选档位推测缺失规格。 */
internal fun reportedAudioDetails(
    codec: String?,
    sampleRate: Int = 0,
    bitDepth: Int = 0,
    channels: String? = null,
    bitrate: Long = 0L,
    bitrateLabel: String = "码率",
): String = buildList {
    add(when (codec?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)) {
        "alac", "audio/alac", "audio/x-alac" -> "ALAC（无损）"
        "flac", "x-flac", "audio/flac", "audio/x-flac" -> "FLAC（无损）"
        "mp3", "audio/mpeg" -> "MP3（有损）"
        "audio/mpeg-l1" -> "MPEG Layer I（有损）"
        "audio/mpeg-l2" -> "MPEG Layer II（有损）"
        "mp4a.40.2", "mp4a.40.02" -> "AAC-LC（有损）"
        "aac", "audio/aac", "audio/mp4a-latm" -> "AAC（有损）"
        "mp4a.40.5", "mp4a.40.05" -> "HE-AAC（有损）"
        "mp4a.40.29" -> "HE-AAC v2（有损）"
        "eac3-joc", "audio/eac3-joc", "ec+3" -> "E-AC-3 JOC（有损）"
        "ec-3", "ec3", "eac3", "audio/eac3" -> "E-AC-3（有损）"
        "ac-3", "ac3", "audio/ac3" -> "AC-3（有损）"
        "ac-4", "ac4", "audio/ac4" -> "AC-4（有损）"
        "audio/raw", "audio/pcm", "audio/x-pcm", "audio/l16", "audio/l24", "l16", "l24" -> "PCM"
        "opus", "audio/opus" -> "Opus（有损）"
        "vorbis", "audio/vorbis" -> "Vorbis（有损）"
        "audio/3gpp", "audio/amr" -> "AMR-NB（有损）"
        "audio/amr-wb" -> "AMR-WB（有损）"
        "audio/g711-alaw" -> "G.711 A-law（有损）"
        "audio/g711-mlaw" -> "G.711 μ-law（有损）"
        "audio/vnd.dts", "dts" -> "DTS（有损）"
        "audio/vnd.dts.hd", "dts-hd" -> "DTS-HD"
        "dts-express" -> "DTS Express"
        "dts:x" -> "DTS:X"
        else -> codec?.takeIf { it.isNotBlank() }?.take(80) ?: "未知"
    })
    add(if (sampleRate > 0) "${decimalThousands(sampleRate.toLong())} kHz" else "采样率未知")
    add(if (bitDepth > 0) "$bitDepth-bit" else "位深未知")
    add(channels?.takeIf { it.isNotBlank() }?.take(32)?.let { "声道 $it" } ?: "声道未知")
    add(if (bitrate > 0L) "$bitrateLabel ${decimalThousands(bitrate)} kbps" else "${bitrateLabel}未知")
}.joinToString(" · ")

/** Reports bit depth from PCM encoding or the codec's initialization payload. */
internal fun reportedBitDepth(
    codec: String?,
    pcmEncoding: Int,
    initializationData: List<ByteArray>,
): Int {
    val normalizedCodec = codec?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
    return when (normalizedCodec) {
        "audio/raw", "audio/pcm", "audio/x-pcm", "audio/l16", "audio/l24", "l16", "l24" -> when (pcmEncoding) {
            C.ENCODING_PCM_8BIT -> 8
            C.ENCODING_PCM_16BIT -> 16
            C.ENCODING_PCM_24BIT -> 24
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 32
            else -> 0
        }
        "flac", "x-flac", "audio/flac", "audio/x-flac" ->
            initializationData.firstNotNullOfOrNull(::flacStreamInfoBitDepth) ?: 0
        "alac", "audio/alac", "audio/x-alac" ->
            initializationData.firstNotNullOfOrNull(::alacConfigBitDepth) ?: 0
        else -> 0
    }
}

private fun alacConfigBitDepth(data: ByteArray): Int? {
    // Media3's MP4 parser removes the full-box header before exposing ALACSpecificConfig.
    // The 24-byte config may be followed by a 24-byte channel layout.
    if ((data.size != 24 && data.size != 48) || data[4] != 0.toByte() || data[9].toInt() !in 1..8) return null
    return data[5].toInt().takeIf { it in 1..32 }
}

private fun flacStreamInfoBitDepth(data: ByteArray): Int? {
    val hasFlacMarker = data.size >= 4 && data[0] == 'f'.code.toByte() && data[1] == 'L'.code.toByte() &&
        data[2] == 'a'.code.toByte() && data[3] == 'C'.code.toByte()
    val offset = when {
        hasFlacMarker && isFlacStreamInfoBlock(data, 4) -> 8
        hasFlacMarker -> return null
        isFlacStreamInfoBlock(data, 0) -> 4
        data.size >= 34 -> 0 // Bare STREAMINFO payload.
        else -> return null
    }
    if (data.size < offset + 34) return null
    var packed = 0L
    for (index in 10 until 18) packed = (packed shl 8) or (data[offset + index].toLong() and 0xffL)
    val sampleRate = (packed ushr 44) and 0xfffffL
    val channels = ((packed ushr 41) and 0x7L) + 1L
    val bitDepth = (((packed ushr 36) and 0x1fL) + 1L).toInt()
    return bitDepth.takeIf { sampleRate in 1L..655_350L && channels in 1L..8L && it in 4..32 }
}

private fun isFlacStreamInfoBlock(data: ByteArray, headerOffset: Int): Boolean =
    data.size >= headerOffset + 4 &&
        (data[headerOffset].toInt() and 0x7f) == 0 &&
        data[headerOffset + 1] == 0.toByte() &&
        data[headerOffset + 2] == 0.toByte() &&
        data[headerOffset + 3] == 34.toByte()

private fun decimalThousands(value: Long): String {
    val fraction = (value % 1_000L).toString().padStart(3, '0').trimEnd('0')
    return if (fraction.isEmpty()) (value / 1_000L).toString() else "${value / 1_000L}.$fraction"
}
