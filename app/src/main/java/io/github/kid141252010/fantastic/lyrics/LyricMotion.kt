package io.github.kid141252010.fantastic.lyrics

import android.view.animation.PathInterpolator
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Apple Music Android 7.0.0 (1614): C4022z, Q2.e, p236lc.y and X9.a. */
internal object LyricMotion {
    val line = PathInterpolator(.4f, .1f, 0f, 1f)
    val emphasis = PathInterpolator(.25f, .1f, .25f, 1f)
    val retirement = PathInterpolator(.39f, .575f, .565f, 1f)
    val dotProgress = PathInterpolator(0f, .25f, 1f, .58f)
    val dotExit = PathInterpolator(.25f, 0f, 1f, .2f)
    const val wordLeadMs = 100L
    const val rushMs = 500L
    const val featherDp = 30f
    const val glowDp = 5f
    const val liftDp = -2f
    const val foreground = 240
    const val background = 89
    const val waiting = 46
    const val legacyMainSp = 20f // Lyrics_body / Title3Emphasized
    const val legacyStaticLineHeightSp = 26f // lyrics_line_static
    const val karaokeMainSp = 34f // lyrics_line_text_size
    const val backgroundSp = 22f // lyrics_line_secondary_text_size
    const val karaokePronunciationSp = 20f // lyrics_word_pronunciation / Title3Emphasized
    const val backgroundPronunciationSp = 14f // lyrics_word_pronunciation_bg / SubheadBoldShort
    const val pronunciationRowPaddingDp = 8f // lyrics_pronunciation_line_break_padding_vert
    const val secondaryTextAlpha = 115 // Player credits and attribution, not karaoke translation masks.
    const val legacyTranslationColor = 0x99ebebf5.toInt() // lyrics_body / secondary_label_color_dark
    const val legacyTranslationAlpha = legacyTranslationColor ushr 24
    const val mainAnnotationTrackingEm = -46f / 2048f // sf_font_tracking_font_units at 20sp
    const val backgroundAnnotationTrackingEm = -22f / 2048f // 14sp
    const val legacyTranslationTrackingEm = -32f / 2048f // 15sp
    const val karaokeTranslationSp = 20f // lyrics_translation_line_karaoke -> Title3Emphasized
    const val backgroundTranslationSp = 14f // lyrics_bg_translation_line_karaoke -> SubheadBoldShort
    const val legacyTranslationSp = 15f // lyrics_body -> SubheadEmphasized
    const val karaokeTranslationMarginDp = 10f // smaller_margin_10
    const val backgroundTranslationMarginDp = 2f // smaller_margin_2
    const val legacyTranslationMarginDp = 4f
    const val scaleEnterDelayMs = 150L // X.O, BR.friendsSetting
    const val scaleEnterMs = 500L
    const val scaleExitDelayMs = 50L // U.W scale reset
    const val scaleExitMs = 350L
    const val lineAlphaEnterDelayMs = 250L // U0.V
    const val lineAlphaExitDelayMs = 250L // U0.V, BR.isSeeAllInvisible
    const val lineAlphaEnterMs = 250L
    const val lineAlphaExitMs = 350L // U0.V, BR.queueItem
    const val backingEnterDelayFraction = .85f // C4022z.j0
    const val backingEnterMs = 500L // scale + alpha
    const val backingExitScaleMs = 500L
    const val backingExitAlphaMs = 250L
    const val backingScale = .9f
    const val interludeMinimum = 7000L
    const val normalScale = .98f
    private const val damping = .93
    private const val omega = 5.0 // sqrt(25), unit mass
    private val frequency = omega * sqrt(1.0 - damping * damping)

    fun fraction(elapsed: Long, duration: Long): Float =
        if (duration <= 0) { if (elapsed >= 0) 1f else 0f } else (elapsed.toFloat() / duration).coerceIn(0f, 1f)

    fun lineDuration(gap: Long): Long = (480 + (gap.coerceIn(200, 750) - 200) * (270f / 550)).roundToInt().toLong()

