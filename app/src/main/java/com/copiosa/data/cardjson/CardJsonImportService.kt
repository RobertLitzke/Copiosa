package com.copiosa.data.cardjson

import android.content.Context
import android.net.Uri
import com.copiosa.data.local.FlashcardEntity
import com.copiosa.data.local.cardDirection
import com.copiosa.data.repository.FlashcardRepository
import com.copiosa.data.settings.SettingsRepository
import com.copiosa.data.vocab.BulkCardCreator
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Brings an edited card file back into the deck. The Android half of [CardJsonImporter]: [preview]
 * reads the file and plans the merge without writing anything, [apply] carries a plan out.
 *
 * Split in two so the screen can show what is about to change. The file was edited somewhere else,
 * possibly by a model, and "it will rewrite 212 of your notes" is something to agree to rather than
 * discover.
 */
@Singleton
class CardJsonImportService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val flashcards: FlashcardRepository,
    private val settingsRepo: SettingsRepository,
) {
    data class Summary(val updated: Int, val added: Int)

    /** Reads [uri] and works out what importing it would do to the active profile's deck. */
    suspend fun preview(uri: Uri): CardJsonImportPlan = withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: throw IllegalStateException("Could not open the file.")
        val settings = settingsRepo.settings.first()
        CardJsonImporter.plan(
            // Some editors save UTF-8 with a byte-order mark, which is not JSON.
            text = text.removePrefix("﻿"),
            existing = flashcards.observeAll().first(),
            targetLanguage = settings.targetLanguage,
        )
    }

    /** Carries out [plan], returning what was actually written. */
    suspend fun apply(plan: CardJsonImportPlan): Summary = withContext(Dispatchers.Default) {
        var updated = 0
        for (update in plan.updates) {
            // Re-read rather than trusting the previewed rows: the note may have been edited or
            // deleted while the confirmation was on screen, and only the entry's own keys may move.
            val cards = flashcards.cardsOfNote(update.noteId)
            val lead = cards.firstOrNull() ?: continue
            var changed = false
            val merged = update.patch.applyTo(lead)
            if (merged !== lead) {
                flashcards.replaceContent(merged, refreshForms = inflectsDifferently(lead, merged))
                changed = true
            }
            for ((direction, frozen) in update.patch.directionChanges(cards)) {
                val card = cards.firstOrNull { it.cardDirection == direction }
                val cardId = card?.id ?: flashcards.addDirection(lead.id, direction) ?: continue
                if (card == null || card.suspended != frozen) flashcards.setSuspended(cardId, frozen)
                changed = true
            }
            if (changed) updated++
        }

        // A handful share one enrichment call; a long list waits for the background backstop — the
        // same rule, for the same reason, as the bulk add.
        val enrich = plan.additions.size <= BulkCardCreator.ENRICH_NOW_MAX
        val settings = settingsRepo.settings.first()
        var added = 0
        for (patch in plan.additions) {
            val term = patch.term ?: continue
            val translation = patch.translation ?: continue
            // Running the patch over a blank row yields its fields already validated and tidied.
            val shaped = patch.applyTo(
                FlashcardEntity(
                    front = term,
                    back = translation,
                    cardKind = BulkCardCreator.kindFor(term),
                    targetLanguage = settings.targetLanguage,
                    nativeLanguage = settings.nativeLanguage,
                    createdAt = 0,
                    dueAt = 0,
                ),
            )
            val id = flashcards.add(
                front = shaped.front,
                back = shaped.back,
                contextSentence = shaped.contextSentence,
                sourceConversationId = null,
                partOfSpeech = shaped.partOfSpeech,
                cardKind = shaped.cardKind,
                gender = shaped.gender,
                // Null takes the kind's default — both ways, for a word, phrase or sentence.
                reviewDirection = patch.direction,
                autoSource = SOURCE,
                enrich = enrich,
            )
            // `add` covers what a saved word needs; the rest of the entry (level, alternates,
            // country, note, marked) lands the way an edit to an existing note would.
            flashcards.getCard(id)?.let { saved ->
                val merged = patch.applyTo(saved)
                if (merged !== saved) {
                    flashcards.replaceContent(merged, refreshForms = inflectsDifferently(saved, merged))
                }
            }
            added++
        }
        Summary(updated, added)
    }

    /** Whether the edit touched anything the cached highlight forms are computed from. */
    private fun inflectsDifferently(before: FlashcardEntity, after: FlashcardEntity): Boolean =
        before.targetSide != after.targetSide ||
            before.partOfSpeech != after.partOfSpeech ||
            before.gender != after.gender

    companion object {
        /** The [FlashcardEntity.autoSource] stamped on notes a file introduced. */
        const val SOURCE = "json"
    }
}
