package com.copiosa.ui.theme.skin

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A horizontal strip of the country's decorative motif (papel picado, azulejo, tricolor, wave).
 * Appears in three places: the band under the status bar, the thin strip above the bottom nav,
 * and the active-tab indicator. Geometry scales with [height] so thin strips keep the pattern.
 */
@Composable
fun MotifBand(
    motif: Motif,
    modifier: Modifier = Modifier,
    height: Dp = 10.dp,
    alpha: Float = 1f,
) {
    Box(modifier = modifier.height(height).motifBackground(motif, alpha))
}

/** Draws [motif] as this element's background, scaled to the element height. */
fun Modifier.motifBackground(motif: Motif, alpha: Float = 1f): Modifier = drawWithCache {
    onDrawBehind {
        clipRect { drawMotif(motif, alpha) }
    }
}

// Nominal design heights from the spec; drawing scales actual height against these.
private fun nominalHeight(motif: Motif): Float = when (motif) {
    Motif.SCALLOPS -> 12f
    Motif.CHECKERBOARD -> 14f
    Motif.PAPEL_PICADO -> 17f
    Motif.DIAGONAL_STRIPES -> 14f
    Motif.WAVES -> 14f
}

private fun DrawScope.drawMotif(motif: Motif, alpha: Float) {
    // Bail out on degenerate sizes. The tile loops below step by a period derived from the height,
    // so a sub-pixel height would collapse the period toward zero and spin the loop indefinitely
    // (millions of sub-pixel steps, or a float-precision stall). Such a band is invisible anyway,
    // and a transient 0-height layout frame must not lock up the draw thread.
    if (size.width <= 0f || size.height < 1f) return
    val s = size.height / (nominalHeight(motif) * density)
    when (motif) {
        Motif.SCALLOPS -> drawScallops(s, alpha)
        Motif.CHECKERBOARD -> drawCheckerboard(s, alpha)
        Motif.PAPEL_PICADO -> drawPapelPicado(s, alpha)
        Motif.DIAGONAL_STRIPES -> drawDiagonalStripes(alpha)
        Motif.WAVES -> drawWaves(s, alpha)
    }
}

/** Scaled px: [v] design pixels at motif scale [s]. */
private fun DrawScope.px(v: Float, s: Float): Float = v * s * density

/** Spain: red scallops hanging from the top of a gold band. */
private fun DrawScope.drawScallops(s: Float, alpha: Float) {
    drawRect(Color(0xFFFFC400), alpha = alpha)
    // Large circles with centres raised above the band, so only a shallow bottom cap of each shows —
    // a row of gentle, wide scallops. Period ≈ the cap's chord width so neighbours meet in a
    // continuous scalloped edge.
    val period = px(15f, s)
    val r = px(9.5f, s)
    val cy = px(-6f, s)
    var cx = period / 2
    while (cx - r < size.width) {
        drawCircle(Color(0xFFC60B1E), radius = r, center = Offset(cx, cy), alpha = alpha)
        cx += period
    }
}

/** Portugal: blue/white azulejo checkerboard. */
private fun DrawScope.drawCheckerboard(s: Float, alpha: Float) {
    drawRect(Color(0xFFFFFFFF), alpha = alpha)
    val cell = px(7f, s)
    val blue = Color(0xFF1E5AA8)
    var y = 0f
    var row = 0
    while (y < size.height) {
        var x = if (row % 2 == 0) 0f else cell
        while (x < size.width) {
            drawRect(blue, topLeft = Offset(x, y), size = Size(cell, cell), alpha = alpha)
            x += cell * 2
        }
        y += cell
        row++
    }
}

/** Mexico: papel picado — red string, green scallops, white punched dots. */
private fun DrawScope.drawPapelPicado(s: Float, alpha: Float) {
    val period = px(18f, s)
    val stringH = px(3f, s)
    // Green scallops hanging from the string.
    var cx = period / 2
    val r = px(8.2f, s)
    while (cx - r < size.width) {
        drawCircle(Color(0xFF006847), radius = r, center = Offset(cx, stringH), alpha = alpha)
        cx += period
    }
    // Punched white dots inside each scallop.
    cx = period / 2
    val dotR = px(2.5f, s)
    while (cx - dotR < size.width) {
        drawCircle(Color(0xF0FFFFFF), radius = dotR, center = Offset(cx, stringH + px(7f, s)), alpha = alpha)
        cx += period
    }
    // The string itself, on top.
    drawRect(Color(0xFFCE1126), size = Size(size.width, stringH), alpha = alpha)
}

/** Germany: 45° black/red/gold tricolor stripes. */
private fun DrawScope.drawDiagonalStripes(alpha: Float) {
    val stripe = 7f * density
    val colors = listOf(Color(0xFF111111), Color(0xFFDD0000), Color(0xFFFFCE00))
    val extent = size.width + size.height
    rotate(degrees = 45f, pivot = Offset.Zero) {
        var x = -extent
        var i = 0
        while (x < extent) {
            drawRect(
                colors[i % 3],
                topLeft = Offset(x, -extent),
                size = Size(stripe, extent * 2),
                alpha = alpha,
            )
            x += stripe
            i++
        }
    }
}

/** Brazil: Copacabana wave — blue crests from the top, green from the bottom, on gold. */
private fun DrawScope.drawWaves(s: Float, alpha: Float) {
    drawRect(Color(0xFFFFDF00), alpha = alpha)
    val period = px(17f, s)
    val r = px(6.5f, s)
    var cx = period / 2
    while (cx - r < size.width) {
        drawCircle(Color(0xFF0033A0), radius = r, center = Offset(cx, 0f), alpha = alpha)
        cx += period
    }
    cx = 0f
    while (cx - r < size.width) {
        drawCircle(Color(0xFF009C3B), radius = r, center = Offset(cx, size.height), alpha = alpha)
        cx += period
    }
}
