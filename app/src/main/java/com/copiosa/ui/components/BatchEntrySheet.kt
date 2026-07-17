package com.copiosa.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** The chat defaults a batch will use (already display-formatted), shown as pills in the sheet. */
data class BatchChatSettings(
    val model: String,
    val level: String,
    val country: String,
)

/**
 * A generic "enter many items, one per line" bottom sheet. The caller supplies all copy; on submit it
 * receives the trimmed, non-blank lines. Used for the Chats batch and reusable for a future Texts batch.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchEntrySheet(
    title: String,
    helper: String,
    placeholder: String,
    buttonLabel: (count: Int) -> String,
    onSubmit: (List<String>) -> Unit,
    onDismiss: () -> Unit,
    settings: BatchChatSettings? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var text by remember { mutableStateOf("") }
    val lines = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                helper,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            settings?.let { BatchSettingsRow(it) }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
                placeholder = {
                    // Example topics stay well in the background until the user starts typing.
                    Text(
                        placeholder,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                    )
                },
                shape = RoundedCornerShape(16.dp),
            )
            Button(
                onClick = { onSubmit(lines) },
                enabled = lines.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(buttonLabel(lines.size))
            }
        }
    }
}

/** The settings the batch will run with, echoing the chat top bar's "Model · Level" pill plus country. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BatchSettingsRow(settings: BatchChatSettings) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SettingsPill("${settings.model} · ${settings.level}", icon = Icons.Filled.AutoAwesome)
        SettingsPill(settings.country)
    }
}

@Composable
private fun SettingsPill(label: String, icon: ImageVector? = null) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (icon != null) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
            }
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}
