package com.copiosa.ui.books

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.copiosa.data.epub.EpubImportService
import com.copiosa.data.epub.ImportOptions
import com.copiosa.data.generation.BookDownloadController
import com.copiosa.data.generation.BookTranslationManager
import com.copiosa.data.repository.BookRepository
import com.copiosa.data.settings.SettingsRepository
import com.copiosa.domain.model.BookDirection
import com.copiosa.domain.model.BookDownloadMode
import com.copiosa.domain.model.Cefr
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Bridges the global "Import book" action (the Library FAB and the open-with intent, both rendered
 * in the nav scaffold outside the screen VMs) to the importer. Parsing + inserting the paragraph
 * rows is local work that finishes in a moment; the first sections' translation then runs in the
 * app-lifetime [BookTranslationManager], so navigating straight to the reader is safe.
 */
@HiltViewModel
class BooksEntryViewModel @Inject constructor(
    private val importService: EpubImportService,
    private val books: BookTranslationManager,
    private val bookRepo: BookRepository,
    private val downloads: BookDownloadController,
    settingsRepo: SettingsRepository,
) : ViewModel() {

    /** What the import dialog needs to phrase its question and pre-fill the level and mode. */
    data class ImportDefaults(
        val nativeLanguage: String,
        val targetLanguage: String,
        val level: Cefr,
        val downloadMode: BookDownloadMode,
    )

    val importDefaults: StateFlow<ImportDefaults?> =
        settingsRepo.settings
            .map {
                ImportDefaults(
                    it.nativeLanguage,
                    it.targetLanguage,
                    it.defaultLevel,
                    it.bookDefaultDownloadMode,
                )
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Imports the epub at [uri] with the options the import dialog collected, and returns the new
     * book id. A forward book needs its first section before the reader has anything to show; a
     * reverse book is already readable, so it only starts translating if a gloss was asked for.
     * A rapid import hands straight over to the download worker, which resumes from wherever that
     * first section left the frontier.
     */
    suspend fun importEpub(uri: Uri, options: ImportOptions): Long {
        val id = importService.import(uri, options)
        if (options.direction == BookDirection.FORWARD || options.downloadMode != BookDownloadMode.OFF) {
            books.ensureTranslatedThrough(id, FIRST_SECTIONS)
        }
        if (options.downloadMode == BookDownloadMode.RAPID) {
            downloads.resumeIfRapid(id, titleOf(id), BookDownloadMode.RAPID, complete = false)
        }
        return id
    }

    private suspend fun titleOf(id: Long): String = bookRepo.getById(id)?.title.orEmpty()

    private companion object {
        /** Sections translated eagerly on import, so the reader opens onto real content. */
        const val FIRST_SECTIONS = 1
    }
}
