package com.copiosa.ui.vocab

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.copiosa.R
import com.copiosa.data.repository.ImageCandidate
import kotlinx.coroutines.launch

/**
 * Search-and-pick image control: an editable query, a "search" action, and a row of candidate
 * thumbnails. Picking one runs [onAttach] (download + downscale + save) and then [onAttached].
 * Pure UI — the search/attach work is supplied as suspend lambdas by the host ViewModel.
 */
@Composable
fun ImagePickerContent(
    initialQuery: String,
    onSearch: suspend (String) -> List<ImageCandidate>,
    onAttach: suspend (ImageCandidate) -> Unit,
    onAttached: () -> Unit,
    modifier: Modifier = Modifier,
    autoSearch: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    var query by remember(initialQuery) { mutableStateOf(initialQuery) }
    var results by remember { mutableStateOf<List<ImageCandidate>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var attaching by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun runSearch() {
        val q = query.trim()
        if (q.isEmpty() || loading) return
        scope.launch {
            loading = true
            error = null
            searched = true
            results = runCatching { onSearch(q) }.getOrElse {
                error = it.message
                emptyList()
            }
            loading = false
        }
    }

    // Kick off an initial search for the prefilled query when the picker first appears (stepping mode).
    LaunchedEffect(initialQuery, autoSearch) {
        if (autoSearch) runSearch()
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(stringResource(R.string.image_search_hint)) },
            singleLine = true,
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { runSearch() }),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Search),
            trailingIcon = {
                IconButton(onClick = { runSearch() }) {
                    Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.image_search_button))
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        when {
            loading || attaching -> Box(
                modifier = Modifier.fillMaxWidth().height(96.dp),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            results.isNotEmpty() -> Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                results.take(4).forEach { candidate ->
                    Box(
                        modifier = Modifier
                            .size(120.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = !attaching) {
                                scope.launch {
                                    attaching = true
                                    error = null
                                    runCatching { onAttach(candidate) }
                                        .onSuccess { onAttached() }
                                        .onFailure { error = it.message }
                                    attaching = false
                                }
                            },
                    ) {
                        FileImage(
                            file = candidate.thumbnailFile,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    }
                }
            }

            searched -> Text(
                stringResource(R.string.image_search_none),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        error?.let {
            Text(
                stringResource(R.string.image_search_error),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
