package com.copiosa.ui.components

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.copiosa.R
import com.copiosa.domain.reading.ReadMode
import com.copiosa.ui.audio.TtsDownloadPrompt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged

// What the text, book and chat readers share around their reading surface. Each used to carry its
// own copy of these; they differ only in what they are handed.

/** How long the "Saved" pill stays up before it asks to be dismissed. */
private const val SAVED_NOTICE_MS = 1_800L

/**
 * The transient "Saved" confirmation pinned below the top bar. Shows while [visible] and, after a
 * moment, calls [onConsume] so the flag behind it clears. Place it inside the screen's root `Box`.
 */
@Composable
fun BoxScope.SavedNotice(visible: Boolean, onConsume: () -> Unit) {
    val consume by rememberUpdatedState(onConsume)
    LaunchedEffect(visible) {
        if (visible) {
            delay(SAVED_NOTICE_MS)
            consume()
        }
    }
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
        // ~56dp clears the top bar, so the pill covers neither it nor the first line of text.
        modifier = Modifier.align(Alignment.TopCenter).padding(top = 56.dp),
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shadowElevation = 6.dp,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.saved), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/** The "delete this?" confirmation every reader shows before removing what it is open on. */
@Composable
fun ConfirmDeleteDialog(
    @StringRes title: Int,
    @StringRes message: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.action_delete)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        title = { Text(stringResource(title)) },
        text = { Text(stringResource(message)) },
    )
}

/**
 * The floating lookup card for the reader's current selection, wired to its [selection] controller.
 * Chat overrides [onSave] and [onDismiss] to clear its own bookkeeping as well, and [onExplain] to
 * ask in the conversation (which a draft cannot, hence [canExplain]); the text, book and routine
 * readers take the defaults, where "Explain" is answered in the card itself.
 */
@Composable
fun ReaderTranslationCard(
    state: TranslateState,
    selection: ReadingSelectionController,
    onOpenVocab: (Long) -> Unit,
    onSave: (headword: String) -> Unit = { selection.saveCurrentTranslation(it) },
    onDismiss: () -> Unit = selection::dismissTranslate,
    canExplain: Boolean = true,
    onExplain: () -> Unit = selection::explain,
    onSendToAudio: (() -> Unit)? = null,
) {
    TranslationCard(
        original = state.original,
        translation = state.translation,
        translationOf = state.translationOf,
        loading = state.loading,
        definition = state.definition,
        canExplain = canExplain,
        anchorYPx = state.anchorY,
        savedCard = state.savedCard,
        lemmaSuggestion = state.saveLemma,
        savePos = state.savePos,
        picking = state.picking,
        onSave = onSave,
        onSelectEntry = selection::selectSaveEntry,
        onExplain = onExplain,
        onDownloadDictionary = selection::downloadDictionary,
        onOpenSaved = { state.savedCard?.let { onOpenVocab(it.id) } },
        onDismiss = onDismiss,
        relatedCard = state.relatedCard,
        onOpenRelated = { state.relatedCard?.let { onOpenVocab(it.id) } },
        onSendToAudio = onSendToAudio,
        explanation = state.explanation,
        explaining = state.explaining,
        explainError = state.explainError,
    )
}

/**
 * "Send to audio" needs the on-device voice. [request] runs the action now if it is installed;
 * otherwise it parks the action, [Prompt] offers the download, and the action runs once the learner
 * agrees (the download carries on in the background, as the voice's own queue expects).
 */
@Stable
class AudioRequestGate internal constructor(
    private val isReady: () -> Boolean,
    private val startDownload: () -> Unit,
) {
    private var pending: (() -> Unit)? = null
    internal var promptShown by mutableStateOf(false)
        private set

    fun request(enqueue: () -> Unit) {
        if (isReady()) {
            enqueue()
        } else {
            pending = enqueue
            promptShown = true
        }
    }

    internal fun confirm() {
        promptShown = false
        startDownload()
        pending?.invoke()
        pending = null
    }

    internal fun dismiss() {
        promptShown = false
        pending = null
    }
}

@Composable
fun rememberAudioRequestGate(isReady: () -> Boolean, startDownload: () -> Unit): AudioRequestGate {
    val ready by rememberUpdatedState(isReady)
    val download by rememberUpdatedState(startDownload)
    return remember { AudioRequestGate(isReady = { ready() }, startDownload = { download() }) }
}

