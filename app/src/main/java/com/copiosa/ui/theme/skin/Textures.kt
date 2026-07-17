package com.copiosa.ui.theme.skin

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.math.hypot

/**
 * Draws the persona's background texture (tinted by the country palette) behind this element.
 * No-op when the skin is unstyled. All painters are cached per size — nothing allocates per frame.
 */
fun Modifier.personaTexture(skin: AppSkin): Modifier {
    val texture = skin.personaSpec?.texture ?: return this
    val palette = skin.palette
    return drawWithCache {
        onDrawBehind {
            clipRect {
                when (texture) {
                    PersonaTexture.VIGNETTE -> drawVignette(palette)
                    PersonaTexture.GINGHAM -> drawGingham(palette)
                    PersonaTexture.RADIAL_WASH -> drawRadialWash(palette)
                    PersonaTexture.PAINT_WASH -> drawPaintWash(palette)
                    PersonaTexture.RULED -> drawRuled(palette)
                    PersonaTexture.CONTOURS -> drawContours(palette)
                }
            }
        }
    }
}

/** Professor: reading-lamp vignette — the page darkens toward the bottom corners. */
private fun DrawScope.drawVignette(palette: CountryPalette) {
    val radius = hypot(size.width, size.height)
    drawRect(
        Brush.radialGradient(
            0.5f to Color.Transparent,
            1f to palette.text.copy(alpha = 0.11f),
            center = Offset(size.width / 2f, 0f),
            radius = radius,
        ),
    )
}

/** Chef: gingham weave — two crossing families of diagonal accent stripes. */
private fun DrawScope.drawGingham(palette: CountryPalette) {
    val stripe = 10f * density
    val color = palette.accent.copy(alpha = 0.07f)
    val extent = size.width + size.height
    for (angle in listOf(45f, -45f)) {
        rotate(degrees = angle, pivot = Offset.Zero) {
            var x = -extent
            while (x < extent) {
                drawRect(color, topLeft = Offset(x, -extent), size = Size(stripe, extent * 2))
                x += stripe * 2
            }
        }
    }
}

/** Poet: a faint accent wash breathing down from the top of the page. */
private fun DrawScope.drawRadialWash(palette: CountryPalette) {
    drawRect(
        Brush.radialGradient(
            0f to palette.accent.copy(alpha = 0.09f),
            0.6f to Color.Transparent,
            center = Offset(size.width / 2f, -size.height * 0.1f),
            radius = maxOf(size.width * 1.2f, size.height * 0.9f),
        ),
    )
}

/** Artist: three overlapping paint washes lighting the studio from different corners. */
private fun DrawScope.drawPaintWash(palette: CountryPalette) {
    val washes = listOf(
        Triple(Offset(size.width * 0.06f, size.height * 0.04f), size.width * 0.5f, palette.accent.copy(alpha = 0.22f)),
        Triple(Offset(size.width * 1.04f, size.height * 0.4f), size.width * 0.55f, palette.accentSoftInk.copy(alpha = 0.18f)),
        Triple(Offset(size.width * 0.6f, size.height * 1.08f), size.width * 0.5f, palette.accent.copy(alpha = 0.16f)),
    )
    for ((center, radius, color) in washes) {
        drawRect(
            Brush.radialGradient(
                0f to color,
                1f to Color.Transparent,
                center = center,
                radius = radius,
            ),
        )
    }
}

/** Commuter: faint horizontal timetable rules. */
private fun DrawScope.drawRuled(palette: CountryPalette) {
    val gap = 28f * density
    val color = palette.text.copy(alpha = 0.04f)
    var y = gap
    while (y < size.height) {
        drawRect(color, topLeft = Offset(0f, y), size = Size(size.width, 1f * density))
        y += gap
    }
}

/** Explorer: contour-line rings radiating from off the top-right corner of the map. */
private fun DrawScope.drawContours(palette: CountryPalette) {
    val center = Offset(size.width * 1.15f, -size.height * 0.25f)
    val period = 27.5f * density
    val stroke = Stroke(width = 1.5f * density)
    val color = palette.text.copy(alpha = 0.06f)
    val maxRadius = hypot(center.x, center.y + size.height)
    var r = period
    while (r < maxRadius) {
        drawCircle(color, radius = r, center = center, style = stroke)
        r += period
    }
}
