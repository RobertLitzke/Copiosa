package com.copiosa.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * A FAB that tells a tap from a long-press. M3's [androidx.compose.material3.FloatingActionButton]
 * exposes only `onClick`, so this re-creates the FAB look (56dp, 16dp rounded, primaryContainer) over
 * a [combinedClickable]. Generic on purpose: Chats uses the long-press to open batch entry, and a
 * future Texts batch can reuse the same gesture.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MultiActionFab(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shadowElevation = 6.dp,
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = contentDescription)
        }
    }
}