    fun stagger(distance: Int): Long {
        val n = distance.coerceIn(0, 4)
        return (6.25f * (5 * n - (n + 1) * n / 2f)).roundToInt().toLong()
    }

    internal inline fun sampleLiftSpring(
        elapsed: Long,
        withVelocity: Boolean,
        consume: (remaining: Double, velocityPerSecond: Double) -> Unit,
    ) {
        if (elapsed <= 0L) {
            consume(1.0, 0.0)
            return
        }
        val time = elapsed / 1000.0
        val a = exp(-damping * omega * time)
        val b = frequency * time
        val s = sin(b)
        val remaining = a * (cos(b) + damping * omega / frequency * s)
        val velocity = if (withVelocity) -a * omega * omega / frequency * s else 0.0
        consume(remaining, velocity)
    }

    fun lift(elapsed: Long): Float {
        if (elapsed <= 0) return 0f
        var result = 0f
        sampleLiftSpring(elapsed, withVelocity = false) { remaining, _ ->
            result = liftDp * (1.0 - remaining).toFloat()
        }
        return result
    }

    fun attackDelay(duration: Long, index: Int, count: Int): Long =
        (minOf(.4f * duration / count, 400f) * index).roundToInt().toLong()

    /** Release starts before a long attack necessarily completes. Its source is the value at release, not the peak. */
    fun envelope(elapsed: Long, duration: Long, index: Int, count: Int): Float {
        val attack = minOf(duration, 3000)
        val offset = attackDelay(duration, index, count)
        val t = elapsed - offset
        if (t <= 0 || attack <= 0) return 0f
        val release = (index * minOf(.4f * duration / count, 400f) + 2f * duration / count).roundToInt() - offset
        val start = emphasis.getInterpolation(fraction(minOf(t, release), attack))
        return start * (1f - emphasis.getInterpolation(fraction(t - release, attack)))
    }

    /** Width to add to the main sweep, before the end-of-wrapped-band rush. C4022z.d0. */
    fun featherExtension(cjk: Boolean, terminal: Boolean, grouped: Boolean, length: Int, total: Int,
        pairedExpansion: Boolean, flexParent: Boolean): Float = when {
        cjk && !pairedExpansion -> 0f
        flexParent && grouped && terminal -> 1f
        grouped -> if (length <= 2) .25f else .12f
        terminal -> 1f
        total <= 2 -> .5f
        else -> .25f
    }

    // X9.a: every sample of the original pulse, not a fitted replacement curve.
    private val pulse = floatArrayOf(
        0f, .0005f, .002f, .004f, .007f, .01f, .0138f, .02f, .026f, .032f, .039f,
        .048f, .058f, .071f, .086f, .102f, .119f, .135f, .153f, .172f, .194f,
        .215f, .234f, .257f, .279f, .299f, .32f, .34f, .358f, .373f, .389f,
        .404f, .418f, .43f, .44f, .45f, .458f, .466f, .473f, .479f, .483f,
        .4875f, .491f, .494f, .496f, .498f, .499f, .4995f, .5f, .5005f, .502f,
        .504f, .507f, .51f, .5138f, .52f, .526f, .532f, .539f, .548f, .558f,
        .571f, .586f, .602f, .619f, .635f, .653f, .672f, .694f, .715f, .734f,
        .757f, .779f, .799f, .82f, .84f, .858f, .873f, .889f, .904f, .918f,
        .93f, .94f, .95f, .958f, .966f, .973f, .979f, .983f, .9875f, .991f,
        .994f, .996f, .998f, .999f, .9995f, 1f,
    )

    fun dotScale(elapsed: Long, duration: Long): Float {
        val cycles = duration / 4000
        if (cycles == 0L) return 1f
        val period = duration / cycles
        val index = (elapsed.coerceAtLeast(0) % period).toFloat() / period * pulse.lastIndex
        val lower = index.toInt().coerceAtMost(pulse.lastIndex - 1)
        val sample = pulse[lower] + (pulse[lower + 1] - pulse[lower]) * (index - lower)
        return 1f + .4f * minOf(sample, 1f - sample)
    }
}
