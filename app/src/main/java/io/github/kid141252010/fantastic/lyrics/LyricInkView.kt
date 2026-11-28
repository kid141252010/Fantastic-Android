package io.github.kid141252010.fantastic.lyrics

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableWrapper
import android.icu.text.BreakIterator
import android.os.Build
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import io.github.kid141252010.fantastic.ui.theme.LocalAmFonts
import io.github.kid141252010.fantastic.R
import java.text.Bidi
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Paints one actual wrapped row, then masks its glyphs AND glow together as in the APK. */
internal class LyricInkView(context: Context) : View(context) {
    private class Group(val begin: Long, val duration: Long, val count: Int) {
        val peak = .14f * (duration / 1000f - 1f).coerceIn(0f, 1f)
        val glyphs = ArrayList<Glyph>()
        var rtl = false
    }

    private class Glyph(
        val start: Int, val end: Int, val x: Float, val width: Float, val inkWidth: Int,
        val rtl: Boolean, val token: LyricToken, val delay: Long, val group: Group?, val index: Int,
    ) {
        var envelope = 0f
        var halfGrowth = 0f
        var shift = 0f
        var liftSamplePx = 0f
    }
    private class Sweep(
        val token: LyricToken, val start: Float, val end: Float, val extension: Float,
        val rush: Boolean, val order: Int, val nextBegin: Long, val clearsPreviousRows: Boolean,
        val geometryOffset: Float,
    ) {
        var from = start
        var target = end
    }
    private class SweepRow(val x: Float, val width: Float, val rtl: Boolean, val pronunciation: Boolean = false) {
        val sweeps = ArrayList<Sweep>()
        var frontier = if (rtl) width else 0f
        var completedAt = Long.MAX_VALUE
    }
    private class Band(val top: Float, val bottom: Float, val baseline: Float, val sweepRow: SweepRow) {
        val glyphs = ArrayList<Glyph>()
    }

    private class Block(
        val text: String, val paint: TextPaint, val layout: StaticLayout, val x: Float, val y: Float,
        val scaleX: Float = 1f, rightAligned: Boolean? = null,
    ) {
        val bands = ArrayList<Band>()
        var backing = false
        var translation = false
        var pronunciation = false
        var writerCredit = false
        var attribution = false
        // Non-lyric blocks keep their center; lyrics pin the aligned bottom corner.
        val pivotX = x + when (rightAligned) {
            true -> layout.width * scaleX
            false -> 0f
            null -> layout.width * scaleX / 2f
        }
        val pivotY = y + if (rightAligned == null) layout.height / 2f else layout.height.toFloat()
    }

    private class SpanColumn(
        val token: LyricToken, val main: StaticLayout, val reading: StaticLayout?,
        val width: Float, val gap: Float, val scale: Float, val offset: Int, val tokenIndex: Int,
        val grouped: Boolean, val total: Int, var wrapped: Boolean = false,
    )

    private val density get() = resources.displayMetrics.density
    private fun dp(value: Float) = value * density
    private fun sp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val mask = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private val solid = Paint(Paint.ANTI_ALIAS_FLAG)
    private val matrix = Matrix()
    private val bounds = Rect()
    private val glyphPath = Path()
    private val glyphRowClip = Path()
    private val glyphBounds = RectF()
    private val groups = ArrayList<Group>()
    private val sweepRows = ArrayList<SweepRow>()
    private val blocks = ArrayList<Block>(6)
    private val attributionBadge = context.getDrawable(R.drawable.fantastic_lyrics_sparkles)!!.mutate().apply {
        setTint(Color.argb(LyricMotion.secondaryTextAlpha, 255, 255, 255))
    }
    private var builtWidth = -1
    private var hasBackground = false
    private var backgroundVisible = false
    private var backingTargetVisible = false
    private var backingHeightAt = 0L
    private var backingHeightDuration = 0L
    private var backingMotionActive = false
    private var backingMotionEnd = 0L
    private var backingAlphaFrom = 0f
    private var backingAlphaTarget = 0f
    private var backingAlphaAt = 0L
    private var backingAlphaDuration = 0L
    private var backingAlphaEntering = false
    private var backingScaleFrom = LyricMotion.backingScale
    private var backingScaleTarget = LyricMotion.backingScale
    private var backingScaleAt = 0L
    private var backingScaleDuration = 0L
    private var foregroundPivotX = 0f
    private var foregroundPivotY = 0f
    private var backingPivotX = 0f
    private var backingPivotY = 0f
    private val rowPressInsetPx = dp(KARAOKE_ROW_MARGIN_DP).roundToInt()
    private var rowPressBackground: Drawable? = null
    private var touchActivationAllowed = true
    internal val rowActivationAllowed: Boolean get() = touchActivationAllowed

    var layoutMotionDuration = 750L
    internal var lineMoveNotBefore = 0L
    private var collapsedContentHeight = 0
    private var expandedContentHeight = 0
    private var line: LyricLine? = null
    private var credits = ""
    private var creditsAttribution = ""
    private var interlude = false
    private var isWord = false
    private var isStatic = false
    private var showTranslation = true
    private var showPronunciation = false
    private var lyricFontWeight = 0
    private var scaleInitialized = false
    private var scaleFrom = LyricMotion.normalScale
    private var scaleTarget = LyricMotion.normalScale
    private var scaleAt = 0L
    private var scaleDuration = 0L
    private var alphaFrom = 0f
    private var alphaTarget = 0f
    private var alphaAt = 0L
    private var alphaDuration = 0L
    private var wordLiftTime = Long.MIN_VALUE
    private var wordLiftReturnAt = Long.MIN_VALUE

    var position = 0L
    private var emphasized = false
    var reduceMotion = false
    var interludeStart = 0L
    var interludeEnd = 0L

    var onContentLayoutChanged: () -> Unit = {}
    private fun resetWordLift() {
        wordLiftTime = Long.MIN_VALUE
        wordLiftReturnAt = Long.MIN_VALUE
    }

    private fun wordLiftAt(glyph: Glyph, time: Long): Float {
        if (time == Long.MIN_VALUE || glyph.delay == Long.MAX_VALUE) return 0f
        return dp(LyricMotion.lift(time - ((glyph.group?.begin ?: glyph.token.begin) + glyph.delay)))
    }

    fun bind(
        line: LyricLine?,
        timing: String,
        credits: String,
        attribution: String,
        interlude: Boolean,
        translation: Boolean,
        pronunciation: Boolean,
        fontWeight: Int,
    ) {
        val sameLine = this.line === line && this.credits == credits &&
            this.creditsAttribution == attribution && this.interlude == interlude
        val wordModeChanged = isWord != (timing == "Word")
        val layoutChanged = !sameLine || wordModeChanged || isStatic != (timing == "None") ||
            showTranslation != translation || showPronunciation != pronunciation || lyricFontWeight != fontWeight
        if (!sameLine || wordModeChanged) resetWordLift()
        if (layoutChanged) builtWidth = -1
        this.line = line
        this.credits = credits
        this.creditsAttribution = attribution
        this.interlude = interlude
        isWord = timing == "Word"
        isStatic = timing == "None"
        showTranslation = translation
        showPronunciation = pronunciation
        lyricFontWeight = fontWeight
        hasBackground = line?.background?.let { it.text.isNotBlank() || it.tokens.any { token -> token.text.isNotBlank() } } == true
        if (!sameLine) {
            lineMoveNotBefore = 0L
            emphasized = false
            scaleInitialized = false
            backgroundVisible = hasBackground && isStatic
            backingTargetVisible = backgroundVisible
            backingHeightDuration = 0L
            backingMotionActive = false
            backingAlphaFrom = if (backgroundVisible) 1f else 0f
            backingAlphaTarget = backingAlphaFrom
            backingScaleFrom = if (backgroundVisible) 1f else LyricMotion.backingScale
            backingScaleTarget = backingScaleFrom
            backingAlphaDuration = 0L
            backingScaleDuration = 0L
            backingMotionEnd = 0L
            alphaDuration = 0L
        }
        if (layoutChanged) {
            val nextBackground = if (line != null && !isStatic && !interlude) pressBackground() else null
            if (background !== nextBackground) background = nextBackground
            requestLayout()
        }
        invalidate()
    }


