package com.copiosa.ui.components

import android.util.Patterns
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.copiosa.R

/**
 * Bottom sheet for adding a text: a "Link" tab that fetches and translates an article URL, and a
 * "Paste text" tab that translates text the user supplies directly. The caller wires each tab to the
 * matching import; the sheet just validates input and reports the chosen one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextEntrySheet(
    onImportUrl: (String) -> Unit,
    onImportText: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var tab by remember { mutableIntStateOf(0) } // 0 = link, 1 = paste text
    var url by remember { mutableStateOf("") }
    var pasted by remember { mutableStateOf("") }

    val urlValid = remember(url) { url.isNotBlank() && Patterns.WEB_URL.matcher(url.trim()).matches() }
    val canImport = if (tab == 0) urlValid else pasted.isNotBlank()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.text_entry_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                stringResource(R.string.text_entry_helper),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SegmentedToggle(
                selected = tab,
                labels = listOf(stringResource(R.string.text_tab_link), stringResource(R.string.text_tab_paste)),
                onSelect = { tab = it },
            )

            if (tab == 0) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.hint_url)) },
                    leadingIcon = { Icon(Icons.Filled.Link, contentDescription = null) },
                    trailingIcon = {
                        if (urlValid) {
                            Icon(
                                Icons.Filled.CheckCircle,
                                contentDescription = stringResource(R.string.text_url_valid),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    shape = RoundedCornerShape(16.dp),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        Icons.Filled.Language,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(R.string.text_entry_detect),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                OutlinedTextField(
                    value = pasted,
                    onValueChange = { pasted = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
                    placeholder = { Text(stringResource(R.string.text_paste_hint)) },
                    shape = RoundedCornerShape(16.dp),
                )
            }

            Button(
                onClick = { if (tab == 0) onImportUrl(url.trim()) else onImportText(pasted.trim()) },
                enabled = canImport,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.text_import_action))
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    modifier = Modifier.padding(start = 6.dp).size(18.dp),
                )
            }
        }
    }
}
