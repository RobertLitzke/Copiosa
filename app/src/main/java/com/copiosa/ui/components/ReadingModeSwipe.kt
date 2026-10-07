package com.copiosa.ui.components

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * Flips a reader between its language versions on a horizontal swipe, so switching costs a flick of
 * the thumb where the text already is rather than a reach for the control at the bottom of the
 * screen. Direction follows the segmented toggle it shadows: swiping left advances a step to the
 * right along it, swiping right goes back. [onStep] is called with +1 / -1, and [canStep] says
 * whether that step leads anywhere — at either end of the control the gesture is inert, and the cue
 * below stays away rather than promising something that won't happen.
 *
 * Vertical scrolling is unaffected (this only claims a pointer once it has moved sideways past touch
 * slop), and a selection drag already in progress consumes its own movement, so dragging a selection
 * handle sideways doesn't flip the page out from under it.
 *
 * A drag that crosses horizontal slop is still only a *candidate* swipe: a scroll that happened to
 * start sideways would otherwise keep accumulating horizontal travel while the list scrolled under
 * it, arm the cue, and flip the page on release. So the drag is abandoned the moment it has covered
 * more ground vertically than horizontally (past a slop that tolerates the wander in a real flick),
 * and once the swipe *will* land it consumes its movement so the list can't fling on the same
 * gesture that flipped the page.
 */
@Composable
fun Modifier.readingModeSwipe(
    canStep: (Int) -> Boolean = { true },
    onStep: (Int) -> Unit,
): Modifier {
    val step by rememberUpdatedState(onStep)
    val allowed by rememberUpdatedState(canStep)
    val drag = remember { SwipeDrag() }
    val cueColor = MaterialTheme.colorScheme.primary
    return this
        .pointerInput(Unit) {
            val threshold = SWIPE_DISTANCE.toPx()
            val verticalSlop = VERTICAL_SLOP.toPx()
            // Armed = this drag would land a step if the finger came up now, which is both when the
            // cue reads as full and when the gesture is ours to own.
            fun armed(): Boolean {
                val travelled = drag.travelled
                return abs(travelled) >= threshold && allowed(if (travelled < 0f) 1 else -1)
            }
            detectHorizontalDragGestures(
                onDragStart = { drag.reset() },
                onDragCancel = { drag.reset() },
                onDragEnd = {
                    val travelled = drag.travelled
                    drag.reset()
                    val direction = if (travelled < 0f) 1 else -1
                    if (abs(travelled) >= threshold && allowed(direction)) step(direction)
                },
            ) { change, delta ->
                drag.onDrag(delta, change.position, change.previousPosition, verticalSlop)
                if (armed()) change.consume()
            }
        }
        .drawWithContent {
            drawContent()
            val travelled = drag.travelled
            if (travelled == 0f) return@drawWithContent
            val direction = if (travelled < 0f) 1 else -1
            if (!allowed(direction)) return@drawWithContent
            drawSwipeCue(
                progress = (abs(travelled) / SWIPE_DISTANCE.toPx()).coerceIn(0f, 1f),
                fromRightEdge = travelled < 0f,
                atY = drag.y,
                color = cueColor,
                maxRadius = CUE_RADIUS.toPx(),
            )
        }
}

/**
 * The swipe's live state, held outside composition: both the gesture handler and the cue's draw
 * read it, so a drag repaints the cue without recomposing a word of the text under it.
 */
@Stable
private class SwipeDrag {
    /** Net sideways displacement, held at zero once the drag has been abandoned as a scroll. */
    var travelled by mutableFloatStateOf(0f)
        private set
    var y by mutableFloatStateOf(0f)
        private set

    // Ground covered on each axis (absolute, so a wobble counts against the gesture rather than
    // cancelling itself out). A page flip is nearly all horizontal; a scroll is not.
    private var horizontal = 0f
    private var vertical = 0f

    /** Set once the drag has proved to be a scroll. A gesture never recovers from it. */
    private var abandoned = false

    fun onDrag(delta: Float, position: Offset, previous: Offset, verticalSlop: Float) {
        if (abandoned) return
        y = position.y
        horizontal += abs(delta)
        vertical += abs(position.y - previous.y)
        // The slop is what lets a real flick wander a little; past it, whichever axis has covered
        // more ground says what the gesture is.
        if (vertical > verticalSlop && vertical > horizontal) {
            abandoned = true
            travelled = 0f
            return
        }
        travelled += delta
    }

    fun reset() {
        travelled = 0f
        // Reset too, so the first frame of the next drag can't paint the cue at this one's height.
        y = 0f
        horizontal = 0f
        vertical = 0f
        abandoned = false
    }
}

/**
 * A half-disc swelling out of the edge the swipe came from, tracking the finger's height and growing
 * with the drag until, at full size, it says the gesture will land if released. Kept very faint: it
 * is confirmation that the reader is being heard, not a thing to look at while reading.
 */
private fun ContentDrawScope.drawSwipeCue(
    progress: Float,
    fromRightEdge: Boolean,
    atY: Float,
    color: Color,
    maxRadius: Float,
) {
    val radius = maxRadius * progress
    if (radius <= 0f) return
    val centerX = if (fromRightEdge) size.width else 0f
    drawArc(
        color = color.copy(alpha = CUE_ALPHA_BASE + CUE_ALPHA_RANGE * progress),
        // Sweeping away from the edge, so only the half that bulges into the page is drawn.
        startAngle = if (fromRightEdge) 90f else -90f,
        sweepAngle = 180f,
        useCenter = true,
        topLeft = Offset(centerX - radius, atY - radius),
        size = Size(radius * 2f, radius * 2f),
    )
}

/**
 * Far enough that it can't be triggered by the sideways wander in a vertical scroll, short enough to
 * stay a flick — about a sixth of a phone's width.
 */
private val SWIPE_DISTANCE = 58.dp

/**
 * How far a candidate swipe may wander vertically before the two axes are compared at all. Below
 * this it is all noise — the arc a thumb traces across a screen is never perfectly flat.
 */
private val VERTICAL_SLOP = 24.dp

/** The cue at full swell. Wide enough to register peripherally, small enough not to cover text. */
private val CUE_RADIUS = 56.dp

// Barely-there at the start of a drag, still translucent when armed.
private const val CUE_ALPHA_BASE = 0.04f
private const val CUE_ALPHA_RANGE = 0.14f