/** The voice-download prompt for [gate]; draws nothing until an audio request needed it. */
@Composable
fun AudioRequestGate.Prompt() {
    if (promptShown) TtsDownloadPrompt(onConfirm = ::confirm, onDismiss = ::dismiss)
}

/**
 * The reader's current language version and what switching it entails, shared by the text and book
 * readers: [mode] is what the rows render, [select] the one way to change it, and [tint] the
 * correspondence tint that makes a switch legible.
 */
class ReadModeState(
    val mode: ReadMode,
    val tint: CorrespondenceTint,
    val select: (ReadMode) -> Unit,
)

/**
 * Reopens a reader in the version it was last read in ([savedMode]; null only means the row has not
 * loaded yet). The restore applies once, and a switch the reader makes first counts as having used
 * it, so a tap that beats the load is never undone by it. Both mode is captured rather than merely
 * set: there the tint is what pairs each paragraph with its translation, and arriving unlit would
 * show the two languages stacked with nothing binding them.
 *
 * `select` serves the toggle and the page swipe alike: it re-anchors the tint, persists the choice
 * and calls [onSwitch] — the place to dismiss the lookup popover, which is anchored to a position in
 * the version being left behind. [headerItems] is how many list items precede the first row, which
 * the tint's window must skip.
 */
@Composable
fun rememberReadModeState(
    savedMode: ReadMode?,
    listState: LazyListState,
    headerItems: Int,
    onSwitch: (ReadMode) -> Unit,
): ReadModeState {
    var mode by remember { mutableStateOf(ReadMode.TARGET) }
    // Whether the stored mode has had its one chance to apply.
    var restored by remember { mutableStateOf(false) }
    val tint = rememberCorrespondenceTint(mode)
    val latestSwitch by rememberUpdatedState(onSwitch)
    LaunchedEffect(savedMode) {
        if (restored) return@LaunchedEffect
        val saved = savedMode ?: return@LaunchedEffect
        restored = true
        if (saved == mode) return@LaunchedEffect
        if (saved == ReadMode.BOTH) tint.capture(saved, listState, headerItems)
        mode = saved
    }
    return ReadModeState(mode, tint) { next ->
        if (next != mode) {
            latestSwitch(next)
            tint.capture(next, listState, headerItems)
            mode = next
            restored = true
        }
    }
}

/**
 * Jumps a reader back to where it was left, then keeps reporting its position as it moves.
 *
 * [saved] is the stored (index, offset), null until it has loaded — the restore waits for it rather
 * than concluding there is nothing to restore. [itemCount] is how many rows can be scrolled into
 * (0 = none yet), and [toListIndex] maps the stored index to a list item, clamped by the caller.
 * Reporting starts only after the restore, so the persisted position is never overwritten with the
 * top of the list before the jump has had a chance to land.
 *
 * [restored] is the gate other effects use ("not before the position is back"); [markRestored]
 * claims it early for a deliberate jump, such as a table-of-contents entry, that must not be undone
 * a frame later.
 */
@Stable
class ScrollRestore internal constructor() {
    var restored by mutableStateOf(false)
        internal set

    fun markRestored() {
        restored = true
    }
}

@Composable
fun rememberScrollRestore(
    listState: LazyListState,
    saved: Pair<Int, Int>?,
    itemCount: Int,
    toListIndex: (storedIndex: Int) -> Int,
    onPosition: (listIndex: Int, offset: Int) -> Unit,
): ScrollRestore {
    val restore = remember { ScrollRestore() }
    val mapIndex by rememberUpdatedState(toListIndex)
    val report by rememberUpdatedState(onPosition)
    LaunchedEffect(saved, itemCount) {
        if (restore.restored || itemCount == 0) return@LaunchedEffect
        val (index, offset) = saved ?: return@LaunchedEffect
        if (index > 0 || offset > 0) listState.scrollToItem(mapIndex(index), offset)
        restore.restored = true
    }
    LaunchedEffect(restore.restored) {
        if (!restore.restored) return@LaunchedEffect
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .distinctUntilChanged()
            .collect { (index, offset) -> report(index, offset) }
    }
    return restore
}
