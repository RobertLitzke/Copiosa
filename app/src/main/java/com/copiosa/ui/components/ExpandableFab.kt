package com.copiosa.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/** One choice in an [ExpandableFab]'s expanded menu. */
data class FabAction(
    val label: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
)

/**
 * A FAB whose tap expands a small speed-dial of [actions] — each a labelled mini-FAB — above it, and
 * whose long-press is a shortcut straight to the default [onClick] (the best single action). When
 * expanded the main button becomes a close (×) and the system back also collapses it.
 *
 * [expanded] is hoisted so the host can draw a scrim over its content while the dial is open and
 * collapse it on a tap anywhere else — a FAB in a Scaffold slot can't reach outside itself to do that.
 * Generic so a future Texts FAB can reuse it; built on [MultiActionFab].
 */
@Composable
fun ExpandableFab(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    actions: List<FabAction>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(enabled = expanded) { onExpandedChange(false) }

    // No spacing on the outer column so the main FAB keeps its exact slot position when collapsed
    // (the AnimatedVisibility measures to zero); the gap above the FAB lives inside the menu instead.
    Column(modifier = modifier, horizontalAlignment = Alignment.End) {
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
        ) {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(bottom = 12.dp),
            ) {
                actions.forEach { action ->
                    FabActionItem(action) {
                        onExpandedChange(false)
                        action.onClick()
                    }
                }
            }
        }
        MultiActionFab(
            icon = if (expanded) Icons.Filled.Close else icon,
            contentDescription = contentDescription,
            onClick = { onExpandedChange(!expanded) },
            onLongClick = { if (expanded) onExpandedChange(false) else onClick() },
        )
    }
}

@Composable
private fun FabActionItem(action: FabAction, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shadowElevation = 3.dp,
        ) {
            Text(
                action.label,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shadowElevation = 4.dp,
        ) {
            Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                Icon(action.icon, contentDescription = action.label)
            }
        }
    }
}
