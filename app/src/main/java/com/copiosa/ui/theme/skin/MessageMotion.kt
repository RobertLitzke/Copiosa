package com.copiosa.ui.theme.skin

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.min

/** Stagger is capped so opening a long chat cascades briefly instead of dribbling in for seconds. */
private const val MAX_STAGGER_STEPS = 8

/**
 * The persona's message-entry animation: fade plus the spec's translate/scale/rotate/blur, delayed
 * by [index] × stagger. Re-fires whenever [themeKey] changes (persona/country/mode switches) and
 * on screen re-entry — LazyColumn item identity restarts it for free. No-op when [spec] is null.
 *
 * Blur is a silent no-op below API 31; the fade still carries the entrance.
 */
fun Modifier.messageEnter(spec: MotionSpec?, index: Int, themeKey: Any): Modifier {
    if (spec == null) return this
    return composed {
        val progress = remember(themeKey) { Animatable(0f) }
        LaunchedEffect(themeKey) {
            progress.snapTo(0f)
            delay(min(index, MAX_STAGGER_STEPS) * spec.staggerMs.toLong())
            progress.animateTo(1f, tween(spec.durationMs, easing = spec.easing))
        }
        val p = progress.value
        this
            .graphicsLayer {
                // Overshoot easings run p past 1 — alpha must clamp, the transforms may bounce.
                alpha = p.coerceIn(0f, 1f)
                translationY = spec.translateY.toPx() * (1f - p)
                val scale = spec.scaleFrom + (1f - spec.scaleFrom) * p
                scaleX = scale
                scaleY = scale
                rotationZ = spec.rotateFrom * (1f - p)
                // Rotating entrances (Artist) pivot from the bottom-left like a peeled sticker.
                if (spec.rotateFrom != 0f) transformOrigin = TransformOrigin(0f, 1f)
            }
            .let {
                if (spec.blurFrom > 0.dp && p < 1f) it.blur(spec.blurFrom * (1f - p.coerceIn(0f, 1f))) else it
            }
    }
}
