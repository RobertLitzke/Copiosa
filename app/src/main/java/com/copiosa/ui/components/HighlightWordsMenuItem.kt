package com.copiosa.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.copiosa.R

/**
 * The "Highlight saved words" toggle every reader's overflow menu carries — one item so a book, a
 * text and a chat all name and check it identically.
 *
 * [checked] is the *effective* state (this item's override, or the language's setting when it has
 * none), so what the menu shows is always what the text is doing. Flipping it writes an override on
 * the item; the language-wide default lives in Settings > Appearance.
 */
@Composable
fun HighlightWordsMenuItem(checked: Boolean, onToggle: () -> Unit, onDismiss: () -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(R.string.action_highlight_words)) },
        leadingIcon = {
            if (checked) {
                Icon(Icons.Filled.Check, contentDescription = null)
            } else {
                Spacer(modifier = Modifier.size(24.dp))
            }
        },
        onClick = { onToggle(); onDismiss() },
    )
}
