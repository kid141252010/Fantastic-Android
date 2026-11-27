package io.github.kid141252010.fantastic.ui.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.Typeface
import android.os.Build
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil3.compose.AsyncImage
import io.github.kid141252010.fantastic.data.FontWeights
import io.github.kid141252010.fantastic.ui.CircularTouchScope
import io.github.kid141252010.fantastic.ui.theme.LocalAmFonts
import io.github.kid141252010.fantastic.ui.theme.LocalFontWeights

internal val PlayerPrimary = Color(0xF0FFFFFF)
internal val PlayerSecondary = Color(0x73FFFFFF)

@Composable
internal fun PlayerArtwork(
    model: Any?, modifier: Modifier, radius: Dp, elevation: Dp = 0.dp, description: String? = null,
    stroke: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(radius)
    val strokeColor = if (isSystemInDarkTheme()) Color(0x4DEBEBF5) else Color(0x1A000000)
    AsyncImage(
        model = model,
        contentDescription = description,
        contentScale = ContentScale.Crop,
        modifier = modifier.shadow(elevation, shape).clip(shape)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .background(Color.White.copy(alpha = .12f))
            .then(if (stroke) Modifier.border(.5.dp, strokeColor, shape) else Modifier),
    )
}

@Composable
internal fun HeaderAction(
    symbol: PlayerSymbol,
    description: String,
    padding: PaddingValues,
    modifier: Modifier = Modifier,
    tint: Color = PlayerPrimary,
    checked: Boolean? = null,
    enabled: Boolean = true,
    exactHitTarget: Boolean = false,
    onClick: () -> Unit,
) {
    if (exactHitTarget) {
        Box(modifier.padding(padding), contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(symbol.intrinsicSize)
                    .then(if (checked != null) Modifier.semantics { selected = checked } else Modifier)
                    .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
                contentAlignment = Alignment.Center,
            ) {
                PlayerGlyph(symbol, description, tint, Modifier.size(symbol.intrinsicSize), selected = checked == true)
            }
        }
        return
    }
    if (symbol == PlayerSymbol.Star || symbol == PlayerSymbol.More) {
        Box(modifier.padding(padding), contentAlignment = Alignment.Center) {
            CircularTouchScope {
                Box(
                    Modifier.size(symbol.intrinsicSize).clip(CircleShape)
                        .then(if (checked != null) Modifier.semantics { selected = checked } else Modifier)
                        .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
                    contentAlignment = Alignment.Center,
                ) {
                    PlayerGlyph(symbol, description, tint, Modifier.size(symbol.intrinsicSize), selected = checked == true)
                }
            }
        }
        return
    }
    Box(
        modifier.then(if (checked != null) Modifier.semantics { selected = checked } else Modifier)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(padding),
        contentAlignment = Alignment.Center,
    ) {
        PlayerGlyph(symbol, description, tint, Modifier.size(symbol.intrinsicSize), selected = checked == true)
    }
}

/** Native marquee; each pass has one repeat. */
@Composable
internal fun PlayerMetadataText(
    title: String,
    artist: String,
    compact: Boolean,
    marquee: Boolean,
    modifier: Modifier = Modifier,
    onOpenEntities: (() -> Unit)? = null,
) {
    val fontWeights = LocalFontWeights.current
    AndroidView(
        factory = ::MetadataRail,
        modifier = modifier,
        update = { it.bind(title, artist, compact, marquee, onOpenEntities, fontWeights) },
    )
}

private class MetadataRail(context: Context) : LinearLayout(context) {
    private val title = MarqueeLabel(context, medium = true, color = 0xF0FFFFFF.toInt())
    private val artist = MarqueeLabel(context, medium = false, color = 0x73FFFFFF)
    private var allowMarquee = false
    private var titleAction: (() -> Unit)? = null
    private var artistAction: (() -> Unit)? = null
    private val restart = Runnable {
        if (canRun() && !title.betweenEnds && !artist.betweenEnds) {
            stopPair()
            startPair()
        }
    }
    private val start = Runnable(::startPair)

