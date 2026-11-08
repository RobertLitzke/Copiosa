package com.copiosa.data.generation

import com.copiosa.data.repository.BookRepository
import com.copiosa.data.settings.AppSettings
import com.copiosa.data.settings.SettingsRepository
import com.copiosa.data.usage.LlmUsageRepository
import com.copiosa.domain.model.BookDownloadMode
import com.copiosa.domain.model.ModelOption
import com.copiosa.domain.model.ProviderId
import com.copiosa.domain.usage.BookSpend
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** What the download-mode dialog needs to draw itself for one book. */
data class BookDownloadState(
    val bookId: Long,
    val title: String,
    val mode: BookDownloadMode,
    val selected: ModelOption,
    /** Every model the learner has a key for, plus the book's own even if its key is now gone. */
    val options: List<ModelOption>,
    /** Nicknames by model id, for the OpenRouter models the learner has named. */
    val modelNames: Map<String, String>,
    val translatedSections: Int,
    val totalSections: Int,
    /** Translated sections per model, so the dialog can price a re-translate before committing. */
    val sectionsByModel: Map<String, Int>,
    /**
     * What the book has cost on the usage meter — every model it has been through, retries and
     * re-translations included — shown beside the choice that spends more. Null when nothing is on
     * record for it.
     */
    val spent: BookSpend? = null,
) {
    val sectionsRemaining: Int get() = (totalSections - translatedSections).coerceAtLeast(0)

    /** Sections already translated by something other than [model] — what a re-translate would redo. */
    fun staleSections(model: String): Int =
        sectionsByModel.entries.filter { it.key != model }.sumOf { it.value }
}

/** The dialog's result. [retranslate] is only meaningful when the model changed. */
data class BookDownloadChoice(
    val mode: BookDownloadMode,
    val model: ModelOption,
    val retranslate: Boolean,
)

/**
 * The one place that turns a download-mode choice into work. Both the reader and the library row
 * open the same dialog, so the decision table — what to cancel, what to start, whether to redo the
 * sections an older model produced — lives here rather than twice in two ViewModels.
 */
@Singleton
class BookDownloadController @Inject constructor(
    private val bookRepo: BookRepository,
    private val settingsRepo: SettingsRepository,
    private val books: BookTranslationManager,
    private val scheduler: BookBulkTranslateScheduler,
    private val usage: LlmUsageRepository,
) {
    /** Null if the book is gone (deleted from under an open menu). */
    suspend fun state(bookId: Long): BookDownloadState? {
        val book = bookRepo.getById(bookId) ?: return null
        val settings = settingsRepo.settings.first()
        val current = ModelOption(book.provider, book.model)
        return BookDownloadState(
            bookId = bookId,
            title = book.title,
            mode = book.downloadMode,
            selected = current,
            options = modelOptions(settings, current),
            modelNames = settings.openRouterModels
                .filter { it.name.isNotBlank() }
                .associate { it.id to it.name },
            translatedSections = book.translatedSections,
            totalSections = book.totalSections,
            sectionsByModel = bookRepo.sectionsByModel(bookId).associate { it.model to it.sections },
            spent = usage.forBook(bookId),
        )
    }

    /**
     * Applies [choice]: the mode and model are stored first, then whatever is in flight is stopped
     * and the new mode starts its own work. A re-translate runs first and hands over on completion —
     * the manager allows one job per book, so the two cannot be started together.
     */
    suspend fun apply(bookId: Long, choice: BookDownloadChoice) {
        val book = bookRepo.getById(bookId) ?: return
        val modelChanged = choice.model.provider != book.provider || choice.model.model != book.model
        // Saving a dialog nobody touched must not disturb a download that is already running:
        // tearing the job down and re-enqueuing would cost the worker a backoff interval of silence.
        if (!modelChanged && choice.mode == book.downloadMode && !choice.retranslate) return

        if (modelChanged) bookRepo.setModel(bookId, choice.model.provider, choice.model.model)
        if (choice.mode != book.downloadMode) bookRepo.setDownloadMode(bookId, choice.mode)

        // Any new plan supersedes the old one; the mode below decides what starts again. The worker
        // goes too even when the book stays RAPID — its run is joined to the job just cancelled, so
        // leaving it up would only have it notice, give up, and wait out a backoff.
        books.cancel(bookId)
        scheduler.cancel(bookId)

        val stale = if (choice.retranslate) bookRepo.staleSections(bookId, choice.model.model) else null
        val from = stale?.firstSection
        val to = stale?.lastSection
        if (stale != null && stale.count > 0 && from != null && to != null) {
            // Hand over only on a clean finish: a failed re-translate should surface in the reader's
            // status bar, not be papered over by a download starting on top of it.
            books.retranslate(bookId, from, to).invokeOnCompletion { cause ->
                if (cause == null) start(choice.mode, bookId, book.title)
            }
            return
        }
        start(choice.mode, bookId, book.title)
    }

    /**
     * Restarts a rapid download that the OS killed — called when a book is opened. Cheap and
     * idempotent: unique work with KEEP, and the worker resumes from the frontier.
     */
    fun resumeIfRapid(bookId: Long, title: String, mode: BookDownloadMode, complete: Boolean) {
        if (mode == BookDownloadMode.RAPID && !complete) scheduler.enqueue(bookId, title)
    }

    private fun start(mode: BookDownloadMode, bookId: Long, title: String) {
        when (mode) {
            BookDownloadMode.RAPID -> scheduler.enqueue(bookId, title)
            // One section straight away so the choice visibly does something; the rest is the
            // trickle's, ~90 minutes apart.
            BookDownloadMode.BACKGROUND -> books.translateToEnd(bookId, maxSections = 1)
            // Read-ahead re-raises its own (small) target the next time the reader crosses a
            // section, and OFF waits for an explicit "continue".
            BookDownloadMode.WHILE_READING, BookDownloadMode.OFF -> Unit
        }
    }

    /**
     * The models the dialog can offer: every provider the learner has a key for, plus [current] so a
     * book never shows a model it isn't actually using (its provider's key can have been replaced
     * since the import).
     */
    private fun modelOptions(settings: AppSettings, current: ModelOption): List<ModelOption> {
        val options = mutableListOf<ModelOption>()
        if (settingsRepo.hasApiKey(ProviderId.GEMINI)) {
            AppSettings.GEMINI_MODELS.forEach { options += ModelOption(ProviderId.GEMINI, it) }
        }
        if (settingsRepo.hasApiKey(ProviderId.OPENROUTER)) {
            // The whole roster, not just the profile default: picking a cheaper model for one long
            // novel (or a better one for a hard book) is the reason to keep several configured.
            settings.openRouterModels.forEach { options += ModelOption(ProviderId.OPENROUTER, it.id) }
        }
        if (current !in options) options.add(0, current)
        return options.distinct()
    }
}
