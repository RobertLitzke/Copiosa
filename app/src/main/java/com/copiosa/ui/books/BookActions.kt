package com.copiosa.ui.books

import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.copiosa.R
import com.copiosa.data.epub.BookEpubExportService
import com.copiosa.data.epub.BookExportRun
import com.copiosa.data.epub.BookExportState
import com.copiosa.data.epub.EpubExportVariant
import com.copiosa.data.generation.BookAudioManager
import com.copiosa.data.generation.BookAudioPlan
import com.copiosa.data.generation.BookAudioScheduler
import com.copiosa.data.generation.BookDownloadChoice
import com.copiosa.data.generation.BookDownloadController
import com.copiosa.data.generation.BookDownloadState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The state behind the three per-book actions — download mode, EPUB export, audio generation — and
 * their dialogs. The reader runs them for the book it is open on and the Library for whichever row
 * was tapped, so each is keyed by a book id handed to its `open…` call and the confirmation reads
 * the id back from what the dialog was opened for.
 *
 * Owned by a ViewModel, which supplies its [scope]; [BookActionDialogs] draws the dialogs.
 */
class BookActions(
    private val scope: CoroutineScope,
    private val downloads: BookDownloadController,
    private val epubExport: BookEpubExportService,
    private val bookAudio: BookAudioManager,
    private val bookAudioScheduler: BookAudioScheduler,
) {
    // --- download mode ---

    private val _downloadState = MutableStateFlow<BookDownloadState?>(null)

    /** The download dialog's data, non-null while it is open. */
    val downloadState: StateFlow<BookDownloadState?> = _downloadState.asStateFlow()

    fun openDownload(bookId: Long) {
        scope.launch { _downloadState.value = downloads.state(bookId) }
    }

    fun dismissDownload() { _downloadState.value = null }

    fun applyDownload(choice: BookDownloadChoice) {
        val id = _downloadState.value?.bookId ?: return
        _downloadState.value = null
        scope.launch { downloads.apply(id, choice) }
    }

    // --- EPUB export ---

    private val _exportState = MutableStateFlow<BookExportState?>(null)

    /** The export picker's data, non-null while the picker is open. */
    val exportState: StateFlow<BookExportState?> = _exportState.asStateFlow()

    private val _exportRun = MutableStateFlow(BookExportRun())
    val exportRun: StateFlow<BookExportRun> = _exportRun.asStateFlow()

    /**
     * Which book the file chooser was opened for. The picker closes before the system chooser
     * appears, so the dialog state is already null by the time a destination comes back.
     */
    private var pendingExportBookId: Long? = null

    /** The export in flight, kept so its progress dialog can cancel it. */
    private var exportJob: Job? = null

    fun openExport(bookId: Long) {
        pendingExportBookId = bookId
        scope.launch { _exportState.value = epubExport.state(bookId) }
    }

    fun dismissExport() { _exportState.value = null }

    fun export(uri: Uri, variant: EpubExportVariant) {
        val id = pendingExportBookId ?: return
        if (_exportRun.value.running) return
        _exportRun.value = BookExportRun(running = true)
        exportJob = scope.launch {
            _exportRun.value = try {
                BookExportRun(summary = epubExport.export(uri, id, variant))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                BookExportRun(error = e.message ?: "Export failed")
            }
        }
    }

    /**
     * Stops an export in flight. Stopped before the file is opened it writes nothing; past that
     * point the book is finished regardless, a whole file being better than half of one.
     */
    fun cancelExport() {
        if (!_exportRun.value.running) return
        exportJob?.cancel()
        _exportRun.value = BookExportRun()
    }

    fun consumeExport() { _exportRun.value = BookExportRun() }

    // --- audio ---

    private val _audioPlan = MutableStateFlow<BookAudioPlan?>(null)

    /** The pending "Generate audio for this book?" dialog, with the numbers it needs. */
    val audioPlan: StateFlow<BookAudioPlan?> = _audioPlan.asStateFlow()

    private val _audioQueued = MutableStateFlow(0)

    /** Track count for the "N chapters queued" toast; reset by [consumeAudioQueued]. */
    val audioQueued: StateFlow<Int> = _audioQueued.asStateFlow()

    fun openAudio(bookId: Long) {
        scope.launch { _audioPlan.value = bookAudio.plan(bookId) }
    }

    fun dismissAudio() { _audioPlan.value = null }

    /**
     * Queues the book's ready chapters and starts the foreground worker that keeps the process alive
     * long enough to render them; how many tracks were queued lands in [audioQueued].
     */
    fun confirmAudio() {
        val plan = _audioPlan.value ?: return
        _audioPlan.value = null
        scope.launch {
            val added = bookAudio.enqueueReady(plan.bookId)
            if (added > 0) {
                bookAudioScheduler.enqueue(plan.bookId, plan.tag)
                _audioQueued.value = added
            }
        }
    }

    fun consumeAudioQueued() { _audioQueued.value = 0 }
}

/** The dialogs and toast for [actions]; draws nothing while none of the three is pending. */
@Composable
fun BookActionDialogs(actions: BookActions) {
    val downloadState by actions.downloadState.collectAsStateWithLifecycle()
    val exportState by actions.exportState.collectAsStateWithLifecycle()
    val exportRun by actions.exportRun.collectAsStateWithLifecycle()
    val audioPlan by actions.audioPlan.collectAsStateWithLifecycle()
    val audioQueued by actions.audioQueued.collectAsStateWithLifecycle()

    downloadState?.let { state ->
        BookDownloadModeDialog(
            state = state,
            onConfirm = actions::applyDownload,
            onDismiss = actions::dismissDownload,
        )
    }

    BookEpubExportDialogs(
        state = exportState,
        run = exportRun,
        onExport = actions::export,
        onDismissPicker = actions::dismissExport,
        onCancelRun = actions::cancelExport,
        onConsumeRun = actions::consumeExport,
    )

    audioPlan?.let { plan ->
        BookAudioDialog(
            plan = plan,
            onConfirm = actions::confirmAudio,
            onDismiss = actions::dismissAudio,
        )
    }

    val context = LocalContext.current
    LaunchedEffect(audioQueued) {
        if (audioQueued > 0) {
            Toast.makeText(context, context.getString(R.string.book_audio_queued, audioQueued), Toast.LENGTH_SHORT).show()
            actions.consumeAudioQueued()
        }
    }
}