    private fun updateContentDescription() {
        val value = line
        contentDescription = when {
            interlude -> "间奏"
            value != null -> listOf(value.main.text, visiblePronunciation(value.main),
                value.main.translation.takeIf { showTranslation }, value.background.text.takeIf { backgroundVisible },
                value.background.takeIf { backgroundVisible }?.let(::visiblePronunciation))
                .filterNot { it.isNullOrBlank() }.joinToString("，")
            else -> listOf(credits, creditsAttribution).filter(String::isNotBlank).joinToString("，")
        }
    }

    private fun visiblePronunciation(voice: LyricVoice): String? = if (showPronunciation) {
        voice.pronunciation.ifBlank { voice.tokens.map { it.pronunciation.trim() }.filter(String::isNotEmpty).joinToString(" ") }
    } else null

    private fun pressInsetFor(width: Int): Int = if (width > 2 * rowPressInsetPx) rowPressInsetPx else 0

    private fun pressBackground(): Drawable {
        rowPressBackground?.let { return it }
        return object : DrawableWrapper(checkNotNull(context.getDrawable(R.drawable.bg_lyrics_row_press))) {
            override fun onBoundsChange(bounds: Rect) {
                val inset = pressInsetFor(bounds.width())
                drawable?.setBounds(bounds.left + inset, bounds.top, bounds.right - inset, bounds.bottom)
            }
        }.also { rowPressBackground = it }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val inset = pressInsetFor(width)
            touchActivationAllowed = event.x >= inset && event.x < width - inset
        }
        if (event.actionMasked == MotionEvent.ACTION_UP &&
            event.eventTime - event.downTime >= ViewConfiguration.getLongPressTimeout()
        ) {
            // Cancel the deferred click when the user held the row.
            event.action = MotionEvent.ACTION_CANCEL
            return try {
                super.dispatchTouchEvent(event)
            } finally {
                event.action = MotionEvent.ACTION_UP
                touchActivationAllowed = true
            }
        }
        val handled = super.dispatchTouchEvent(event)
        if (!touchActivationAllowed) {
            // Cancel the scrolling container's deferred tap before restoring non-touch activation.
            cancelLongPress()
            isPressed = false
        }
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            touchActivationAllowed = true
        }
        return handled
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        if (width > 0 && builtWidth != width) build(width)
        val height = if (backingHeightDuration > 0L) {
            val fraction = LyricMotion.fraction(SystemClock.uptimeMillis() - backingHeightAt, backingHeightDuration)
            if (fraction == 1f) {
                backingHeightDuration = 0L
                expandedContentHeight
            } else {
                val progress = LyricMotion.line.getInterpolation(fraction)
                collapsedContentHeight + ((expandedContentHeight - collapsedContentHeight) * progress).roundToInt()
            }
        } else expandedContentHeight
        setMeasuredDimension(width, resolveSize(height, heightMeasureSpec))
    }

    private fun maskPalette(backing: Boolean, rtl: Boolean): IntArray {
        val active = if (backing) 0x59FFFFFF.toInt() else 0xF0FFFFFF.toInt()
        val resting = 0x2EFFFFFF.toInt()
        return if (rtl) intArrayOf(resting, resting, active, active)
        else intArrayOf(active, active, resting, resting)
    }

    private fun maskStops(frontier: Float, width: Float, rtl: Boolean, feather: Float): FloatArray {
        val safeWidth = width.coerceAtLeast(1f)
        val p = frontier / safeWidth
        val f = (feather / safeWidth).coerceAtLeast(0f)
        val leading = if (!rtl) (p - f).coerceIn(0f, 1f) else p.coerceIn(0f, 1f)
        val trailing = if (!rtl) p.coerceIn(0f, 1f) else (p + f).coerceIn(0f, 1f)
        return floatArrayOf(0f, leading, trailing, 1f)
    }

    private fun releasePalette(backing: Boolean, rtl: Boolean): IntArray {
        val active = if (backing) LyricMotion.background else LyricMotion.foreground
        val releaseAlpha = ((active - LyricMotion.waiting) * 255f /
            (255 - LyricMotion.waiting)).roundToInt().coerceIn(0, 255)
        val release = Color.argb(releaseAlpha, 255, 255, 255)
        return if (rtl) intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, release, release)
        else intArrayOf(release, release, Color.TRANSPARENT, Color.TRANSPARENT)
    }

    fun setLinePresentation(emphasized: Boolean, snap: Boolean, now: Long, deferRetirement: Boolean) {
        if (deferRetirement && this.emphasized && !emphasized && !snap && !reduceMotion) return
        val presentationChanged = !scaleInitialized || emphasized != this.emphasized ||
            ((snap || reduceMotion) && (scaleDuration != 0L || alphaDuration != 0L))
        if (!scaleInitialized) {
            scaleFrom = LyricMotion.normalScale
            scaleTarget = scaleFrom
            scaleDuration = 0L
            alphaFrom = 0f
            alphaTarget = 0f
            alphaDuration = 0L
        }
        val target = if (emphasized) 1f else LyricMotion.normalScale
        if (snap || reduceMotion) {
            resetWordLift()
            scaleFrom = target
            scaleTarget = target
            scaleDuration = 0L
            alphaFrom = if (emphasized) 1f else 0f
            alphaTarget = alphaFrom
            alphaDuration = 0L
        } else if (emphasized != this.emphasized) {
            if (emphasized) resetWordLift()
            else if (wordLiftTime != Long.MIN_VALUE) wordLiftReturnAt = now
            scaleFrom = if (emphasized) LyricMotion.normalScale else 1f
            scaleTarget = target
            scaleAt = now + if (emphasized) LyricMotion.scaleEnterDelayMs else LyricMotion.scaleExitDelayMs
            scaleDuration = if (emphasized) LyricMotion.scaleEnterMs else LyricMotion.scaleExitMs
            alphaFrom = if (emphasized) 0f else 1f
            alphaTarget = if (emphasized) 1f else 0f
            alphaAt = now + if (emphasized) LyricMotion.lineAlphaEnterDelayMs else LyricMotion.lineAlphaExitDelayMs
            alphaDuration = if (emphasized) LyricMotion.lineAlphaEnterMs else LyricMotion.lineAlphaExitMs
        }
        scaleInitialized = true
        this.emphasized = emphasized
        // Start backing expansion on visual activation; the audio clock still owns the word sweep.
        setBackingVisibility(hasBackground && (isStatic || emphasized), isStatic || snap || reduceMotion, now)
        if (presentationChanged) invalidate()
    }

    private fun setBackingVisibility(show: Boolean, snap: Boolean, now: Long) {
        if (show == backingTargetVisible && (show == backgroundVisible || backingMotionActive) &&
            (!snap || (!backingMotionActive && backingHeightDuration == 0L))) return
        backingTargetVisible = show
        if (snap) {
            backingHeightDuration = 0L
            backingMotionActive = false
            backgroundVisible = show
            backingAlphaFrom = if (show) 1f else 0f
            backingAlphaTarget = backingAlphaFrom
            backingAlphaDuration = 0L
            backingScaleFrom = if (show) 1f else LyricMotion.backingScale
            backingScaleTarget = backingScaleFrom
            backingScaleDuration = 0L
            builtWidth = -1
            updateContentDescription()
            requestLayout()
            invalidate()
            return
        }
        backingMotionActive = true
        if (show) {
            val opening = !backgroundVisible
            backingAlphaFrom = if (opening) 0f else currentBackingAlpha(now)
            backingAlphaTarget = 1f
            backingScaleFrom = if (opening) LyricMotion.backingScale else currentBackingScale(now)
            backingScaleTarget = 1f
            val delay = if (opening) {
                backingHeightAt = now
                backingHeightDuration = layoutMotionDuration
                (backingHeightDuration * LyricMotion.backingEnterDelayFraction).roundToInt().toLong()
            } else 0L
            backingAlphaAt = now + delay
            backingScaleAt = backingAlphaAt
            backingAlphaDuration = LyricMotion.backingEnterMs
            backingScaleDuration = LyricMotion.backingEnterMs
            backingAlphaEntering = true
            backingMotionEnd = backingAlphaAt + backingAlphaDuration
            if (opening) {
                backgroundVisible = true
                builtWidth = -1
                updateContentDescription()
                requestLayout()
            }
        } else {
            backingAlphaFrom = currentBackingAlpha(now)
            backingAlphaTarget = 0f
            backingAlphaAt = now
            backingAlphaDuration = LyricMotion.backingExitAlphaMs
            backingAlphaEntering = false
            backingScaleFrom = currentBackingScale(now)
            backingScaleTarget = LyricMotion.backingScale
            backingScaleAt = now
            backingScaleDuration = LyricMotion.backingExitScaleMs
            backingMotionEnd = now + maxOf(backingAlphaDuration, backingScaleDuration)
        }
        invalidate()
        postInvalidateOnAnimation()
    }

    private fun currentBackingAlpha(now: Long): Float {
        if (backingAlphaDuration == 0L) return backingAlphaTarget
        val fraction = LyricMotion.fraction(now - backingAlphaAt, backingAlphaDuration)
        val eased = if (backingAlphaEntering) LyricMotion.line.getInterpolation(fraction)
            else LyricMotion.retirement.getInterpolation(fraction)
        return backingAlphaFrom + (backingAlphaTarget - backingAlphaFrom) * eased
    }

    private fun currentBackingScale(now: Long): Float = if (backingScaleDuration == 0L) backingScaleTarget else
        backingScaleFrom + (backingScaleTarget - backingScaleFrom) * LyricMotion.line.getInterpolation(
            LyricMotion.fraction(now - backingScaleAt, backingScaleDuration))

    private fun updateBackingMotion(now: Long) {
        if (!backingMotionActive || now < backingMotionEnd) return
        backingMotionActive = false
        backingAlphaFrom = if (backingTargetVisible) 1f else 0f
        backingAlphaTarget = backingAlphaFrom
        backingAlphaDuration = 0L
        backingScaleFrom = if (backingTargetVisible) 1f else LyricMotion.backingScale
        backingScaleTarget = backingScaleFrom
        backingScaleDuration = 0L
        if (!backingTargetVisible && backgroundVisible) {
            backingHeightDuration = 0L
            backgroundVisible = false
            builtWidth = -1
            updateContentDescription()
            onContentLayoutChanged()
            requestLayout()
        }
    }

    private fun currentScale(now: Long): Float {
        if (scaleDuration == 0L) return scaleTarget
        return scaleFrom + (scaleTarget - scaleFrom) * LyricMotion.line.getInterpolation(
            LyricMotion.fraction(now - scaleAt, scaleDuration))
    }
    internal val currentLineScale: Float get() = currentScale(SystemClock.uptimeMillis())

    private fun currentAlpha(now: Long): Float = if (alphaDuration == 0L) alphaTarget else
        alphaFrom + (alphaTarget - alphaFrom) * LyricMotion.retirement.getInterpolation(
            LyricMotion.fraction(now - alphaAt, alphaDuration))

    private fun lyricPaint(size: Float, weight: Int, customize: Boolean = true): TextPaint {
        val custom = if (customize) lyricFontWeight else 0
        val actualWeight = if (custom == 0) weight else custom
        return TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            color = Color.WHITE
            textSize = sp(size)
            typeface = LocalAmFonts.typeface(context, size, actualWeight, exactWeight = custom != 0)
                ?: if (Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.DEFAULT, actualWeight, false)
            else Typeface.create(if (actualWeight in 500..699) "sans-serif-medium" else "sans-serif",
                if (actualWeight >= 700) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    private fun textLayout(
        text: CharSequence,
        paint: TextPaint,
        width: Int,
        rightAligned: Boolean? = null,
        staticLineHeight: Boolean = false,
    ): StaticLayout {
        val direction = TextDirectionHeuristics.ANYRTL_LTR
        val opposite = rightAligned != null && direction.isRtl(text, 0, text.length) != rightAligned
        val builder = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(if (opposite) Layout.Alignment.ALIGN_OPPOSITE else Layout.Alignment.ALIGN_NORMAL)
            .setTextDirection(direction)
            .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .setIncludePad(false)
        if (staticLineHeight && Build.VERSION.SDK_INT >= 28) {
            val metrics = paint.fontMetricsInt
            val naturalHeight = metrics.descent - metrics.ascent
            builder.setLineSpacing((sp(LEGACY_STATIC_LINE_HEIGHT_SP) - naturalHeight).coerceAtLeast(0f), 1f)
        }
        return builder.build()
    }

    private fun isSpace(char: Char) = Character.isWhitespace(char) || Character.isSpaceChar(char)

    private fun nextWordBegins(tokens: List<LyricToken>): LongArray {
        val result = LongArray(tokens.size)
        var next = Long.MAX_VALUE
        for (i in tokens.indices.reversed()) {
            result[i] = next
            if (tokens[i].isTimed && tokens[i].text.isNotBlank()) next = tokens[i].begin
        }
        return result
    }

    private fun wordEnd(tokens: List<LyricToken>, first: Int): Int {
        var last = first
        while (last + 1 < tokens.size && !tokens[last].spaceAfter &&
            tokens[last].text.lastOrNull()?.let(::isSpace) == false &&
            tokens[last + 1].text.firstOrNull()?.let(::isSpace) == false &&
            !containsCjk(tokens[last].text, includeThai = true)) last++
        return last
    }

    private fun containsCjk(text: String, includeThai: Boolean = false): Boolean = text.any {
        when (Character.UnicodeBlock.of(it)) {
            Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS,
            Character.UnicodeBlock.HIRAGANA,
            Character.UnicodeBlock.KATAKANA -> true
            Character.UnicodeBlock.THAI -> includeThai
            else -> false
        }
    }

    private fun wordGroups(tokens: List<LyricToken>, backing: Boolean): Array<Group?> {
        val result = arrayOfNulls<Group>(tokens.size)
        var index = 0
        while (index < tokens.size) {
            val first = index
            val last = wordEnd(tokens, first)
            index = last + 1
            val text = buildString { for (i in first..last) append(tokens[i].text.trim()) }
            val duration = (first..last).sumOf { (tokens[it].end - tokens[it].begin).coerceAtLeast(0) }
            val count = text.codePointCount(0, text.length)
            if (!backing && duration >= 1000 && count in 1..7 &&
                !SplitCharacters.containsMatchIn(text) && !JoinedScripts.containsMatchIn(text)) {
                val group = Group(tokens[first].begin, duration, count).apply {
                    rtl = Bidi(text, Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT).baseLevel == 1
                }
                groups += group
                for (i in first..last) result[i] = group
            }
        }
        return result
    }

    private fun addTimedVoice(
        tokens: List<LyricToken>, size: Float, backing: Boolean, duetRightAligned: Boolean?,
        x: Float, top: Float, available: Int, paired: Boolean,
    ): Float {
        val mainPaint = lyricPaint(size, 700)
        val readingPaint = lyricPaint(if (backing) LyricMotion.backgroundPronunciationSp else LyricMotion.karaokePronunciationSp, 700).apply {
            letterSpacing = if (backing) LyricMotion.backgroundAnnotationTrackingEm else LyricMotion.mainAnnotationTrackingEm
        }
        val rawSource = tokens.asText()
        val spacedSource = LyricTextAutospace.apply(rawSource)
        val source = spacedSource.text
        val rawReading = if (paired) buildString { for (token in tokens) append(token.pronunciation) } else ""
        val spacedReading = if (paired) LyricTextAutospace.apply(rawReading) else null
        val columns = ArrayList<SpanColumn>(tokens.size)
        val emphasis = wordGroups(tokens, backing)
        val nextBegins = nextWordBegins(tokens)
        val groupIndices = IntArray(tokens.size)
        for (i in 1 until tokens.size) if (emphasis[i] != null && emphasis[i] === emphasis[i - 1]) {
            groupIndices[i] = groupIndices[i - 1] + tokens[i - 1].text.codePointCount(0, tokens[i - 1].text.length)
        }
        var sourceOffset = 0
        var readingOffset = 0
        var wordFirst = 0
        var wordLast = -1
        var wordTotal = 0
        for ((tokenIndex, token) in tokens.withIndex()) {
            if (tokenIndex > wordLast) {
                wordFirst = tokenIndex
                wordLast = wordEnd(tokens, wordFirst)
                wordTotal = (wordFirst..wordLast).sumOf { tokens[it].text.trim().length }
            }
            if (token.text.isBlank()) {
                sourceOffset += token.text.length + if (token.spaceAfter) 1 else 0
                readingOffset += token.pronunciation.length
                continue
            }
            val leading = token.text.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
            val offset = spacedSource.displayOffset(sourceOffset + leading)
            var nextIndex = tokenIndex + 1
            var nextSourceOffset = sourceOffset + token.text.length + if (token.spaceAfter) 1 else 0
            var nextReadingOffset = readingOffset + token.pronunciation.length
            while (nextIndex < tokens.size && tokens[nextIndex].text.isBlank()) {
                nextSourceOffset += tokens[nextIndex].text.length + if (tokens[nextIndex].spaceAfter) 1 else 0
                nextReadingOffset += tokens[nextIndex].pronunciation.length
                nextIndex++
            }
            val next = tokens.getOrNull(nextIndex)
            val nextLeading = next?.text?.indexOfFirst { !it.isWhitespace() }?.coerceAtLeast(0) ?: 0
            val nextReadingLeading = next?.pronunciation?.indexOfFirst { !it.isWhitespace() }?.coerceAtLeast(0) ?: 0
            val mainAutoGap = if (next != null && !token.spaceAfter) {
                if (spacedSource.hasGapBefore(nextSourceOffset + nextLeading)) mainPaint.textSize * .125f else 0f
            } else 0f
            val readingAutoGap = if (next != null && spacedReading?.hasGapBefore(nextReadingOffset) == true)
                readingPaint.textSize * .125f else 0f
            val gapEnd = nextSourceOffset + if (duetRightAligned == true) 0 else nextLeading
            val readingGapEnd = nextReadingOffset + if (duetRightAligned == true) 0 else nextReadingLeading
            // c0/b0 put real whitespace INSIDE the measured word View, including a wrap boundary.
            val mainStart = when {
                columns.isEmpty() -> 0
                duetRightAligned == true -> sourceOffset
                else -> sourceOffset + leading
            }
            val mainText = rawSource.substring(mainStart, gapEnd)
            val mainSpaced = LyricTextAutospace.apply(mainText)
            val mainLayoutText = mainSpaced.layoutText()
            val naturalMainWidth = ceil(Layout.getDesiredWidth(mainLayoutText, mainPaint)).toInt().coerceAtLeast(1)
            val wrapMain = containsCjk(token.text) && naturalMainWidth > available
            val mainLayout = textLayout(mainLayoutText, mainPaint, if (wrapMain) available else naturalMainWidth)
            val readingLeading = token.pronunciation.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
            val readingStart = when {
                columns.isEmpty() -> 0
                duetRightAligned == true -> readingOffset
                else -> readingOffset + readingLeading
            }
            val readingText = if (paired) rawReading.substring(readingStart, readingGapEnd) else ""
            val readingLayoutText = LyricTextAutospace.apply(readingText).layoutText()
            val naturalReadingWidth = if (readingText.isBlank()) 1 else
                ceil(Layout.getDesiredWidth(readingLayoutText, readingPaint)).toInt().coerceAtLeast(1)
            val wrapReading = containsCjk(readingText) && naturalReadingWidth > available
            val readingLayout = if (readingText.isBlank()) null else textLayout(readingLayoutText, readingPaint,
                if (wrapReading) available else naturalReadingWidth)
            val width = maxOf(mainLayout.width, readingLayout?.width ?: 0).toFloat()
            val gap = maxOf(mainAutoGap, if (paired) readingAutoGap else 0f)
            columns += SpanColumn(token, mainLayout, readingLayout, minOf(width, available.toFloat()), gap,
                (available / width).coerceAtMost(1f), offset, tokenIndex, tokenIndex != wordLast, wordTotal)
            sourceOffset += token.text.length + if (token.spaceAfter) 1 else 0
            readingOffset += token.pronunciation.length
        }
        if (columns.isEmpty()) return top
        val bidi = Bidi(source, Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT)
        val groupWidths = FloatArray(columns.size)
        for (i in columns.lastIndex downTo 0) {
            val column = columns[i]
            groupWidths[i] = column.width + if (column.grouped && i < columns.lastIndex) column.gap + groupWidths[i + 1] else 0f
        }
        var groupStart = 0
        while (groupStart < columns.size) {
            var groupEnd = groupStart
            while (groupEnd < columns.lastIndex && columns[groupEnd].grouped) groupEnd++
            val keepTogether = groupEnd > groupStart && groupWidths[groupStart] <= available
            for (i in groupStart..groupEnd) columns[i].wrapped = keepTogether
            groupStart = groupEnd + 1
        }
        var y = top
        var start = 0
        while (start < columns.size) {
            var end = start
            var rowWidth = 0f
            while (end < columns.size) {
                val column = columns[end]
                val gap = if (end == start) 0f else columns[end - 1].gap
                val startsGroup = end == start || !columns[end - 1].grouped
                val itemWidth = if (startsGroup && column.wrapped) groupWidths[end] else column.width
                if (end > start && rowWidth + gap + itemWidth > available) break
                rowWidth += gap + column.width
                end++
            }
            val row = Array(end - start) { columns[start + it] }
            val levels = ByteArray(row.size) { bidi.getLevelAt(row[it].offset).toByte() }
            Bidi.reorderVisually(levels, 0, row, 0, row.size)
            val rightAligned = duetRightAligned ?: (bidi.baseLevel == 1)
            var left = x + if (rightAligned) available - rowWidth else 0f
            val mainBaseline = row.maxOf { it.main.getLineBaseline(0) }
            val mainDescent = row.maxOf { it.main.height - it.main.getLineBaseline(0) }
            val readingBaseline = row.maxOf { it.reading?.getLineBaseline(0) ?: 0 }
            val mainSweep = SweepRow(x, available.toFloat(), bidi.baseLevel == 1)
            sweepRows += mainSweep
            val readingText = if (paired) buildString { for (column in row) append(column.token.pronunciation) } else ""
            val readingRtl = paired && Bidi(readingText, Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT).baseLevel == 1
            val readingSweep = if (row.any { it.reading != null }) {
                SweepRow(x, available.toFloat(), readingRtl, pronunciation = true).also { sweepRows += it }
            } else null
            val readingDescent = row.maxOf { it.reading?.let { layout -> layout.height - layout.getLineBaseline(0) } ?: 0 }
            val lastOffset = columns[end - 1].offset
            for (column in row) {
                val rightToLeft = bidi.getLevelAt(column.offset) % 2 != 0
                val gap = if (column.offset == lastOffset) 0f else column.gap
                if (rightToLeft) left += gap
                fun addCell(layout: StaticLayout, baseline: Float, sweep: SweepRow) {
                    val reading = sweep.pronunciation
                    val cellLeft = left + if (rightToLeft) column.width - layout.width * column.scale else 0f
                    val block = Block(layout.text.toString(), layout.paint, layout, cellLeft,
                        baseline - layout.getLineBaseline(0), scaleX = column.scale, rightAligned = rightAligned).apply {
                        this.backing = backing
                        pronunciation = reading
                    }
                    val token = column.token
                    buildTimedCell(block, token, sweep, if (reading) null else emphasis[column.tokenIndex],
                        groupIndices[column.tokenIndex])
                    if (token.isTimed) {
                        val word = (if (reading) token.pronunciation else token.text).trim()
                        val cjk = containsCjk(word)
                        val terminal = column.offset == lastOffset
                        val extension = LyricMotion.featherExtension(cjk, terminal, !reading && column.grouped,
                            word.length, if (reading) word.length else column.total,
                            pairedExpansion = column.reading != null && column.width > layout.width,
                            flexParent = column.reading == null && !column.wrapped) * dp(LyricMotion.featherDp)
                        val advances = FloatArray(layout.lineCount) {
                            (if (layout.lineCount == 1) layout.width.toFloat() else layout.getLineWidth(it)) * column.scale
                        }
                        var totalAdvance = 0f
                        for (advance in advances) totalAdvance += advance
                        val sign = if (sweep.rtl) -1f else 1f
                        val origin = cellLeft - x + if (sweep.rtl) layout.width * column.scale else 0f
                        var consumed = 0f
                        for (i in block.bands.indices) {
                            val bandRow = block.bands[i].sweepRow
                            val rush = terminal && i == block.bands.lastIndex && cjk && !reading
                            // One source word/fraction; wrapping changes only the measured coordinate offset.
                            bandRow.sweeps += Sweep(token, origin, origin + sign * totalAdvance, extension,
                                rush, column.tokenIndex, nextBegins[column.tokenIndex], i == 0 && (!cjk || reading),
                                geometryOffset = -sign * consumed)
                            consumed += advances[i]
                        }
                    }
                    blocks += block
                }
                addCell(column.main, y + mainBaseline, mainSweep)
                if (readingSweep != null) column.reading?.let {
                    addCell(it, y + mainBaseline + mainDescent + readingBaseline, readingSweep)
                }
                left += column.width + if (rightToLeft) 0f else gap
            }
            y += mainBaseline + mainDescent + readingBaseline + readingDescent +
                (if (paired) dp(LyricMotion.pronunciationRowPaddingDp) else 0f)
            start = end
        }
        return y
    }

    private fun build(width: Int) {
        builtWidth = width
        blocks.clear()
        groups.clear()
        sweepRows.clear()
        foregroundPivotX = 0f
        foregroundPivotY = 0f
        val model = line
        val edge = dp(if (isStatic && model != null) 16f else 32f)
        val usable = (width - 2 * edge).toInt().coerceAtLeast(1)
        val duetRightAligned = model?.duetRightAligned
        val wordRightAligned = if (isWord && model != null) duetRightAligned ?: startsRtl(model.main.text) else false
        val available = if (model?.narrow == true && duetRightAligned != null) {
            // The width fraction applies inside the row margins, before text padding.
            val margin = dp(KARAOKE_ROW_MARGIN_DP)
            ((width - 2 * margin) * .85f - 2 * (edge - margin)).roundToInt().coerceAtLeast(1)
        } else usable
        val x = if (duetRightAligned == true) width - edge - available else edge
        val padding = edge - dp(KARAOKE_ROW_MARGIN_DP)
        val frameLeft = x - padding
        val frameRight = x + available + padding
        var y = if (isStatic && model != null) 0f else dp(2f)

        fun addText(
            text: String, size: Float, backing: Boolean,
            weight: Int = 700, translation: Boolean = false, pronunciation: Boolean = false,
            writerCredit: Boolean = false, attribution: Boolean = false,
            rightAligned: Boolean? = null,
        ) {
            if (text.isBlank()) return
            val spaced = LyricTextAutospace.apply(text)
            val paint = lyricPaint(size, weight, customize = !writerCredit && !attribution).apply {
                if (translation || pronunciation) letterSpacing = when {
                    translation && isStatic -> LyricMotion.legacyTranslationTrackingEm
                    backing -> LyricMotion.backgroundAnnotationTrackingEm
                    else -> LyricMotion.mainAnnotationTrackingEm
                }
                if (translation && isStatic) color = LyricMotion.legacyTranslationColor
            }
            val isLegacyMain = isStatic && line != null && !translation && !pronunciation && !backing
            val badgeWidth = if (attribution) dp(18f).roundToInt() else 0
            val layout = textLayout(spaced.layoutText(), paint, (available - badgeWidth).coerceAtLeast(1),
                rightAligned = duetRightAligned, staticLineHeight = isLegacyMain)
            val textX = x + if (attribution && layoutDirection != LAYOUT_DIRECTION_RTL) badgeWidth else 0
            val block = Block(spaced.text, paint, layout, textX, y, rightAligned = rightAligned).apply {
                this.backing = backing
                this.translation = translation
                this.pronunciation = pronunciation
                this.writerCredit = writerCredit
                this.attribution = attribution
            }
            blocks += block
            y += layout.height
        }

        fun addVoice(voice: LyricVoice, backing: Boolean) {
            val firstSweepRow = sweepRows.size
            val tokens = voice.tokens
            val hasTimed = tokens.any { it.isTimed && it.text.isNotBlank() }
            val plain = voice.text
            val rightAligned = when {
                isWord -> wordRightAligned
                !isStatic -> startsRtl(voice.text)
                else -> duetRightAligned ?: (Bidi(plain, Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT).baseLevel == 1)
            }
            val mainSize = if (isStatic) LyricMotion.legacyMainSp else if (backing) LyricMotion.backgroundSp else LyricMotion.karaokeMainSp
            val timed = isWord && hasTimed
            val paired = timed && showPronunciation && tokens.any { it.pronunciation.isNotBlank() }
            if (timed) {
                y = addTimedVoice(tokens, mainSize, backing, duetRightAligned, x, y, available, paired)
            } else {
                addText(tokens.asText().ifBlank { plain }, mainSize, backing, rightAligned = rightAligned)
            }
            if (!paired && showPronunciation && voice.pronunciation.isNotBlank()) {
                val size = if (backing) LyricMotion.backgroundPronunciationSp else LyricMotion.karaokePronunciationSp
                addText(voice.pronunciation, size, backing, pronunciation = true, rightAligned = rightAligned)
                if (isWord) y += dp(LyricMotion.pronunciationRowPaddingDp)
            }
            if (showTranslation && voice.translation.isNotBlank() && !voice.translation.equals(plain, true)) {
                val size = when {
                    isStatic -> LyricMotion.legacyTranslationSp
                    backing -> LyricMotion.backgroundTranslationSp
                    else -> LyricMotion.karaokeTranslationSp
                }
                val margin = when {
                    isStatic -> LyricMotion.legacyTranslationMarginDp
                    backing -> LyricMotion.backgroundTranslationMarginDp
                    else -> LyricMotion.karaokeTranslationMarginDp
                }
                y += dp(margin)
                addText(voice.translation, size, backing,
                    if (isStatic) 500 else 700, translation = true, rightAligned = rightAligned)
            }
            sealSweepRows(firstSweepRow)
            if (backing) {
                backingPivotY = y
                backingPivotX = if (isWord) {
                    if (wordRightAligned) frameRight else frameLeft
                } else x + if (rightAligned) available else 0
            }
        }

        when {
            interlude -> y += dp(36f)
            model != null -> addVoice(model.main, backing = false)
            else -> {
                y += addWriterCredits(credits, 22f, available, x, y)
                if (!isStatic && creditsAttribution.isNotBlank()) {
                    if (credits.isNotBlank()) y += sp(22f)
                    addText(creditsAttribution, 15f, backing = false,
                        weight = 400, attribution = true)
                }
            }
        }
        if (isWord && model != null) {
            foregroundPivotX = if (wordRightAligned) frameRight else frameLeft
            foregroundPivotY = y
        }
        val bottomPadding = dp(if (isStatic && model != null) 11f else 10f)
        collapsedContentHeight = (y + bottomPadding).roundToInt()
        expandedContentHeight = if (!interlude && model != null && hasBackground && backgroundVisible) {
            y += dp(22f)
            addVoice(model.background, backing = true)
            (y + bottomPadding).roundToInt()
        } else collapsedContentHeight
        for (group in groups) group.glyphs.sortBy { it.index }
        updateContentDescription()

    }

    private fun addWriterCredits(text: String, size: Float, available: Int, x: Float, y: Float): Int {
        if (text.isBlank()) return 0
        val styled = android.text.SpannableString(text)
        val prefixEnd = text.indexOf('：').takeIf { it >= 0 }?.plus(1) ?: 0
        if (prefixEnd > 0) styled.setSpan(android.text.style.StyleSpan(Typeface.BOLD), 0, prefixEnd,
            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val paint = lyricPaint(size, 400, customize = false)
        val layout = textLayout(styled, paint, available)
        blocks += Block(text, paint, layout, x, y).apply { writerCredit = true }
        return layout.height
    }

    private fun List<LyricToken>.asText() = buildString {
        for (token in this@asText) {
            append(token.text)
            if (token.spaceAfter) append(' ')
        }
    }

    @Suppress("DEPRECATION")
    private fun buildTimedCell(block: Block, token: LyricToken, sweep: SweepRow, emphasis: Group?, groupIndex: Int) {
        val layout = block.layout
        val text = block.text
        val word = if (block.pronunciation) token.pronunciation else token.text
        val split = SplitCharacters.containsMatchIn(word)
        val lift = token.isTimed && !block.pronunciation && !JoinedScripts.containsMatchIn(word)
        val individual = emphasis != null || split || LyricTextAutospace.GAP_CHAR in text
        val iterator = if (individual) BreakIterator.getCharacterInstance().apply { setText(text) } else null
        val tokenSize = word.codePointCount(0, word.length).coerceAtLeast(1)
        var index = 0
        for (row in 0 until layout.lineCount) {
            val bandRow = if (row == 0) sweep else SweepRow(sweep.x, sweep.width, sweep.rtl, sweep.pronunciation)
                .also { sweepRows += it }
            val band = Band(layout.getLineTop(row).toFloat(), layout.getLineBottom(row).toFloat(),
                layout.getLineBaseline(row).toFloat(), bandRow)
            block.bands += band
            var at = layout.getLineStart(row)
            val limit = layout.getLineVisibleEnd(row)
            var runEnd = at
            while (at < limit) {
                val codePoint = text.codePointAt(at)
                if (codePoint == LyricTextAutospace.GAP_CHAR.code || Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) {
                    at += Character.charCount(codePoint)
                    continue
                }
                val rtl = layout.isRtlCharAt(at)
                if (at >= runEnd) runEnd = textRunEnd(layout, text, at, limit, rtl)
                val end = if (iterator == null) runEnd else {
                    val boundary = iterator.following(at)
                    if (boundary > at) minOf(boundary, runEnd) else runEnd
                }
                layout.getSelectionPath(at, end, glyphPath)
                if (row < layout.lineCount - 1 && end == layout.getLineEnd(row)) {
                    // An exclusive end at the next row also selects that row's edge contour.
                    glyphRowClip.rewind()
                    glyphRowClip.addRect(layout.getLineLeft(row), band.top,
                        layout.getLineRight(row), band.bottom, Path.Direction.CW)
                    glyphPath.op(glyphRowClip, Path.Op.INTERSECT)
                }
                glyphPath.computeBounds(glyphBounds, true)
                if (emphasis != null) block.paint.getTextBounds(text, at, end, bounds)
                val delay = when {
                    !lift -> Long.MAX_VALUE
                    emphasis != null -> LyricMotion.attackDelay(emphasis.duration, groupIndex + index, emphasis.count)
                    split -> index * ((token.end - token.begin).toFloat() / tokenSize).roundToInt().toLong()
                    else -> 0L
                }
                val glyph = Glyph(at, end, glyphBounds.left, glyphBounds.width(),
                    if (emphasis != null) bounds.width() else 0, rtl, token, delay, emphasis, groupIndex + index)
                if (wordLiftTime != Long.MIN_VALUE) glyph.liftSamplePx = wordLiftAt(glyph, wordLiftTime)
                band.glyphs += glyph
                emphasis?.glyphs?.add(glyph)
                index++
                at = end
            }
        }
    }

    private fun textRunEnd(layout: StaticLayout, text: String, start: Int, limit: Int, rtl: Boolean): Int {
        var end = start + Character.charCount(text.codePointAt(start))
        while (end < limit && text[end] != LyricTextAutospace.GAP_CHAR && layout.isRtlCharAt(end) == rtl) {
            end += Character.charCount(text.codePointAt(end))
        }
        return end
    }

    private fun sealSweepRows(firstRow: Int) {
        var voiceEnd = Long.MIN_VALUE
        if (firstRow == sweepRows.size) return
        for (index in firstRow until sweepRows.size) {
            val row = sweepRows[index]
            row.sweeps.sortBy { it.order }
            var frontier = if (row.rtl) row.width else 0f
            val sign = if (row.rtl) -1f else 1f
            for (sweep in row.sweeps) {
                // W ends the preceding ordinary sweep before e0 captures the next start.
                sweep.from = if (row.rtl) minOf(frontier, sweep.start) else maxOf(frontier, sweep.start)
                val target = sweep.end + sign * sweep.extension
                sweep.target = if (row.rtl) minOf(sweep.from, target) else maxOf(sweep.from, target)
                frontier = sweep.target
                voiceEnd = maxOf(voiceEnd, sweep.token.begin, sweep.token.end)
            }
        }
        if (voiceEnd == Long.MIN_VALUE) return
        var mainHandoff = Long.MAX_VALUE
        var readingHandoff = Long.MAX_VALUE
        for (index in sweepRows.lastIndex downTo firstRow) {
            val row = sweepRows[index]
            val handoff = if (row.pronunciation) readingHandoff else mainHandoff
            var rushEnd = Long.MIN_VALUE
            var nextHandoff = handoff
            for (sweep in row.sweeps) {
                if (sweep.rush) rushEnd = maxOf(rushEnd, maxOf(sweep.token.begin, sweep.token.end) + LyricMotion.rushMs)
                if (sweep.clearsPreviousRows) nextHandoff = minOf(nextHandoff, sweep.token.begin)
            }
            // The local player holds finished voices on screen. Settle the mask at the real
            // voice end, or after its CJK rush, not at a wrap or in a gap between words.
            row.completedAt = minOf(handoff, maxOf(voiceEnd, rushEnd))
            if (row.pronunciation) readingHandoff = nextHandoff else mainHandoff = nextHandoff
        }
    }

    private fun sweepFrontier(row: SweepRow, time: Long): Float {
        val feather = dp(LyricMotion.featherDp)
        val exit = if (row.rtl) -feather else row.width + feather
        if (time >= row.completedAt) return exit
        var frontier = if (row.rtl) row.width else 0f
        for (i in row.sweeps.indices) {
            val sweep = row.sweeps[i]
            val elapsed = time - sweep.token.begin
            if (elapsed < 0) continue
            val duration = (sweep.token.end - sweep.token.begin).coerceAtLeast(0L)
            val target = sweep.target + sweep.geometryOffset
            val ordinary = if (time >= sweep.nextBegin) target else sweep.geometryOffset +
                sweep.from + (sweep.target - sweep.from) * LyricMotion.fraction(elapsed, duration)
            val next = if (sweep.rush && elapsed >= duration && sweep.target != sweep.from) {
                target + (exit - target) * LyricMotion.fraction(elapsed - duration, LyricMotion.rushMs)
            } else ordinary
            frontier = if (row.rtl) minOf(frontier, next) else maxOf(frontier, next)
        }
        return frontier
    }

    private fun prepareWordMotion(ink: Float) {
        val time = position + LyricMotion.wordLeadMs
        for (groupIndex in groups.indices) {
            val group = groups[groupIndex]
            val glyphs = group.glyphs
            val moving = !reduceMotion && ink > 0f
            for (glyphIndex in glyphs.indices) {
                val glyph = glyphs[glyphIndex]
                glyph.envelope = if (moving) LyricMotion.envelope(time - group.begin, group.duration, glyph.index, group.count) else 0f
                glyph.halfGrowth = glyph.inkWidth * group.peak * glyph.envelope * .5f
                glyph.shift = 0f
            }
            val sign = if (group.rtl) -1f else 1f
            val middle = (glyphs.size - 1) / 2f
            for (i in (ceil(middle).toInt() - 1) downTo 0) {
                val glyph = glyphs[i]
                val next = glyphs[i + 1]
                glyph.shift = -sign * (glyph.halfGrowth + (if (i + 1 <= middle) next.halfGrowth else 0f) +
                    (if (i + 1 < middle) abs(next.shift) else 0f)) * .5f
            }
            for (i in (kotlin.math.floor(middle).toInt() + 1) until glyphs.size) {
                val glyph = glyphs[i]
                val previous = glyphs[i - 1]
                glyph.shift = sign * (glyph.halfGrowth + (if (i - 1 >= middle) previous.halfGrowth else 0f) +
                    (if (i - 1 > middle) abs(previous.shift) else 0f)) * .5f
            }
        }
        for (rowIndex in sweepRows.indices) {
            val row = sweepRows[rowIndex]
            row.frontier = sweepFrontier(row, time)
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (interlude) { drawDots(canvas); return }
        val now = SystemClock.uptimeMillis()
        updateBackingMotion(now)
        val expandingBacking = backingHeightDuration > 0L
        if (expandingBacking) requestLayout()
        val backingAlpha = currentBackingAlpha(now)
        val backingScale = currentBackingScale(now)
        val ink = currentAlpha(now)
        prepareWordMotion(ink)
        val returningLift = !reduceMotion && wordLiftReturnAt != Long.MIN_VALUE
        var remaining = 1f
        var settledBelowPx = 0f
        if (returningLift) {
            val elapsed = now - wordLiftReturnAt
            LyricMotion.sampleLiftSpring(elapsed, withVelocity = true) { remainingSample, velocityPerSecond ->
                remaining = remainingSample.toFloat()
                if (elapsed > 0L) {
                    settledBelowPx = minOf(
                        0.75 / abs(remainingSample),
                        46.875 / abs(velocityPerSecond),
                    ).toFloat()
                }
            }
        } else {
            wordLiftTime = if (isWord && emphasized && !reduceMotion && ink > 0f) {
                position + LyricMotion.wordLeadMs
            } else Long.MIN_VALUE
        }
        val scale = if (isStatic) 1f else currentScale(now)
        val saved = canvas.save()
        val wordContainerMode = isWord && line != null
        val foregroundSaved = if (wordContainerMode) canvas.save() else saved
        if (wordContainerMode) canvas.scale(scale, scale, foregroundPivotX, foregroundPivotY)
        var drawingBacking = false
        var hasReturningLift = false
        for (blockIndex in blocks.indices) {
            val block = blocks[blockIndex]
            if (block.backing) {
                if (!backgroundVisible || backingAlpha <= 0f) continue
                if (!drawingBacking) {
                    // Native karaoke voices are siblings; backing clip/alpha stay in row coordinates.
                    if (wordContainerMode) canvas.restoreToCount(foregroundSaved)
                    drawingBacking = true
                    // RecyclerView allows ink overflow; clip only the opening backing voice to the row.
                    if (expandingBacking) canvas.clipRect(0, 0, width, height)
                    // j0 fades the contiguous backing blocks as one group, including masks and annotations.
                    if (backingAlpha < 1f) canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(),
                        (255 * backingAlpha).roundToInt())
                    if (wordContainerMode) canvas.scale(backingScale, backingScale, backingPivotX, backingPivotY)
                }
            }
            val shifted = canvas.save()
            if (!wordContainerMode) {
                canvas.scale(scale, scale, block.pivotX, block.pivotY)
                if (block.backing) canvas.scale(backingScale, backingScale, backingPivotX, backingPivotY)
            }
            canvas.translate(block.x, block.y)
            if (block.scaleX != 1f) canvas.scale(block.scaleX, 1f)
            if (block.bands.isEmpty()) {
                val alpha = when {
                    block.writerCredit -> LyricMotion.secondaryTextAlpha
                    block.attribution -> LyricMotion.secondaryTextAlpha
                    // Karaoke translation masks stay at rest; sentence-synced translations have no alpha binding.
                    block.translation -> when {
                        isStatic -> LyricMotion.legacyTranslationAlpha
                        isWord -> LyricMotion.waiting
                        else -> 255
                    }
                    isStatic -> 204
                    block.backing -> (LyricMotion.waiting + (LyricMotion.background - LyricMotion.waiting) * ink).roundToInt()
                    else -> (LyricMotion.waiting + (LyricMotion.foreground - LyricMotion.waiting) * ink).roundToInt()
                }
                block.paint.alpha = alpha
                if (block.attribution) {
                    val badgeWidth = dp(18f).roundToInt()
                    val badgeLeft = if (layoutDirection == LAYOUT_DIRECTION_RTL) block.layout.width else -badgeWidth
                    attributionBadge.setBounds(badgeLeft, 0, badgeLeft + badgeWidth, dp(20f).roundToInt())
                    attributionBadge.draw(canvas)
                }
                block.layout.draw(canvas)
                block.paint.alpha = 255
            } else {
                for (bandIndex in block.bands.indices) {
                    hasReturningLift = paintBand(canvas, block, block.bands[bandIndex], ink,
                        returningLift, remaining, settledBelowPx) || hasReturningLift
                }
            }
            canvas.restoreToCount(shifted)
        }
        canvas.restoreToCount(saved)
        if (returningLift && !hasReturningLift) resetWordLift()
        if (!reduceMotion && (hasReturningLift || expandingBacking ||
                (scaleDuration > 0L && now < scaleAt + scaleDuration) ||
                (alphaDuration > 0L && now < alphaAt + alphaDuration) ||
                backingMotionActive)) postInvalidateOnAnimation()
    }

    private fun paintBand(
        canvas: Canvas,
        block: Block,
        band: Band,
        ink: Float,
        returningLift: Boolean,
        remaining: Float,
        settledBelowPx: Float,
    ): Boolean {
        val extent = block.layout.width.toFloat()
        val row = band.sweepRow
        val rtl = row.rtl
        val feather = dp(LyricMotion.featherDp)
        val rawRowWidth = row.width
        val rowWidth = rawRowWidth.coerceAtLeast(1f)
        val frontier = row.frontier
        val hasWidth = rawRowWidth > 0f
        val highEdge = if (hasWidth) {
            (frontier + if (rtl) feather else -feather).coerceIn(0f, rawRowWidth)
        } else 0f
        val lowEdge = if (hasWidth) frontier.coerceIn(0f, rawRowWidth) else 0f
        val unlit = !hasWidth || ink <= 0f || if (rtl) frontier >= rawRowWidth else frontier <= 0f
        val lit = hasWidth && !unlit &&
            ((if (rtl) frontier <= -feather else frontier >= rawRowWidth + feather) || highEdge == lowEdge)
        if (!unlit && !lit) {
            val scale = block.scaleX
            val offset = block.x - row.x
            matrix.reset()
            matrix.setScale(rowWidth / scale, 1f)
            matrix.postTranslate(-offset / scale, 0f)
        }
        val glow = dp(LyricMotion.glowDp)
        val pad = dp(12f)
        val left = -pad
        val top = band.top - pad
        val right = extent + pad
        val bottom = band.bottom + pad
        val layer = canvas.saveLayer(left, top, right, bottom, null)
        textPaint.set(block.paint)
        var hasReturningLift = false
        for (glyphIndex in band.glyphs.indices) {
            val glyph = band.glyphs[glyphIndex]
            val group = glyph.group
            val lift = if (returningLift) {
                val from = glyph.liftSamplePx
                if (from == 0f || abs(from) < settledBelowPx) 0f else {
                    hasReturningLift = true
                    from * remaining
                }
            } else if (wordLiftTime != Long.MIN_VALUE) {
                wordLiftAt(glyph, wordLiftTime).also { glyph.liftSamplePx = it }
            } else 0f
            val scale = 1f + (group?.peak ?: 0f) * glyph.envelope
            val saved = canvas.save()
            canvas.translate(glyph.shift, lift)
            canvas.scale(scale, scale, glyph.x + glyph.width / 2, band.baseline)
            if (glyph.envelope > 0f) textPaint.setShadowLayer(glow, 0f, 0f,
                Color.argb((128 * glyph.envelope).roundToInt(), 255, 255, 255))
            canvas.drawTextRun(block.text, glyph.start, glyph.end, glyph.start, glyph.end,
                glyph.x, band.baseline, glyph.rtl, textPaint)
            textPaint.clearShadowLayer()
            canvas.restoreToCount(saved)
        }
        mask.shader = null
        mask.color = Color.WHITE
        mask.alpha = 255
        when {
            unlit -> {
                mask.alpha = LyricMotion.waiting
                canvas.drawRect(left, top, right, bottom, mask)
            }
            lit -> {
                val high = if (block.backing) LyricMotion.background else LyricMotion.foreground
                mask.alpha = (LyricMotion.waiting + (high - LyricMotion.waiting) * ink).roundToInt()
                canvas.drawRect(left, top, right, bottom, mask)
            }
            ink < 1f -> {
                val stops = maskStops(frontier, rowWidth, rtl, feather)
                val shader = LinearGradient(0f, 0f, 1f, 0f, releasePalette(block.backing, rtl), stops,
                    Shader.TileMode.CLAMP)
                shader.setLocalMatrix(matrix)
                // Keep the unsung mask at waiting alpha while fading only the sung portion.
                val retiring = canvas.saveLayer(left, top, right, bottom, mask)
                solid.shader = null
                solid.color = Color.WHITE
                solid.alpha = LyricMotion.waiting
                canvas.drawRect(left, top, right, bottom, solid)
                solid.shader = shader
                solid.alpha = (255 * ink).roundToInt()
                canvas.drawRect(left, top, right, bottom, solid)
                solid.shader = null
                canvas.restoreToCount(retiring)
            }
            else -> {
                val shader = LinearGradient(0f, 0f, 1f, 0f, maskPalette(block.backing, rtl),
                    maskStops(frontier, rowWidth, rtl, feather), Shader.TileMode.CLAMP)
                shader.setLocalMatrix(matrix)
                mask.shader = shader
                canvas.drawRect(left, top, right, bottom, mask)
                mask.shader = null
            }
        }
        mask.shader = null
        canvas.restoreToCount(layer)
        return hasReturningLift
    }

    private fun drawDots(canvas: Canvas) {
        val duration = (interludeEnd - interludeStart - 800L).coerceAtLeast(1)
        val time = position - interludeStart
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        val pivot = if (rtl) width - dp(32f) else dp(32f)
        val sign = if (rtl) -1f else 1f
        var scale = if (reduceMotion) 1f else LyricMotion.dotScale(time, duration)
        var opacity = 1f
        if (!reduceMotion && time >= duration) {
            val p = LyricMotion.emphasis.getInterpolation(LyricMotion.fraction(time - duration, 750))
            scale += (1.2f - scale) * p
            if (time >= duration + 750) {
                val exit = LyricMotion.dotExit.getInterpolation(LyricMotion.fraction(time - duration - 750, 250))
                scale = 1.2f - .7f * exit
                opacity = 1f - exit
            }
        }
        val y = height / 2f
        val save = canvas.save()
        canvas.scale(scale, scale, pivot, y)
        val interval = ((duration + 750) / 3f).roundToInt().toLong()
        for (i in 0..2) {
            val t = time - i * interval
            val p = LyricMotion.dotProgress.getInterpolation(LyricMotion.fraction(t, if (i < 2) interval else duration - 2 * interval))
            val enter = if (reduceMotion) 1f else LyricMotion.fraction(time - i * 50L, 750)
            solid.color = Color.WHITE
            solid.alpha = ((LyricMotion.waiting + (LyricMotion.foreground - LyricMotion.waiting) * p) * opacity * enter * enter).roundToInt()
            canvas.drawCircle(pivot + sign * dp(5f + 22f * i), y, dp(5f), solid)
        }
        canvas.restoreToCount(save)
    }

    private companion object {
        const val KARAOKE_ROW_MARGIN_DP = 20f
        const val LEGACY_STATIC_LINE_HEIGHT_SP = 26f
        val SplitCharacters = Regex("[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}\\p{IsHangul}\\p{IsThai}]")
        val JoinedScripts = Regex("[\\p{IsArabic}\\p{IsDevanagari}]")
    }
}
