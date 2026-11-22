import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
    bottomBar: @Composable (PlayerSheetFrame, Boolean) -> Unit,
        if (!trackPresent) return
        if (activeDrag == null && currentDetent == target && slide == target.slide) {
            if (targetDetent != target || settleJob?.isActive == true) {
                cancelSettlement()
                targetDetent = target
            }
            notifyExpanded(target == PlayerSheetDetent.Expanded)
            return
        }
        val currentFrame = renderFrame.value
        val start = currentFrame.topAt(slide)
        val targetTop = target.top(currentFrame)
            targetDetent = target
                if (trackPresent && generation == settleGeneration) {
                    slide = renderFrame.value.slideAt(value, target)
                }
        if (trackPresent && chromeVisible && (targetDetent != PlayerSheetDetent.Expanded || activeDrag != null)) {
    val sheetAtDetent = slide == PlayerSheetDetent.Expanded.slide ||
        slide == PlayerSheetDetent.Collapsed.slide || slide == PlayerSheetDetent.Hidden.slide
    val chromeInteractive = chromeVisible &&
        slide == PlayerSheetDetent.Collapsed.slide &&
        currentDetent == PlayerSheetDetent.Collapsed &&
        targetDetent == PlayerSheetDetent.Collapsed &&
        activeDrag == null && settleJob?.isActive != true
    BackHandler(enabled = trackPresent && chromeVisible &&
        (!sheetAtDetent || activeDrag != null || settleJob?.isActive == true ||
            targetDetent == PlayerSheetDetent.Expanded)) {
        val currentFrame = renderFrame.value
            topPx = currentFrame.topAt(slide),
            expandedTopPx = currentFrame.expandedTopPx,
            collapsedTopPx = currentFrame.collapsedTopPx,
            hiddenTopPx = currentFrame.hiddenTopPx,
            sheetRangePx = currentFrame.hiddenTopPx - currentFrame.collapsedTopPx,
    val topNow by rememberUpdatedState { renderFrame.value.topAt(slide) }
    val fullVisibleNow by rememberUpdatedState { trackPresent && chromeVisible && slide > 0f }
    val partialSlideNow by rememberUpdatedState {
        slide != PlayerSheetDetent.Expanded.slide &&
            slide != PlayerSheetDetent.Collapsed.slide &&
            slide != PlayerSheetDetent.Hidden.slide
    }
            settleGeneration.also { activeDrag = it }
        val currentFrame = renderFrame.value
        val from = currentFrame.topAt(slide)
        val to = (from + delta).coerceIn(currentFrame.expandedTopPx, currentFrame.hiddenTopPx)
        slide = currentFrame.slideAt(to, currentDetent)
    val finishDrag by rememberUpdatedState<(Int, Velocity) -> Boolean> { token, velocity ->
        if (activeDrag != token) {
            false
        } else {
            true
            private var preFlingGeneration = -1
                skipPostFling = false
                preFlingGeneration = settleGeneration
                activeDrag = null
                    startDrag()?.also {
                        drag = it
                        skipPostFling = false
                    } ?: return Offset.Zero
                if (token != activeDrag) {
                    drag = null
                    return Offset.Zero
                }
                return Offset(0f, dragBy(delta))
                if (token != null && finishDrag(token, available)) return true
                if (activeDrag != null || settleJob?.isActive == true ||
                    !fullVisibleNow() || !childAtTop() || !partialSlideNow()) return false
                releaseSheet(available.x, available.y)
                val generation = settleGeneration
                preFlingGeneration = generation
                skipPostFling = settled
                if (!canSettle) return Velocity.Zero
                return if (settle(available)) available else Velocity.Zero
                        velocityTracker.addPointerInputChange(change)
                    if (drag == null) return@awaitEachGesture
                    val activeEnded = active.changedToUpIgnoreConsumed() || !active.pressed
                    if (activeEnded) {
                        velocity = velocityTracker.calculateVelocity()
                        if (active.changedToUpIgnoreConsumed() && !active.isConsumed) active.consume()
                    } else {
                        while (drag == activeDrag) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == active.id } ?: break
                            velocityTracker.addPointerInputChange(change)
                            if (change.changedToUpIgnoreConsumed()) {
                                velocity = velocityTracker.calculateVelocity()
                                if (!change.isConsumed) change.consume()
                                break
                            }
                            if (!change.pressed) {
                                velocity = velocityTracker.calculateVelocity()
                                break
                            }
                            if (drag != activeDrag) break
                            val delta = change.positionChangeIgnoreConsumed().y
                            if (delta != 0f) {
                                dragBy(delta)
                                change.consume()
                            }
                        }
                    drag?.let { finishDrag(it, velocity) }
                    enabled = chromeInteractive,
            Box(Modifier.padding(horizontal = Dimens.Page)) { bottomBar(frame, chromeInteractive) }