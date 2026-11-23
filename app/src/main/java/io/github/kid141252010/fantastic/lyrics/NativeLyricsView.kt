package io.github.kid141252010.fantastic.lyrics

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.os.SystemClock
import android.provider.Settings
import android.util.SparseBooleanArray
import android.util.SparseIntArray
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.VelocityTracker
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Android RecyclerView owns touch slop, fling, scroll bar, click and long-click dispatch. */
internal class NativeLyricsView(context: Context) : RecyclerView(context) {
    private data class Row(
        val id: Int,
        val begin: Long,
        val end: Long,
        val presentationAt: Long,
        val duration: Long,
        val lyric: LyricLine? = null,
        val dots: Boolean = false,
        val credits: String = "",
        val attribution: String = "",
        val lyricIndex: Int = -1,
        val cueBegin: Long = begin,
        val cueEnd: Long = end,
    )
    private val layout = object : LinearLayoutManager(context) {
        override fun calculateExtraLayoutSpace(state: State, extraLayoutSpace: IntArray) {
            super.calculateExtraLayoutSpace(state, extraLayoutSpace)
            if (moveExtraPx > 0) {
                extraLayoutSpace[0] = maxOf(extraLayoutSpace[0], moveExtraPx)
                extraLayoutSpace[1] = maxOf(extraLayoutSpace[1], moveExtraPx)
            }
        }
    }
    private val ink = InkAdapter()
    private val viewportCompositePaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
    }
    private val fading = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private val bottomFading = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private val bottomFadeColors = intArrayOf(Color.BLACK, 0x0d000000, Color.TRANSPARENT, Color.TRANSPARENT)
    private val bottomFadeStops = floatArrayOf(0f, 0f, 0f, 1f)
    private val positionsBeforeMove = SparseIntArray()
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var song: LyricSong? = null
    private var rows = emptyList<Row>()
    private var displayed = emptyList<Row>()
    private var selected = NO_POSITION
    private var selectedTailId = NO_POSITION
    private var selectedDuration = 750L
    private val emphasizedLyricIds = SparseBooleanArray()
    private val candidateLyricIds = SparseBooleanArray()
    private var lastPreparedCueBegin = Long.MIN_VALUE
    private var nextGapId = 0
    private var instant = true
    private var snapScaleChanges = true
    private var hasPlaybackAnchor = false
    private var seekFollowRequested = false
    private var browsing = false
    private var touch = false
    private var generation = 0
    private var moveExtraPx = 0
    private var moveEndAt = 0L
    private var pendingMovePosition = NO_POSITION
    private var pendingMoveDuration = 0L
    private var pendingResize = false
    private object AnnotationChange
    private var translation = true
    private var pronunciation = false
    private var playing = false
    private var timeAt = 0L
    private var timeValue = 0L
    private var currentTime = 0L
    private var lastDispatched = Long.MIN_VALUE
    private var fadeTopSize = 0
    private var fadeBottomSize = 0
    private var controlsInset = 0
    private var controlsVisibility = 1f
    private var bottomFadeFirstClear = 0f
    private var bottomFadeSecondClear = 0f
    private var tapTargetStartY = NO_POSITION
    private var controlsTapExclusive = false
    private var onControlsTap: (() -> Unit)? = null
    private var tapTargetDown = false
    private var tapTargetMoved = false
    private var tapTargetDownX = 0f
    private var tapTargetDownY = 0f
    private var tapTargetDownAt = 0L
    private var sheetTouchTracker: VelocityTracker? = null
    private var lastRawTouchY = 0f
    private var animate = true

    var onSeek: (Long) -> Unit = {}
    internal var onUserScroll: (() -> Unit)? = null
    internal var onSheetDragBy: ((Float) -> Boolean)? = null
    internal var onSheetScrollEnd: ((Float, Float) -> Boolean)? = null
    internal var onShareLyrics: ((List<LyricShareLine>, Int) -> Unit)? = null
        set(value) {
            if (field === value) return
            field = value
            updateShareAvailability()
        }

    internal val hasSheetTouch get() = sheetTouchTracker != null && touch

    val visibleLyricCount: Int get() = (0 until childCount).count { getChildAt(it).contentDescription?.isNotBlank() == true }
    internal fun setControlsInset(value: Int, visibility: Float) {
        val inset = value.coerceAtLeast(0)
        val fraction = visibility.coerceIn(0f, 1f)
        val insetChanged = controlsInset != inset
        if (!insetChanged && controlsVisibility == fraction) return
        controlsInset = inset
        controlsVisibility = fraction
        if (insetChanged) {
            updateViewport()
            if (selected != NO_POSITION && !browsing) layout.scrollToPositionWithOffset(selected, 0)
        } else updateBottomFade()
        invalidate()
    }

    internal fun setControlsTapTarget(startY: Int, exclusive: Boolean, onTap: (() -> Unit)?) {
        tapTargetStartY = startY
        controlsTapExclusive = exclusive
        onControlsTap = onTap
    }

    private fun updateShareAvailability() {
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            val row = displayed.getOrNull(getChildAdapterPosition(child))
            child.isLongClickable = onShareLyrics != null && row?.lyric != null && song?.timing != "None"
        }
    }

    private fun updateViewport() {
        val h = height
        if (h <= 0) return
        fadeTopSize = (.07f * h).roundToInt()
        fadeBottomSize = ((.2775f * h).roundToInt() + controlsInset).coerceAtMost(h)
        fading.shader = LinearGradient(0f, 0f, 0f, fadeTopSize.coerceAtLeast(1).toFloat(),
            Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP)
        val fadeHeight = fadeBottomSize.coerceAtLeast(1).toFloat()
        bottomFadeFirstClear = (.24f * h / fadeHeight).coerceIn(0f, 1f)
        bottomFadeSecondClear = (.2775f * h / fadeHeight).coerceIn(bottomFadeFirstClear, 1f)
        updateBottomFade()
        val topAnchor = if (song?.timing == "None") 0 else focusOffset()
        setPadding(0, topAnchor, 0, fadeBottomSize)
    }

    private fun updateBottomFade() {
        if (height <= 0) return
        // W1 / k: fixed fade bounds; interpolate the shown and hidden palettes and stops.
        val hidden = 1f - controlsVisibility
        bottomFadeColors[1] = ((13f + 140f * hidden).toInt() shl 24)
        bottomFadeColors[2] = ((77f * hidden).toInt() shl 24)
        bottomFadeStops[1] = bottomFadeFirstClear + (.3f - bottomFadeFirstClear) * hidden
        bottomFadeStops[2] = bottomFadeSecondClear + (.6f - bottomFadeSecondClear) * hidden
        bottomFading.shader = LinearGradient(0f, (height - fadeBottomSize).toFloat(), 0f, height.toFloat(),
            bottomFadeColors, bottomFadeStops, Shader.TileMode.CLAMP)
    }


    init {
        layoutManager = layout
        adapter = ink
        itemAnimator = null // Native move timing includes position-dependent delays, not DefaultItemAnimator's easing.
        setHasFixedSize(true)
        overScrollMode = OVER_SCROLL_NEVER
        clipChildren = false
        clipToPadding = false
        isVerticalScrollBarEnabled = false
        addOnScrollListener(object : OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == SCROLL_STATE_DRAGGING) {
                    browsing = true
                    finishMoves()
                    isVerticalScrollBarEnabled = true
                    onUserScroll?.invoke()
                }
                updateScrollBarVisibility()
            }

            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                updateScrollBarVisibility()
                invalidate()
            }
        })
    }

    fun setSong(value: LyricSong?) {
        generation++
        song = value
        updateViewport()
        rows = makeRows(value)
        displayed = rows.filterNot { it.dots }
        selected = NO_POSITION
        selectedTailId = NO_POSITION
        selectedDuration = 750L
        emphasizedLyricIds.clear()
        candidateLyricIds.clear()
        lastPreparedCueBegin = Long.MIN_VALUE
        lastDispatched = Long.MIN_VALUE
        instant = true
        snapScaleChanges = true
        hasPlaybackAnchor = false
        seekFollowRequested = false
        browsing = false
        touch = false
        nextGapId = 0
        stopScroll()
        finishMoves()
        ink.notifyDataSetChanged()
        layout.scrollToPositionWithOffset(0, 0)
        updatePosition(force = true)
        updateScrollBarVisibility()
    }

    fun showAnnotations(translation: Boolean, pronunciation: Boolean) {
        if (this.translation == translation && this.pronunciation == pronunciation) return
        this.translation = translation
        this.pronunciation = pronunciation
        prepareResize()
        ink.notifyItemRangeChanged(0, displayed.size, AnnotationChange)
    }

    /** Uses the playback clock; physical scroll position is independent while a finger/fling owns the list. */
    fun sync(position: Long, playing: Boolean, discontinuity: Boolean) {
        refreshAnimationScale()
        val now = SystemClock.uptimeMillis()
        val projected = timeValue + if (this.playing) now - timeAt else 0L
        if (!hasPlaybackAnchor || discontinuity || this.playing != playing || kotlin.math.abs(projected - position) > 30) {
            timeValue = position.coerceAtLeast(0)
            timeAt = now
        }
        this.playing = playing
        if (discontinuity || !hasPlaybackAnchor) {
            // First load snaps; an in-song seek retargets from the rows' current visible positions.
            instant = !hasPlaybackAnchor || !isLaidOut || !isAttachedToWindow
            snapScaleChanges = snapScaleChanges || instant || !animate
            emphasizedLyricIds.clear()
            candidateLyricIds.clear()
            lastPreparedCueBegin = Long.MIN_VALUE
            lastDispatched = Long.MIN_VALUE
            seekFollowRequested = true
            browsing = false
            stopScroll()
            if (instant) finishMoves()
        }
        hasPlaybackAnchor = true
        updatePosition(force = true)
        if (playing && isAttachedToWindow) postInvalidateOnAnimation()
    }


    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        refreshAnimationScale()
        snapScaleChanges = true
        updatePosition(force = true)
    }

    private fun refreshAnimationScale() {
        val resolver = context.contentResolver
        val enabled = Settings.Global.getFloat(resolver, "transition_animation_scale", 1f) != 0f ||
            Settings.Global.getFloat(resolver, "window_animation_scale", 1f) != 0f
        if (enabled == animate) return
        animate = enabled
        snapScaleChanges = true
        if (!enabled) finishMoves()
        invalidate()
    }

    override fun stopNestedScroll(type: Int) {
        super.stopNestedScroll(type)
        if (type == ViewCompat.TYPE_TOUCH) onSheetScrollEnd?.invoke(0f, 0f)
    }

    override fun onDetachedFromWindow() {
        onSheetScrollEnd?.invoke(0f, 0f)
        sheetTouchTracker?.recycle()
        sheetTouchTracker = null
        super.onDetachedFromWindow()
    }

    private fun trackSheetTouch(event: MotionEvent) {
        val tracker = sheetTouchTracker ?: return
        // The sheet can move under the finger. Track in window coordinates.
        val dx = event.rawX - event.x
        val dy = event.rawY - event.y
        event.offsetLocation(dx, dy)
        try {
            tracker.addMovement(event)
        } finally {
            event.offsetLocation(-dx, -dy)
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        var sheetFinished = false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touch = true
                sheetTouchTracker?.recycle()
                sheetTouchTracker = if (isNestedScrollingEnabled && onSheetDragBy != null) VelocityTracker.obtain() else null
                lastRawTouchY = event.rawY
                trackSheetTouch(event)
                tapTargetDown = onControlsTap != null && tapTargetStartY >= 0 && event.y >= tapTargetStartY
                tapTargetMoved = false
                tapTargetDownX = event.x
                tapTargetDownY = event.y
                tapTargetDownAt = event.eventTime
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(event.x - tapTargetDownX) > touchSlop || abs(event.y - tapTargetDownY) > touchSlop) tapTargetMoved = true
                val rawY = event.rawY
                val delta = rawY - lastRawTouchY
                lastRawTouchY = rawY
                trackSheetTouch(event)
                if (hasSheetTouch && onSheetDragBy?.invoke(delta) == true) return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                tapTargetMoved = true
                onSheetScrollEnd?.invoke(0f, 0f)
                sheetTouchTracker?.recycle()
                sheetTouchTracker = null
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                touch = false
                parent?.requestDisallowInterceptTouchEvent(false)
                updateScrollBarVisibility()
                val tracker = sheetTouchTracker
                if (onSheetDragBy?.invoke(0f) == true) {
                    if (event.actionMasked == MotionEvent.ACTION_UP && tracker != null) {
                        trackSheetTouch(event)
                        tracker.computeCurrentVelocity(1000)
                        sheetFinished = onSheetScrollEnd?.invoke(tracker.xVelocity, tracker.yVelocity) == true
                    } else {
                        sheetFinished = onSheetScrollEnd?.invoke(0f, 0f) == true
                    }
                }
                tracker?.recycle()
                sheetTouchTracker = null
            }
        }
        val controlsTap = event.actionMasked == MotionEvent.ACTION_UP && tapTargetDown && !tapTargetMoved &&
            event.eventTime - tapTargetDownAt < ViewConfiguration.getLongPressTimeout()
        val handled = if (sheetFinished && event.actionMasked == MotionEvent.ACTION_UP) {
            // RecyclerView must end its touch scroll without starting a competing fling.
            event.action = MotionEvent.ACTION_CANCEL
            try {
                super.dispatchTouchEvent(event)
            } finally {
                event.action = MotionEvent.ACTION_UP
            }
        } else super.dispatchTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) tapTargetDown = false
        if (controlsTap && !sheetFinished) {
            onControlsTap?.invoke()
            return true
        }
        return handled
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        val intercepted = super.onInterceptTouchEvent(event)
        // Own the gesture from DOWN; a row may otherwise post its click after UP dispatch returns.
        return intercepted || (tapTargetDown && controlsTapExclusive)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateViewport()
        if (selected != NO_POSITION && !browsing) layout.scrollToPositionWithOffset(selected, 0)
    }

    override fun dispatchDraw(canvas: Canvas) {
        updatePosition()
        updateVisiblePresentation(snapScaleChanges)
        val layer = if (fading.shader != null) canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), viewportCompositePaint) else -1
        super.dispatchDraw(canvas)
        if (layer >= 0) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fading)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bottomFading)
            canvas.restoreToCount(layer)
        }
        if (playing && isAttachedToWindow) postInvalidateOnAnimation()
    }

    private fun updatePosition(force: Boolean = false) {
        val now = SystemClock.uptimeMillis()
        val projectedTime = timeValue + if (playing) now - timeAt else 0L
        currentTime = if (lastDispatched == Long.MIN_VALUE) projectedTime else maxOf(projectedTime, lastDispatched)
        if (!force && currentTime == lastDispatched) return
        val value = song ?: return
        if (isComputingLayout) return
        lastDispatched = currentTime
        val selectedId = displayed.getOrNull(selected)?.id ?: NO_POSITION
        val selectedLyricId = displayed.getOrNull(selected)?.takeIf { it.lyric != null }?.id ?: NO_POSITION
        var topologyReflow = false
        var topologySnapshotCaptured = false
        var topologyAnchorId = selectedLyricId
        var targetId = if (emphasizedLyricIds.size() > 0) selectedId else NO_POSITION
        var tailId = if (targetId != NO_POSITION) selectedTailId else NO_POSITION
        var duration = if (targetId != NO_POSITION) selectedDuration else 750L
        var latestPrepared: Row? = null
        var gap: Row? = null
        var credits: Row? = null
        var acceptedGroup = false
        val timed = hasPlaybackAnchor && value.timing != "None" && value.lines.isNotEmpty()
        if (timed) for (row in rows) {
            if (row.lyric != null) {
                val latest = latestPrepared
                if (currentTime >= row.presentationAt && (latest == null || row.cueBegin > latest.cueBegin ||
                    (row.cueBegin == latest.cueBegin && row.lyricIndex > latest.lyricIndex))) {
                    latestPrepared = row
                }
            } else if (row.dots) {
                if (currentTime >= row.begin && currentTime < row.end + 200L) gap = row
            } else if (currentTime >= row.presentationAt) credits = row
        }
        val prepared = latestPrepared
        if (gap != null && prepared != null && prepared.id > gap.id) gap = null
        val preparingGap = gap != null && currentTime >= gap.presentationAt
        val queryChanged = prepared != null && prepared.cueBegin != lastPreparedCueBegin
        if (prepared != null && queryChanged) lastPreparedCueBegin = prepared.cueBegin
        if (prepared != null && queryChanged && !preparingGap) {
            candidateLyricIds.clear()
            var hasBackground = false
            for (row in rows) {
                val lyric = row.lyric ?: continue
                if (currentTime >= row.presentationAt && (row.cueBegin == prepared.cueBegin ||
                    (row.cueBegin <= prepared.cueBegin && prepared.cueBegin < row.cueEnd))) {
                    candidateLyricIds.put(row.id, true)
                    if (lyric.background.text.isNotBlank() || lyric.background.tokens.isNotEmpty()) hasBackground = true
                }
            }
            // The APK's backing-line branch also needs word IDs unavailable in the local parser.
            if (value.timing == "Word" && hasBackground) {
                for (i in candidateLyricIds.size() - 1 downTo 0) {
                    val id = candidateLyricIds.keyAt(i)
                    if (id != prepared.id && !emphasizedLyricIds[id]) candidateLyricIds.delete(id)
                }
                candidateLyricIds.put(prepared.id, true)
            }
            var first: Row? = null
            var last: Row? = null
            var oldTailIndex = -1
            var previousIndex = -1
            var contiguous = true
            var intersects = false
            var hasAddition = false
            for (row in rows) {
                if (emphasizedLyricIds[row.id]) oldTailIndex = row.lyricIndex
                if (!candidateLyricIds[row.id]) continue
                if (first == null) first = row
                if (previousIndex >= 0 && row.lyricIndex != previousIndex + 1) contiguous = false
                previousIndex = row.lyricIndex
                last = row
                if (emphasizedLyricIds[row.id]) intersects = true else hasAddition = true
            }
            val groupFirst = first
            val groupLast = last
            // Non-empty subsets (including equality) leave the accepted group and its animations intact.
            if (hasAddition && groupFirst != null && groupLast != null) {
                targetId = if (intersects && !contiguous && groupLast.lyricIndex > oldTailIndex) {
                    groupLast.id
                } else groupFirst.id
                tailId = groupLast.id
                duration = groupLast.duration
                emphasizedLyricIds.clear()
                for (i in 0 until candidateLyricIds.size()) emphasizedLyricIds.put(candidateLyricIds.keyAt(i), true)
                acceptedGroup = true
            }
        }
        if (gap != null && currentTime >= gap.presentationAt) {
            if (selectedId != gap.id) {
                emphasizedLyricIds.clear()
                acceptedGroup = true
            }
            targetId = gap.id
            tailId = gap.id
            duration = gap.duration
        }
        if (credits != null) {
            targetId = credits.id
            tailId = credits.id
            duration = credits.duration
        }
        val gapId = gap?.id ?: 0
        if (gapId != nextGapId) {
            topologyReflow = true
            topologyAnchorId = if (gap != null && currentTime >= gap.presentationAt) gap.id else selectedLyricId
            val canPreserveTopology = animate && !instant && isLaidOut && isAttachedToWindow &&
                !touch && !browsing && scrollState == SCROLL_STATE_IDLE
            if (canPreserveTopology) {
                captureMoveSnapshot()
                topologySnapshotCaptured = true
            } else {
                finishMoves()
            }
            val old = displayed.indexOfFirst { it.id == nextGapId }
            if (old >= 0) {
                displayed = displayed.toMutableList().apply { removeAt(old) }
                ink.notifyItemRemoved(old)
            }
            if (gap != null) {
                var at = 0
                for (row in rows) {
                    if (row.id == gapId) break
                    if (!row.dots) at++
                }
                displayed = displayed.toMutableList().apply { add(at, gap) }
                ink.notifyItemInserted(at)
            }
            nextGapId = gapId
            selected = displayed.indexOfFirst { it.id == selectedId }
            if (credits == null) {
                if (gap != null && currentTime >= gap.presentationAt) targetId = gap.id
                else if (topologyAnchorId != NO_POSITION) targetId = topologyAnchorId
            }
        }
        val targetIndex = if (targetId == NO_POSITION) NO_POSITION else displayed.indexOfFirst { it.id == targetId }
        if (timed && (acceptedGroup || topologyReflow || targetIndex != selected || instant || seekFollowRequested)) {
            val visibleActive = isActiveVisible()
            val snap = instant || (topologyReflow && !topologySnapshotCaptured)
            selected = targetIndex
            selectedTailId = if (targetIndex == NO_POSITION) NO_POSITION else tailId
            selectedDuration = if (targetIndex == NO_POSITION) 750L else duration
            val targetVisible = targetIndex != NO_POSITION && isAdapterPositionVisible(targetIndex)
            if (selected != NO_POSITION && !touch && scrollState == SCROLL_STATE_IDLE && (!browsing || targetVisible || snap)) {
                browsing = false
                moveTo(selected, snap, preserveCapturedPositions = topologySnapshotCaptured)
                snapScaleChanges = snapScaleChanges || snap || !animate
                seekFollowRequested = false
            }
        }
        instant = false
        updateScrollBarVisibility()
        if (force) invalidate()
    }

    private fun presentationLayoutReady() = isLaidOut && !isComputingLayout && !isLayoutRequested &&
        pendingMovePosition == NO_POSITION && !pendingResize

    private fun updateVisiblePresentation(snap: Boolean) {
        val now = SystemClock.uptimeMillis()
        for (i in 0 until childCount) {
            val view = getChildAt(i) as LyricInkView
            val row = displayed.getOrNull(getChildAdapterPosition(view)) ?: continue
            updateInk(view, row, snap, now)
        }
        if (moveEndAt > 0L) {
            if (now >= moveEndAt) {
                moveEndAt = 0L
                clearMoveSpace()
            } else postInvalidateOnAnimation()
        }
        // A bind or a backing height change may have requested layout while applying the presentation.
        if (presentationLayoutReady()) snapScaleChanges = false
    }

    private fun updateInk(view: LyricInkView, row: Row, snap: Boolean, now: Long) {
        val emphasized = row.lyric != null && emphasizedLyricIds[row.id]
        view.layoutMotionDuration = selectedDuration
        val paintChanged = view.position != currentTime || view.reduceMotion != !animate
        view.position = currentTime
        view.reduceMotion = !animate
        val deferRetirement = animate && !touch && !browsing && scrollState == SCROLL_STATE_IDLE &&
            (pendingMovePosition != NO_POSITION || now < view.lineMoveNotBefore)
        view.setLinePresentation(emphasized, snap || !animate, now, deferRetirement)
        view.interludeStart = row.begin
        view.interludeEnd = row.end
        if (paintChanged) view.invalidate()
    }

    private fun captureMoveSpace() {
        var top = 0f
        var bottom = height.toFloat()
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val visualTop = child.top + child.translationY
            val visualBottom = child.bottom + child.translationY
            // Ignore prior extra-layout children; repeated line moves must not grow the envelope.
            if (visualBottom > 0f && visualTop < height) {
                top = minOf(top, visualTop)
                bottom = maxOf(bottom, visualBottom)
            }
        }
        moveExtraPx = maxOf(height, ceil(bottom - top).toInt())
        moveEndAt = 0L
    }
    private fun captureMoveSnapshot() {
        captureMoveSpace()
        positionsBeforeMove.clear()
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            positionsBeforeMove.put(getChildViewHolder(child).itemId.toInt(), (child.top + child.translationY).roundToInt())
        }
    }

    private fun clearMoveSpace() {
        if (moveExtraPx == 0) return
        moveExtraPx = 0
        requestLayout()
    }

    private fun prepareResize() {
        if (!animate || !isLaidOut || !isAttachedToWindow || !hasPlaybackAnchor || pendingResize ||
            pendingMovePosition != NO_POSITION) return
        captureMoveSnapshot()
        for (i in 0 until childCount) {
            val child = getChildAt(i) as LyricInkView
            child.animate().cancel()
            child.lineMoveNotBefore = 0L
        }
        pendingResize = true
        pendingMoveDuration = selectedDuration
    }

    private fun moveTo(position: Int, snap: Boolean, preserveCapturedPositions: Boolean = false) {
        if (position == NO_POSITION || position !in displayed.indices) return
        val useMotion = animate && !snap && isLaidOut && isAttachedToWindow
        val hasCapturedPositions = preserveCapturedPositions && positionsBeforeMove.size() > 0
        pendingResize = false
        if (useMotion) {
            if (!hasCapturedPositions) captureMoveSnapshot()
        } else {
            positionsBeforeMove.clear()
            moveEndAt = 0L
            clearMoveSpace()
        }
        for (i in 0 until childCount) {
            val child = getChildAt(i) as LyricInkView
            child.animate().cancel()
            child.lineMoveNotBefore = 0L
            if (!useMotion) child.translationY = 0f
        }
        pendingMovePosition = if (useMotion) position else NO_POSITION
        pendingMoveDuration = selectedDuration
        layout.scrollToPositionWithOffset(position, 0)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val position = pendingMovePosition
        val resizing = pendingResize
        val duration = pendingMoveDuration
        // Binds during super still see the pending move and defer outgoing presentation.
        if (position != NO_POSITION || resizing) for (i in 0 until childCount) getChildAt(i).translationY = 0f
        super.onLayout(changed, l, t, r, b)
        if (position != NO_POSITION) pendingMovePosition = NO_POSITION
        if (resizing) pendingResize = false
        if (position == NO_POSITION && !resizing) return
        if (touch || browsing || scrollState != SCROLL_STATE_IDLE) {
            finishMoves()
            return
        }
        val tailIndex = displayed.indexOfFirst { it.id == selectedTailId }
        val staggerFrom = if (tailIndex == NO_POSITION) selected else tailIndex
        var fallback = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val key = positionsBeforeMove.indexOfKey(getChildViewHolder(child).itemId.toInt())
            if (key >= 0) { fallback = positionsBeforeMove.valueAt(key) - child.top; break }
        }
        val now = SystemClock.uptimeMillis()
        var lastDelay = 0L
        var moving = false
        for (i in 0 until childCount) {
            val child = getChildAt(i) as LyricInkView
            child.lineMoveNotBefore = 0L
            val p = getChildAdapterPosition(child)
            if (p == NO_POSITION) continue
            child.translationY = (positionsBeforeMove.get(getChildViewHolder(child).itemId.toInt(), child.top + fallback) - child.top).toFloat()
            if (child.translationY != 0f) {
                val delay = LyricMotion.stagger(p - staggerFrom)
                lastDelay = maxOf(lastDelay, delay)
                moving = true
                if (position != NO_POSITION) child.lineMoveNotBefore = now + delay
                child.animate().translationY(0f).setInterpolator(LyricMotion.line)
                    .setDuration(duration).setStartDelay(delay).start()
            }
        }
        positionsBeforeMove.clear()
        moveEndAt = if (moving) now + duration + lastDelay else 0L
        if (moving) postInvalidateOnAnimation() else clearMoveSpace()
    }

    private fun finishMoves() {
        pendingMovePosition = NO_POSITION
        pendingResize = false
        moveEndAt = 0L
        positionsBeforeMove.clear()
        clearMoveSpace()
        for (i in 0 until childCount) (getChildAt(i) as LyricInkView).apply {
            animate().cancel()
            translationY = 0f
            lineMoveNotBefore = 0L
        }
    }

    private fun isActiveVisible(): Boolean {
        if (song?.timing == "None") return false
        if (isAdapterPositionVisible(selected) || isAdapterPositionVisible(displayed.indexOfFirst { it.id == selectedTailId })) return true
        val first = layout.findFirstVisibleItemPosition()
        val last = layout.findLastVisibleItemPosition()
        return first != NO_POSITION && last != NO_POSITION && (first..last).any { displayed.getOrNull(it)?.dots == true }
    }

    private fun isAdapterPositionVisible(position: Int): Boolean {
        if (position !in displayed.indices) return false
        val first = layout.findFirstVisibleItemPosition()
        val last = layout.findLastVisibleItemPosition()
        return first != NO_POSITION && last != NO_POSITION && position in first..last
    }

    private fun updateScrollBarVisibility() {
        val keepVisible = browsing && !touch && scrollState == SCROLL_STATE_IDLE && song?.timing != "None" && !isActiveVisible()
        if (scrollState == SCROLL_STATE_IDLE && !keepVisible) isVerticalScrollBarEnabled = false
    }

    private fun focusOffset() = (.08f * height).roundToInt().minus(dp(2f)).coerceAtLeast(0)
    private fun dp(value: Float) = (value * resources.displayMetrics.density).roundToInt()

    private fun makeRows(song: LyricSong?): List<Row> = buildList {
        if (song == null) return@buildList
        val cueBegins = LongArray(song.lines.size)
        val cueEnds = LongArray(song.lines.size)
        for ((index, lyric) in song.lines.withIndex()) {
            val begin = lyric.paragraphBegin
            val end = lyric.paragraphEnd
            val paragraphTimed = begin != null && end != null && end > begin
            cueBegins[index] = if (paragraphTimed) begin!! else lyric.begin
            cueEnds[index] = if (paragraphTimed) end!! else lyric.end
        }
        val timingOrder = song.lines.indices.sortedWith(compareBy<Int> { cueBegins[it] }.thenBy { it })
        val durations = LongArray(song.lines.size)
        val presentationTimes = LongArray(song.lines.size)
        var lead = 500L
        for (order in timingOrder.indices) {
            val index = timingOrder[order]
            val nextIndex = timingOrder.getOrNull(order + 1)
            durations[index] = if (song.timing == "Word") {
                LyricMotion.lineDuration(if (nextIndex == null) 0L else cueBegins[nextIndex] - cueEnds[index])
            } else 750L
            if (order > 0 && song.timing == "Word") {
                val previous = timingOrder[order - 1]
                if (cueBegins[index] != cueBegins[previous]) lead = durations[previous]
            }
            presentationTimes[index] = (cueBegins[index] - lead).coerceAtLeast(0L)
        }
        var end = 0L
        var id = 1
        for ((index, lyric) in song.lines.withIndex()) {
            val gap = lyric.begin - end
            if (song.timing != "None" && gap >= LyricMotion.interludeMinimum) {
                val gapPresentationAt = if (index == 0) end else end + 300L
                add(Row(id++, end, lyric.begin, gapPresentationAt, 750, dots = true))
            }
            add(Row(id++, lyric.begin, lyric.end, presentationTimes[index], durations[index], lyric,
                lyricIndex = index, cueBegin = cueBegins[index], cueEnd = cueEnds[index]))
            end = maxOf(end, lyric.end)
        }
        val creditNames = song.credits.filter { it.isNotBlank() }
        val credits = creditNames.takeIf { it.isNotEmpty() }?.joinToString(", ")
            ?.let { "创作者：$it" }.orEmpty()
        val attribution = if (song.timing == "None") "" else when {
            song.translationAutomatic && song.pronunciationAutomatic -> "Automatically created translation and pronunciation"
            song.translationAutomatic -> "Automatically created translation"
            song.pronunciationAutomatic -> "Automatically created pronunciation"
            else -> ""
        }
        if (credits.isNotEmpty() || attribution.isNotEmpty()) {
            add(Row(id, end + 1000, Long.MAX_VALUE, end + 1000, 850, credits = credits, attribution = attribution))
        }
    }


    private fun shareFrom(row: Row) {
        val value = song ?: return
        val callback = onShareLyrics ?: return
        val sourceIndex = row.lyricIndex
        if (value.timing == "None" || sourceIndex !in value.lines.indices) return
        val eligible = ArrayList<Pair<Int, LyricShareLine>>(value.lines.size)
        for ((index, line) in value.lines.withIndex()) {
            if (line.main.text.isBlank() || line.begin < 0 || line.end < line.begin) continue
            eligible += index to LyricShareLine(line.main.text, line.begin)
        }
        val tappedIndex = eligible.indexOfFirst { it.first == sourceIndex }
        if (tappedIndex < 0) return
        callback(eligible.map { it.second }, tappedIndex)
    }

    private inner class InkAdapter : Adapter<ViewHolder>() {
        init { setHasStableIds(true) }
        override fun getItemCount() = displayed.size
        override fun getItemId(position: Int) = displayed[position].id.toLong()
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LyricInkView(parent.context)
            view.onContentLayoutChanged = ::prepareResize
            view.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            return object : ViewHolder(view) {}
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
            if (payloads.isNotEmpty() && payloads.all { it === AnnotationChange }) {
                val row = displayed[position]
                val view = holder.itemView as LyricInkView
                view.bind(row.lyric, song?.timing ?: "None", row.credits, row.attribution, row.dots, translation, pronunciation)
                updateInk(view, row, snapScaleChanges, SystemClock.uptimeMillis())
                return
            }
            onBindViewHolder(holder, position)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val view = holder.itemView as LyricInkView
            val row = displayed[position]
            val serial = generation
            val timing = song?.timing ?: "None"
            view.animate().cancel()
            view.translationY = 0f
            view.lineMoveNotBefore = 0L
            view.layoutParams = (view.layoutParams as LayoutParams).apply {
                topMargin = if (timing == "None" && position == 0) dp(30f) else 0
                bottomMargin = if (position == itemCount - 1) 0 else dp(if (timing == "None") 40f else 20f)
            }
            view.bind(row.lyric, timing, row.credits, row.attribution, row.dots, translation, pronunciation)
            if (row.lyric != null) {
                view.setOnLongClickListener {
                    if (!view.rowActivationAllowed) return@setOnLongClickListener false
                    if (serial == generation) shareFrom(row)
                    true
                }
                view.isLongClickable = onShareLyrics != null && timing != "None"
                view.setOnClickListener {
                    if (view.rowActivationAllowed && serial == generation && timing != "None") {
                        onSeek(row.begin)
                        sync(row.begin, playing, true)
                    }
                }
                view.isClickable = timing != "None"
            } else {
                view.setOnClickListener(null)
                view.setOnLongClickListener(null)
                view.isClickable = false
                view.isLongClickable = false
            }
            updateInk(view, row, snapScaleChanges, SystemClock.uptimeMillis())
        }
    }
}
