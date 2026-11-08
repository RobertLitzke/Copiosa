package com.copiosa.ui.cloze

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.outlined.AltRoute
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.copiosa.R
import com.copiosa.data.cloze.ClozeMineResult
import com.copiosa.data.local.ClozeSentenceEntity
import com.copiosa.data.tts.TtsModelState
import com.copiosa.domain.cloze.ClozeReject
import com.copiosa.domain.model.Cloze
import com.copiosa.domain.stats.FocusActivityType
import com.copiosa.ui.audio.TtsDownloadPrompt
import com.copiosa.ui.components.ScreenHeader
import com.copiosa.ui.components.SectionLabel
import com.copiosa.ui.components.TrackFocusTime
import com.copiosa.ui.routines.activities.ChoiceItem
import com.copiosa.ui.routines.activities.MultipleChoiceActivity

/**
 * The standalone cloze bank: setup, sitting, results — structurally the conjugation drill screen.
 *
 * Every sentence here is one the learner has already met, with a word from their own deck blanked,
 * which is the whole difference from a general-purpose cloze trainer.
 */
@Composable
fun ClozeScreen(
    onBack: () -> Unit,
    /** Opens an audio folder by its tag — where a radio track made here turns up. */
    onOpenAudioTag: (String) -> Unit,
    /** Opens the bank as a list to search and tidy. */
    onBrowse: () -> Unit,
    viewModel: ClozeViewModel = hiltViewModel(),
) {
    val home by viewModel.home.collectAsStateWithLifecycle()
    val session by viewModel.session.collectAsStateWithLifecycle()
    val reading by viewModel.reading.collectAsStateWithLifecycle()
    val active = session
    val passages = reading
    val running = (active != null && !active.finished) || (passages != null && !passages.finished)

    // Only a sitting in flight counts as study time; browsing the setup pane doesn't.
    TrackFocusTime(FocusActivityType.CLOZE, enabled = running)

    val report: @Composable (Int) -> Unit = { index ->
        ReportMenu(
            // Keyed on the item so the "reported" acknowledgement doesn't carry over.
            key = index,
            onAmbiguous = { viewModel.flagAmbiguous(index) },
            onWrong = { viewModel.flagWrong(index) },
        )
    }

    Column(Modifier.fillMaxSize()) {
        // A sitting brings its own header — one that counts questions and offers a way past the
        // item on screen — so the screen-level one stands down for the duration.
        if (!running) {
            ScreenHeader(title = stringResource(R.string.cloze_title), onBack = onBack)
        }

        when {
            home.loading -> Unit
            passages != null -> {
                val passage = passages.current
                if (passage == null) {
                    ScoreResultsPane(
                        correct = passages.correct,
                        answered = passages.blanks,
                        missed = emptyList(),
                        onAgain = { viewModel.dismissReading(); viewModel.startReading() },
                        onDone = viewModel::dismissReading,
                    )
                } else {
                    ClozeReadingActivity(
                        passage = passage,
                        index = passages.index,
                        total = passages.passages.size,
                        title = stringResource(R.string.cloze_reading_title),
                        onExit = onBack,
                        onChecked = viewModel::onPassageChecked,
                        onContinue = viewModel::nextPassage,
                    )
                }
            }
            active == null -> SetupPane(
                home = home,
                onStock = viewModel::stock,
                onStart = viewModel::start,
                onRead = viewModel::startReading,
                onBrowse = onBrowse,
                onDownloadVoice = viewModel::downloadVoice,
                onMakeRadio = viewModel::makeRadio,
                onOpenAudioTag = onOpenAudioTag,
                onUnflag = viewModel::unflag,
                onDeleteFlagged = viewModel::deleteFlagged,
            )
            active.finished -> ScoreResultsPane(
                correct = active.correct,
                answered = active.answered,
                missed = active.missed,
                // Another of the same kind: a listening sitting is followed by a listening one.
                onAgain = viewModel::again,
                onDone = viewModel::dismissSession,
            )
            active.mode == ClozeMode.TYPED -> TypedClozeActivity(
                items = active.items,
                onAnswer = viewModel::onAnswer,
                onComplete = viewModel::onSessionComplete,
                title = stringResource(R.string.cloze_title),
                onExit = onBack,
                afterReveal = report,
            )
            else -> MultipleChoiceActivity(
                prompt = stringResource(
                    if (active.audio != null) R.string.cloze_listen_prompt else R.string.cloze_prompt,
                ),
                audio = active.audio,
                items = active.items.map {
                    ChoiceItem(
                        sentence = it.prompt,
                        options = it.options,
                        answerIndex = it.answerIndex,
                        // The bank has no per-item note; what it has is the sentence in English,
                        // which belongs in the translation slot and not in the explanation one.
                        explanation = null,
                        translation = it.translation,
                        glosses = it.glosses,
                    )
                },
                onAnswer = viewModel::onAnswer,
                onComplete = viewModel::onSessionComplete,
                afterReveal = report,
                title = stringResource(
                    if (active.mode == ClozeMode.TRACK) R.string.cloze_track_title else R.string.cloze_title,
                ),
                onExit = onBack,
                allowSkip = true,
                allowAgainLater = true,
            )
        }
    }
}

