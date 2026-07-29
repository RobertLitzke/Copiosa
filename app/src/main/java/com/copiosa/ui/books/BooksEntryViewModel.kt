package com.copiosa.ui.books

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.copiosa.data.epub.EpubImportService
import com.copiosa.data.epub.ImportOptions
import com.copiosa.data.generation.BookTranslationManager
import com.copiosa.data.settings.SettingsRepository
import com.copiosa.domain.model.BookDirection
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
    settingsRepo: SettingsRepository,
) : ViewModel() {

    /** What the import dialog needs to phrase its question and pre-fill the level. */
    data class ImportDefaults(val nativeLanguage: String, val targetLanguage: String, val level: Cefr)

    val importDefaults: StateFlow<ImportDefaults?> =
        settingsRepo.settings
            .map { ImportDefaults(it.nativeLanguage, it.targetLanguage, it.defaultLevel) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Imports the epub at [uri] with the options the import dialog collected, and returns the new
     * book id. A forward book needs its first section before the reader has anything to show; a
     * reverse book is already readable, so it only starts translating if the user asked for the
     * whole-book gloss.
     */
    suspend fun importEpub(uri: Uri, options: ImportOptions): Long {
        val id = importService.import(uri, options)
        if (options.direction == BookDirection.FORWARD || options.translateAll) {
            books.ensureTranslatedThrough(id, FIRST_SECTIONS)
        }
        return id
    }

    private companion object {
        /** Sections translated eagerly on import, so the reader opens onto real content. */
        const val FIRST_SECTIONS = 1
    }
}
