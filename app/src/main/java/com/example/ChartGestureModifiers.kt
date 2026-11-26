package com.example

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * Interactive chart gesture modifier that coordinates cleanly with parent [androidx.compose.foundation.lazy.LazyColumn]
 * and [androidx.compose.foundation.pager.HorizontalPager]:
 * - When [enableEdgePagerPassThrough] is true, touches starting within [edgePassThroughDp] of the
 *   left/right edge immediately pass through to the parent pager for effortless page swiping.
 * - Vertical drags (|dy| >= |dx| once touch slop is exceeded) yield without consuming so vertical
 *   scrolling never stalls on top of full-width charts.
 * - Quick taps invoke [onTap] without interfering with swipes or scrolls.
 * - Intentional horizontal drags inside the chart area invoke [onScrubStart]/[onScrubMove]/[onScrubEnd].
 */
fun Modifier.chartScrubGestures(
    vararg keys: Any?,
    enabled: Boolean = true,
    enableEdgePagerPassThrough: Boolean = false,
    edgePassThroughDp: Dp = 28.dp,
    onTap: ((Offset, IntSize) -> Unit)? = null,
    onScrubStart: (Offset, IntSize) -> Unit,
    onScrubMove: (Offset, IntSize) -> Unit,
    onScrubEnd: () -> Unit = {},
    onScrubCancel: () -> Unit = onScrubEnd
): Modifier = composed {
    val currentEnabled by rememberUpdatedState(enabled)
    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnScrubStart by rememberUpdatedState(onScrubStart)
    val currentOnScrubMove by rememberUpdatedState(onScrubMove)
    val currentOnScrubEnd by rememberUpdatedState(onScrubEnd)
    val currentOnScrubCancel by rememberUpdatedState(onScrubCancel)

    this.pointerInput(*keys, enableEdgePagerPassThrough, edgePassThroughDp) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (!currentEnabled) return@awaitEachGesture

            val edgePx = if (enableEdgePagerPassThrough) edgePassThroughDp.toPx() else 0f
            val inEdgeZone = enableEdgePagerPassThrough &&
                size.width > (edgePx * 2.5f) &&
                (down.position.x <= edgePx || down.position.x >= size.width - edgePx)

            val slop = viewConfiguration.touchSlop
            var decided = false
            var isHorizontalScrub = false

            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: event.changes.firstOrNull()
                if (change == null) {
                    if (isHorizontalScrub) currentOnScrubCancel()
                    return@awaitEachGesture
                }

                if (!change.pressed) {
                    if (isHorizontalScrub) {
                        change.consume()
                        currentOnScrubEnd()
                    } else if (!decided && !change.isConsumed) {
                        // Released before exceeding touch slop -> treat as quick tap
                        val tapHandler = currentOnTap
                        if (tapHandler != null) {
                            change.consume()
                            tapHandler(change.position, size)
                        }
                    }
                    return@awaitEachGesture
                }

                if (!decided) {
                    val dx = change.position.x - down.position.x
                    val dy = change.position.y - down.position.y
                    val absDx = abs(dx)
                    val absDy = abs(dy)
                    if (absDx > slop || absDy > slop) {
                        decided = true
                        if (inEdgeZone || absDy >= absDx) {
                            // Edge horizontal swipe (for HorizontalPager) OR vertical scroll (for LazyColumn)
                            return@awaitEachGesture
                        }
                        isHorizontalScrub = true
                        change.consume()
                        currentOnScrubStart(change.position, size)
                    }
                } else {
                    if (change.isConsumed) {
                        currentOnScrubCancel()
                        return@awaitEachGesture
                    }
                    if (change.positionChanged()) {
                        change.consume()
                        currentOnScrubMove(change.position, size)
                    }
                }
            }
        }
    }
}