/**
 * Reporting a bad item: one outlined, flagged control that opens the two reasons.
 *
 * Bare text sitting between the answer and Continue reads as a row of self-scoring buttons — that
 * spot holds grading in every other mode — so this has to be visibly a control and visibly not a
 * score: an outline and a flag say "report", before any reason is offered.
 *
 * The reasons carry words as well as icons because neither is guessable from a glyph, and they stay
 * apart because the fix differs: options that could both be right are worth regenerating, a sentence
 * that doesn't work is worth never seeing again.
 */
@Composable
private fun ReportMenu(key: Int, onAmbiguous: () -> Unit, onWrong: () -> Unit) {
    var open by remember(key) { mutableStateOf(false) }
    var reported by remember(key) { mutableStateOf(false) }

    if (reported) {
        Text(
            stringResource(R.string.cloze_report_done),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Box {
        OutlinedButton(
            onClick = { open = true },
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
            modifier = Modifier.heightIn(min = 34.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        ) {
            Icon(Icons.Outlined.Flag, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.cloze_report), style = MaterialTheme.typography.labelMedium)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.cloze_report_ambiguous)) },
                leadingIcon = { Icon(Icons.Outlined.AltRoute, contentDescription = null) },
                onClick = { open = false; reported = true; onAmbiguous() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.cloze_report_wrong)) },
                leadingIcon = { Icon(Icons.Outlined.Block, contentDescription = null) },
                onClick = { open = false; reported = true; onWrong() },
            )
        }
    }
}

@Composable
private fun SetupPane(
    home: ClozeHomeUi,
    onStock: () -> Unit,
    onStart: (ClozeMode) -> Unit,
    onRead: () -> Unit,
    onBrowse: () -> Unit,
    onDownloadVoice: () -> Unit,
    onMakeRadio: (withTranslation: Boolean, title: String, tag: String) -> Unit,
    onOpenAudioTag: (String) -> Unit,
    onUnflag: (Long) -> Unit,
    onDeleteFlagged: (Long) -> Unit,
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
                    stringResource(R.string.cloze_bank_explainer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (home.canStart) {
            Button(onClick = { onStart(ClozeMode.CHOICE) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.cloze_start))
            }
            // The same words, asked harder: nothing to pick from.
            OutlinedButton(onClick = { onStart(ClozeMode.TYPED) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Keyboard, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.cloze_type))
            }
            ListenButton(home = home, onListen = { onStart(ClozeMode.LISTEN) }, onAskVoice = { askVoice = true })
            if (home.noneUsable) {
                Text(
                    stringResource(R.string.cloze_none_usable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(8.dp))
            SectionLabel(stringResource(R.string.cloze_more_section))
            OutlinedButton(onClick = onRead, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.cloze_reading_start))
            }
            if (home.noPassages) {
                Text(
                    stringResource(R.string.cloze_reading_none),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            home.track?.let { track ->
                OutlinedButton(
                    onClick = { onStart(ClozeMode.TRACK) },
                    enabled = track.banked > track.passed,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.cloze_track_start))
                }
                Text(
                    stringResource(R.string.cloze_track_progress, track.passed, track.total, track.banked),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            SectionLabel(stringResource(R.string.cloze_bank_section))
        }

        // The bank is stocked in the background too, but a first visit must not show an empty screen.
        OutlinedButton(
            onClick = onStock,
            enabled = !home.stocking,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (home.stocking) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.height(8.dp))
            }
            Text(
                stringResource(
                    if (home.bankSize == 0) R.string.cloze_stock_first else R.string.cloze_stock_more,
                ),
            )
        }

        home.lastStock?.let { StockOutcome(it) }

        if (home.canStart) {
            TextButton(onClick = onBrowse) { Text(stringResource(R.string.cloze_browse_open)) }
        }

        // Only once there is a voice to make it with; the Listen button above is the way to get one.
        if (home.canStart && home.voiceReady) {
            RadioSection(home = home, onMakeRadio = onMakeRadio, onOpenAudioTag = onOpenAudioTag)
        }

        if (home.flagged.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            SectionLabel(stringResource(R.string.cloze_flagged_section))
            home.flagged.forEach { row ->
                FlaggedRow(row, onUnflag = { onUnflag(row.id) }, onDelete = { onDeleteFlagged(row.id) })
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

/**
 * The way into a listening sitting — the same sentences, heard before they are seen. Without the
 * voice it is still shown, as the offer to fetch it: a mode that is simply absent can't be found.
 */
@Composable
private fun ListenButton(home: ClozeHomeUi, onListen: () -> Unit, onAskVoice: () -> Unit) {
    when {
        home.voice is TtsModelState.Downloading -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.quiz_model_downloading), style = MaterialTheme.typography.bodyMedium)
        }
        !home.voiceReady -> OutlinedButton(onClick = onAskVoice, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.cloze_listen_needs_voice))
        }
        else -> OutlinedButton(
            onClick = onListen,
            enabled = !home.preparingListen,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (home.preparingListen) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(if (home.preparingListen) R.string.rebuild_preparing else R.string.cloze_listen),
            )
        }
    }
}

