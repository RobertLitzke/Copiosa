package com.copiosa.ui.components

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.isSpecified
import com.copiosa.ui.theme.LocalReadingStyle
import com.copiosa.ui.theme.skin.FocusDecoration
import com.copiosa.ui.theme.skin.FocusWordStyle
import com.copiosa.ui.theme.skin.LocalAppSkin
import com.copiosa.ui.theme.skin.focusWordStyle

/**
 * Renders an assistant message as selectable reading text. A long-press selects the word under the
 * finger; dragging (without lifting) extends the selection word-by-word so a phrase can be picked.
 * The translation card opens on *release* with the final selection — so a multi-word phrase is one
 * smooth gesture and the card isn't yanked open on every intermediate word. The highlight is drawn
 * as a background span and *stays put* after the finger lifts (it lives in this composable's state,
 * independent of focus), so the card can sit on top without the selection vanishing.
 *
 * [onSelected] reports the chosen text plus the vertical center of the selection in window pixels,
 * letting the card place itself on the opposite half of the screen so it never covers the word.
 *
 * [isActive] is false when another message owns the selection or the card was dismissed; flipping
 * it false clears this message's highlight so only one selection shows at a time.
 */
@Composable
fun SelectableMessage(
    content: String,
    colorizer: WordColorizer,
    isActive: Boolean,
    onSelected: (String, Float) -> Unit,
    onCleared: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Overrides the reading-font style (persona chat skins bring their own type). The reading-size
     * preference still scales it — the persona sets the face and proportions, the user keeps
     * control of legibility.
     */
    styleOverride: TextStyle? = null,
) {
    val reading = LocalReadingStyle.current
    val annotated = rememberSelectableMessage(content, colorizer, reading.scale)
    val base = styleOverride ?: MaterialTheme.typography.bodyLarge.copy(fontFamily = reading.fontFamily)
    val style = base.copy(
        fontSize = if (base.fontSize.isSpecified) base.fontSize * reading.scale else base.fontSize,
        lineHeight = if (base.lineHeight.isSpecified) base.lineHeight * reading.scale else base.lineHeight,
    )
    val highlight = LocalTextSelectionColors.current.backgroundColor

    // Persona focus-word decorations (wavy underline, dashed box, pill) can't live in a SpanStyle,
    // so they're drawn over the text layout. The decorated ranges are exactly the spans styled with
    // the colorizer's savedStyle. NONE (the stock skin) costs nothing.
    val focus = focusWordStyle(LocalAppSkin.current)
    val decoratedRanges = remember(annotated, colorizer, focus) {
        if (focus.decoration == FocusDecoration.NONE || colorizer.isEmpty) {
            emptyList()
        } else {
            annotated.spanStyles.filter { it.item == colorizer.savedStyle }.map { it.start to it.end }
        }
    }

    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var topInWindow by remember { mutableStateOf(0f) }
    var selection by remember(content) { mutableStateOf(TextRange.Zero) }

    LaunchedEffect(isActive) { if (!isActive) selection = TextRange.Zero }

    fun report() {
        val len = annotated.text.length
        val sub = annotated.text.substring(selection.min.coerceIn(0, len), selection.max.coerceIn(0, len))
        if (sub.isBlank()) {
            onCleared()
            return
        }
        // Vertical center of the selection in window space, so the card can dodge it.
        val box = layout?.takeIf { len > 0 }?.getBoundingBox(selection.min.coerceIn(0, len - 1))
        val centerY = topInWindow + (box?.let { (it.top + it.bottom) / 2f } ?: 0f)
        onSelected(sub, centerY)
    }

    val displayed = remember(annotated, selection, highlight) {
        if (selection.collapsed) {
            annotated
        } else {
            buildAnnotatedString {
                append(annotated)
                addStyle(SpanStyle(background = highlight), selection.min, selection.max)
            }
        }
    }

    Text(
        text = displayed,
        style = style,
        onTextLayout = { layout = it },
        modifier = modifier
            .fillMaxWidth()
            .onGloballyPositioned { topInWindow = it.positionInWindow().y }
            .drawBehind {
                val l = layout
                if (decoratedRanges.isNotEmpty() && l != null) {
                    drawFocusDecorations(l, decoratedRanges, focus)
                }
            }
            .pointerInput(annotated) {
                var anchor = TextRange.Zero
                detectDragGesturesAfterLongPress(
                    onDragStart = { pos ->
                        layout?.let { l ->
                            anchor = l.getWordBoundary(l.getOffsetForPosition(pos))
                            selection = anchor
                        }
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        layout?.let { l ->
                            val word = l.getWordBoundary(l.getOffsetForPosition(change.position))
                            selection = TextRange(minOf(anchor.min, word.min), maxOf(anchor.max, word.max))
                        }
                    },
                    // Open/refresh the card once the gesture finishes, with the full selection.
                    onDragEnd = { report() },
                    onDragCancel = { report() },
                )
            },
    )
}

