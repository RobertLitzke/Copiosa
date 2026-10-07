package com.copiosa.ui.vocab

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.copiosa.R
import com.copiosa.data.local.FlashcardEntity
import com.copiosa.ui.theme.SerifBody
import com.copiosa.ui.theme.SerifTitle

/**
 * "Add images" stepping flow: walks the imageless-card backlog one card at a time. Each card auto-runs
 * an image search for its meaning; tapping a candidate attaches it and advances, and Skip moves on.
 */
@Composable
fun VocabImagesScreen(
    onBack: () -> Unit,
    viewModel: VocabImagesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.start() }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 20.dp, top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
            }
            Text(
                stringResource(R.string.add_images_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            if (state.total > 0) {
                Text(
                    stringResource(R.string.review_progress, state.position, state.total),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        val card = state.current
        when {
            state.loading -> Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            card == null -> Column(
                modifier = Modifier.fillMaxWidth().weight(1f).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.add_images_done), style = MaterialTheme.typography.titleMedium)
                Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.action_done))
                }
            }

            else -> key(card.id) { CardImageStep(card, viewModel) }
        }
    }
}

@Composable
private fun CardImageStep(card: FlashcardEntity, viewModel: VocabImagesViewModel) {
    val targetWord = if (card.frontIsTarget) card.front else card.back
    val nativeGloss = if (card.frontIsTarget) card.back else card.front
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Text(
                targetWord,
                style = SerifTitle.copy(fontSize = 26.sp, lineHeight = 32.sp),
                textAlign = TextAlign.Center,
            )
            if (nativeGloss.isNotBlank()) {
                Text(
                    nativeGloss,
                    style = SerifBody,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }

        ImagePickerContent(
            initialQuery = imageSearchQuery(nativeGloss).ifBlank { nativeGloss.ifBlank { targetWord } },
            onSearch = viewModel::searchImages,
            onAttach = { viewModel.attachImage(it) },
            onAttached = viewModel::next,
            autoSearch = true,
        )

        TextButton(
            onClick = viewModel::next,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) {
            Text(stringResource(R.string.action_skip))
        }
    }
}