/**
 * Radio: the bank as something to listen to with hands and eyes elsewhere. It makes an ordinary
 * playlist track rather than playing anything here, so it plays in the background, resumes, and
 * obeys the speed setting like every other track — and this screen only has to say where it went.
 */
@Composable
private fun RadioSection(
    home: ClozeHomeUi,
    onMakeRadio: (withTranslation: Boolean, title: String, tag: String) -> Unit,
    onOpenAudioTag: (String) -> Unit,
) {
    var withTranslation by remember { mutableStateOf(true) }
    val tag = stringResource(R.string.cloze_radio_tag)
    val context = LocalContext.current

    Spacer(Modifier.height(8.dp))
    SectionLabel(stringResource(R.string.cloze_radio_section))
    Text(
        stringResource(R.string.cloze_radio_explainer),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = withTranslation, onCheckedChange = { withTranslation = it })
        Text(stringResource(R.string.cloze_radio_with_translation), style = MaterialTheme.typography.bodyMedium)
    }
    OutlinedButton(
        onClick = {
            // Dated to the minute, so two tracks made on one day are told apart in the folder.
            val stamp = DateUtils.formatDateTime(
                context,
                System.currentTimeMillis(),
                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH,
            )
            onMakeRadio(withTranslation, context.getString(R.string.cloze_radio_title, stamp), tag)
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.cloze_radio_make))
    }
    home.radioQueued?.let { queued ->
        Text(
            if (queued > 0) stringResource(R.string.cloze_radio_queued, queued)
            else stringResource(R.string.cloze_radio_none),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (queued > 0) {
            TextButton(onClick = { onOpenAudioTag(tag) }) { Text(stringResource(R.string.cloze_radio_open)) }
        }
    }
}

/**
 * What the last stocking run actually did.
 *
 * A run that offers two hundred sentences and banks one is the normal way this mode disappoints, and
 * without a line like this it is indistinguishable from a run that found nothing to look at. Naming
 * the rule that did most of the refusing is what turns "it only made one" into something either of
 * us can act on.
 */
@Composable
private fun StockOutcome(result: ClozeMineResult) {
    val reason = result.topReason?.let { stringResource(reasonLabel(it)) }
    val text = when {
        result.rejected == 0 && result.banked == 0 -> stringResource(R.string.cloze_stock_none)
        reason == null -> stringResource(R.string.cloze_stock_added, result.banked)
        else -> stringResource(R.string.cloze_stock_mixed, result.banked, result.rejected, reason)
    }
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Plain-language name for each gate rule, so the outcome line says something actionable. */
private fun reasonLabel(reject: ClozeReject): Int = when (reject) {
    ClozeReject.TOO_SHORT -> R.string.cloze_reject_too_short
    ClozeReject.TOO_LONG -> R.string.cloze_reject_too_long
    ClozeReject.MARKUP -> R.string.cloze_reject_markup
    ClozeReject.ANSWER_NOT_FOUND -> R.string.cloze_reject_answer_missing
    ClozeReject.ANSWER_REPEATED -> R.string.cloze_reject_answer_repeated
    ClozeReject.ANSWER_SENTENCE_INITIAL -> R.string.cloze_reject_answer_initial
    ClozeReject.ANSWER_NOT_CONTENT_WORD -> R.string.cloze_reject_not_content
    ClozeReject.UNANCHORED -> R.string.cloze_reject_unanchored
    ClozeReject.TOO_LITTLE_CONTEXT -> R.string.cloze_reject_little_context
    ClozeReject.TOO_HARD -> R.string.cloze_reject_too_hard
}

@Composable
private fun FlaggedRow(row: ClozeSentenceEntity, onUnflag: () -> Unit, onDelete: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(Cloze.answer(row.text), style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onUnflag) { Text(stringResource(R.string.cloze_flag_restore)) }
                TextButton(onClick = onDelete) { Text(stringResource(R.string.cloze_flag_delete)) }
            }
        }
    }
}

@Composable
private fun ScoreResultsPane(
    correct: Int,
    answered: Int,
    missed: List<String>,
    onAgain: () -> Unit,
    onDone: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(R.string.cloze_results_score, correct, answered),
            style = MaterialTheme.typography.headlineSmall,
        )
        if (missed.isNotEmpty()) {
            SectionLabel(stringResource(R.string.cloze_results_missed))
            Text(
                missed.joinToString(", "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = onAgain, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.cloze_again))
            }
            OutlinedButton(onClick = onDone, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.cloze_finish))
            }
        }
    }
}