/**
 * Draws the persona's focus-word decoration under each highlighted range, one segment per wrapped
 * line so multi-line spans decorate correctly.
 */
private fun DrawScope.drawFocusDecorations(
    layout: TextLayoutResult,
    ranges: List<Pair<Int, Int>>,
    focus: FocusWordStyle,
) {
    val maxOffset = layout.layoutInput.text.length
    for ((rawStart, rawEnd) in ranges) {
        val start = rawStart.coerceIn(0, maxOffset)
        val end = rawEnd.coerceIn(0, maxOffset)
        if (end <= start) continue
        val firstLine = layout.getLineForOffset(start)
        val lastLine = layout.getLineForOffset(end - 1)
        for (line in firstLine..lastLine) {
            val left = if (line == firstLine) layout.getHorizontalPosition(start, true) else layout.getLineLeft(line)
            val right = if (line == lastLine) layout.getHorizontalPosition(end, true) else layout.getLineRight(line)
            if (right <= left) continue
            val segment = Rect(left, layout.getLineTop(line), right, layout.getLineBottom(line))
            when (focus.decoration) {
                FocusDecoration.WAVY_UNDERLINE -> drawWavyUnderline(segment, focus)
                FocusDecoration.DASHED_BOX -> drawWordBox(segment, focus, dashed = true)
                FocusDecoration.OUTLINED_PILL -> drawWordBox(segment, focus, dashed = false)
                FocusDecoration.NONE -> {}
            }
        }
    }
}

/** The Artist's sketchy sine underline just below the descender line. */
private fun DrawScope.drawWavyUnderline(segment: Rect, focus: FocusWordStyle) {
    val amplitude = 1.5f * density
    val wavelength = 7f * density
    val y = segment.bottom - 1.5f * density
    val path = Path().apply {
        moveTo(segment.left, y)
        var x = segment.left
        while (x < segment.right) {
            val next = (x + wavelength / 2).coerceAtMost(segment.right)
            val mid = (x + next) / 2
            val sign = if (((x - segment.left) / (wavelength / 2)).toInt() % 2 == 0) 1f else -1f
            quadraticTo(mid, y + sign * amplitude * 2, next, y)
            x = next
        }
    }
    drawPath(path, focus.decorationColor, style = Stroke(width = 1.5f * density))
}

/** The Chef's outlined pill / the Explorer's dashed field-note box around a word. */
private fun DrawScope.drawWordBox(segment: Rect, focus: FocusWordStyle, dashed: Boolean) {
    val padX = 2.5f * density
    val padY = 1f * density
    val stroke = Stroke(
        width = 1.5f * density,
        pathEffect = if (dashed) {
            PathEffect.dashPathEffect(floatArrayOf(4f * density, 3.5f * density))
        } else {
            null
        },
    )
    val radius = if (dashed) 4f * density else 9f * density
    drawRoundRect(
        color = focus.decorationColor,
        topLeft = Offset(segment.left - padX, segment.top + padY),
        size = Size(segment.width + padX * 2, segment.height - padY * 2),
        cornerRadius = CornerRadius(radius),
        style = stroke,
    )
}