    init {
        orientation = VERTICAL
        addView(title, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(artist, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        title.setOnClickListener { titleAction?.invoke() }
        artist.setOnClickListener { artistAction?.invoke() }
        val pressedBackground = TypedValue()
        if (context.theme.resolveAttribute(android.R.attr.selectableItemBackground, pressedBackground, true)) {
            title.background = context.getDrawable(pressedBackground.resourceId)
            artist.background = context.getDrawable(pressedBackground.resourceId)
        }
        title.onEndEdge = ::waitAtEdge
        artist.onEndEdge = ::waitAtEdge
    }

    fun bind(
        titleText: String, artistText: String, compact: Boolean, marquee: Boolean,
        onOpenEntities: (() -> Unit)?, fontWeights: FontWeights,
    ) {
        titleAction = if (compact) null else onOpenEntities
        artistAction = onOpenEntities
        title.isClickable = titleAction != null
        title.isFocusable = title.isClickable
        artist.isClickable = artistAction != null
        artist.isFocusable = artist.isClickable
        val labelWidth = if (compact) LayoutParams.WRAP_CONTENT else LayoutParams.MATCH_PARENT
        val titleParams = title.layoutParams
        if (titleParams.width != labelWidth) {
            titleParams.width = labelWidth
            title.layoutParams = titleParams
        }
        val artistParams = artist.layoutParams
        if (artistParams.width != labelWidth) {
            artistParams.width = labelWidth
            artist.layoutParams = artistParams
        }
        val size = if (compact) 17 else 20
        val changed = title.bind(titleText, size, fontWeights.title) or artist.bind(artistText, size, fontWeights.body)
        if (allowMarquee != marquee) {
            allowMarquee = marquee
            title.ellipsize = if (marquee) TextUtils.TruncateAt.MARQUEE else TextUtils.TruncateAt.END
            artist.ellipsize = title.ellipsize
            schedule()
        } else if (changed) schedule()
    }

    private fun canRun() = allowMarquee && isAttachedToWindow && windowVisibility == VISIBLE && isShown

    private fun startPair() {
        if (!canRun()) return
        title.selectForMarquee(true)
        artist.selectForMarquee(true)
    }

    private fun stopPair() {
        removeCallbacks(start)
        removeCallbacks(restart)
        title.selectForMarquee(false)
        artist.selectForMarquee(false)
    }

    private fun schedule() {
        stopPair()
        if (canRun()) postDelayed(start, MARQUEE_DELAY_MS)
    }

    private fun waitAtEdge() {
        removeCallbacks(restart)
        postDelayed(restart, MARQUEE_DELAY_MS)
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) schedule() else stopPair()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw) schedule()
    }

    override fun onDetachedFromWindow() {
        stopPair()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val MARQUEE_DELAY_MS = 3000L
    }
}

private class MarqueeLabel(context: Context, medium: Boolean, color: Int) : TextView(context) {
    private var textSizeSp = 0
    private val fontWeight = if (medium) 500 else 400
    private var customFontWeight = -1
    private var changingSelection = false
    var onEndEdge: () -> Unit = {}
    private val overflowing: Boolean get() = leftFadingEdgeStrength > 0f || rightFadingEdgeStrength > 0f
    private val atEndEdge: Boolean get() = (leftFadingEdgeStrength == 0f) != (rightFadingEdgeStrength == 0f)
    val betweenEnds: Boolean get() = overflowing && !atEndEdge

    init {
        typeface = Typeface.create(if (medium) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
        setTextColor(color)
        setSingleLine(true)
        setHorizontallyScrolling(true)
        ellipsize = TextUtils.TruncateAt.END
        marqueeRepeatLimit = 1
        textDirection = View.TEXT_DIRECTION_ANY_RTL
        textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
    }

    fun bind(value: String, size: Int, customWeight: Int): Boolean {
        val changedText = !TextUtils.equals(text, value)
        val fontChanged = size != textSizeSp || customWeight != customFontWeight
        val changed = changedText || fontChanged
        if (changedText) text = value
        if (fontChanged) {
            textSizeSp = size
            customFontWeight = customWeight
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size.toFloat())
            val weight = if (customWeight == 0) fontWeight else customWeight
            typeface = LocalAmFonts.typeface(context, size.toFloat(), weight, exactWeight = customWeight != 0)
                ?: if (customWeight != 0 && Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.DEFAULT, weight, false)
                else Typeface.create(if (weight in 500..699) "sans-serif-medium" else "sans-serif",
                    if (weight >= 700) Typeface.BOLD else Typeface.NORMAL)
        }
        return changed
    }

    fun selectForMarquee(value: Boolean) {
        if (isSelected == value) return
        changingSelection = true
        isSelected = value
        changingSelection = false
    }

    override fun sendAccessibilityEvent(eventType: Int) {
        if (!changingSelection || eventType != AccessibilityEvent.TYPE_VIEW_SELECTED) super.sendAccessibilityEvent(eventType)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (isSelected && overflowing && atEndEdge) onEndEdge()
    }
}
