package com.copiosa.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.copiosa.R

/** Circular icon avatar for list rows; [highlighted] rows (e.g. unread chats) get the accent tint. */
@Composable
fun CircleAvatar(
    icon: ImageVector,
    highlighted: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val bg = if (highlighted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (highlighted) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = modifier.size(40.dp).clip(CircleShape).background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(20.dp))
    }
}

/** Search state for [ScreenHeader]; pass null when a screen has no search affordance. */
data class HeaderSearch(
    val active: Boolean,
    val query: String,
    val onQueryChange: (String) -> Unit,
    val onToggle: () -> Unit,
    /** Placeholder override; defaults to the generic search hint. */
    @StringRes val hintRes: Int = R.string.search_hint,
)

/**
 * The one header every screen uses: `[back?] [logo?] Title [search?] [actions…]`.
 *
 * The brand logo stands in for the app's identity on top-level destinations; screens reached with a
 * back arrow show that instead. While [search] is active the whole row gives way to the text field.
 */
@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    search: HeaderSearch? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = if (onBack != null) 4.dp else 20.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (search != null && search.active) {
            OutlinedTextField(
                value = search.query,
                onValueChange = search.onQueryChange,
                placeholder = { Text(stringResource(search.hintRes)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = search.onToggle) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_search))
            }
            return@Row
        }

        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
            }
            Spacer(Modifier.width(4.dp))
        } else {
            // The adjacent title already names the screen, so the logo is decorative.
            Image(
                painter = painterResource(brandLogoRes()),
                contentDescription = null,
                modifier = Modifier.height(28.dp),
            )
            Spacer(Modifier.width(10.dp))
        }
        Text(
            title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (search != null) {
            IconButton(onClick = search.onToggle) {
                Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.action_search))
            }
        }
        actions()
    }
}

/**
 * The logo variant that survives the current background. Chosen from the resolved colour scheme
 * rather than a `-night` resource qualifier, because a country skin can select a dark palette
 * independently of the system uiMode.
 */
@Composable
fun brandLogoRes(): Int =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) R.drawable.ic_logo_dark else R.drawable.ic_logo

/** Small uppercase group label used to head sections (settings, the model menu, etc.). */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/** Pill segmented control matching the app's chip styling. */
@Composable
fun SegmentedToggle(
    selected: Int,
    labels: List<String>,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        labels.forEachIndexed { i, label ->
            val sel = i == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (sel) MaterialTheme.colorScheme.primary else Color.Transparent)
                    .clickable { onSelect(i) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (sel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Centered placeholder shown when a list has no items. [action] is the way in — the buttons that
 * would fill the list — so an empty screen offers something to tap rather than only describing what
 * is missing.
 */
@Composable
fun EmptyState(message: String, action: (@Composable () -> Unit)? = null) {
    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            action?.invoke()
        }
    }
}

/** Small CEFR level pill (A1–C2). */
@Composable
fun CefrBadge(level: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            level,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}
