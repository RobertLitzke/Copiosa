package com.copiosa.data.cardjson

import android.content.Context
import android.net.Uri
import com.copiosa.data.exportTo
import com.copiosa.data.local.FlashcardDao
import com.copiosa.data.local.noteKey
import com.copiosa.data.settings.SettingsRepository
import com.copiosa.domain.vocab.freq.FrequencyLists
import com.copiosa.domain.vocab.freq.VocabCoverage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes the active profile's deck to a user-chosen file as readable JSON. The Android half of
 * [CardJsonExporter], which owns the format — the same split `AnkiExportService`/`AnkiExporter` uses.
 */
@Singleton
class CardJsonExportService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val flashcardDao: FlashcardDao,
    private val settingsRepo: SettingsRepository,
    private val coverage: VocabCoverage,
) {
    /** Thrown when there are no notes to export, so the UI can explain rather than write an empty file. */
    class NothingToExportException : Exception("You don't have any saved words to export yet.")

    /** Exports every note of the active profile (frozen ones included); returns how many were written. */
    suspend fun export(uri: Uri, now: Long = System.currentTimeMillis()): Int =
        context.contentResolver.exportTo(uri) { write(uri, now) }

    private suspend fun write(uri: Uri, now: Long): Int = withContext(Dispatchers.IO) {
        val settings = settingsRepo.settings.first()
        val cards = flashcardDao.getAll(settings.targetLanguage)
        if (cards.isEmpty()) throw NothingToExportException()

        // Empty for a language with no bundled list, which just leaves the rank off every note.
        val ranks = FrequencyLists.forTag(settings.targetLanguage)
            ?.let { coverage.ranksByCard(cards, it) }
            .orEmpty()
        val text = CardJsonExporter.encode(cards, ranks, settings.targetLanguage, settings.nativeLanguage, now)

        // The last point a cancel is honoured; opening the destination truncates it.
        ensureActive()
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            ?: throw IllegalStateException("Could not open the destination file.")
        cards.distinctBy { it.noteKey }.size
    }
}
