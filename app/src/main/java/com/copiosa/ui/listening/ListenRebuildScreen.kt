package com.copiosa.ui.listening

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.copiosa.R
import com.copiosa.data.tts.TtsModelState
import com.copiosa.domain.stats.FocusActivityType
import com.copiosa.ui.audio.TtsDownloadPrompt
import com.copiosa.ui.components.ScreenHeader
import com.copiosa.ui.components.TrackFocusTime

/**
 * The standalone listen-and-rebuild screen: setup, sitting, results — the cloze bank's shape.
 *
 * It needs two things the learner may not have yet, and says which: the on-device voice (offered
 * with the same opt-in prompt as the Audio tab) and a stocked sentence bank (stocked from the
 * fill-the-blank screen, which this one links to rather than duplicating).
 */
@Composable
fun ListenRebuildScreen(
    onBack: () -> Unit,
    onOpenCloze: () -> Unit,
    viewModel: ListenRebuildViewModel = hiltViewModel(),
) {
    val home by viewModel.home.collectAsStateWithLifecycle()
    val session by viewModel.session.collectAsStateWithLifecycle()
    val active = session
    val running = active != null && !active.finished

    TrackFocusTime(FocusActivityType.LISTEN_REBUILD, enabled = running)

    Column(Modifier.fillMaxSize()) {
        if (!running) {
            ScreenHeader(title = stringResource(R.string.rebuild_title), onBack = onBack)
        }
        when {
            home.loading -> Unit
            active == null -> SetupPane(
                home = home,
                onDownloadVoice = viewModel::downloadVoice,
                onStart = viewModel::start,
                onOpenCloze = onOpenCloze,
            )
            active.finished -> ResultsPane(
                session = active,
                onAgain = { viewModel.dismissSession(); viewModel.start() },
                onDone = viewModel::dismissSession,
            )
            else -> ListenRebuildActivity(
                items = active.items,
                onAnswer = { _, correct, _ -> viewModel.onAnswer(correct) },
                onComplete = viewModel::onSessionComplete,
                title = stringResource(R.string.rebuild_title),
                onExit = onBack,
            )
        }
    }
}

@Composable
private fun SetupPane(
    home: RebuildHomeUi,
    onDownloadVoice: () -> Unit,
    onStart: () -> Unit,
    onOpenCloze: () -> Unit,
) {
    var askVoice by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    stringResource(R.string.cloze_bank_size, home.bankSize),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.rebuild_explainer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        when {
            home.voice is TtsModelState.Downloading -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.quiz_model_downloading), style = MaterialTheme.typography.bodyMedium)
            }
            !home.voiceReady -> {
                Text(
                    stringResource(R.string.rebuild_needs_voice),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = { askVoice = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.audio_download_tts))
                }
            }
            home.bankSize == 0 -> {
                Text(stringResource(R.string.rebuild_bank_empty), style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = onOpenCloze, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.rebuild_open_cloze))
                }
            }
            else -> {
                Button(onClick = onStart, enabled = !home.preparing, modifier = Modifier.fillMaxWidth()) {
                    if (home.preparing) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.rebuild_preparing))
                    } else {
                        Text(stringResource(R.string.cloze_start))
                    }
                }
                if (home.noneFit) {
                    Text(
                        stringResource(R.string.rebuild_none_fit),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = onOpenCloze, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.rebuild_open_cloze))
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (askVoice) {
        TtsDownloadPrompt(
            onConfirm = { askVoice = false; onDownloadVoice() },
            onDismiss = { askVoice = false },
        )
    }
}

@Composable
private fun ResultsPane(session: RebuildSessionUi, onAgain: () -> Unit, onDone: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(R.string.cloze_results_score, session.firstTime, session.answered),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            stringResource(R.string.rebuild_results_note),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onAgain, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.cloze_again))
            }
            OutlinedButton(onClick = onDone, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.cloze_finish))
            }
        }
        Box(Modifier.height(24.dp))
    }
}
