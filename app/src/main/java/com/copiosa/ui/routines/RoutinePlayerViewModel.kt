package com.copiosa.ui.routines

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.copiosa.data.local.FlashcardEntity
import com.copiosa.data.local.RoutineRunEntity
import com.copiosa.data.repository.ConversationRepository
import com.copiosa.data.repository.FlashcardRepository
import com.copiosa.data.routine.RoutineAdaptation
import com.copiosa.data.routine.RoutineRepository
import com.copiosa.data.routine.RoutineSynthesisManager
import com.copiosa.data.tts.SupertonicModelStore
import com.copiosa.domain.highlight.HighlightTargetProvider
import com.copiosa.domain.routine.ActivityInstance
import com.copiosa.domain.routine.RoutineRunStatus
import com.copiosa.ui.components.ReadingSelectionController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drives one routine run: observes its synthesis status, steps through the synthesized plan, records
 * silent attempts (feeding the adaptation engine), and exposes whether on-device TTS is available for
 * the closing story. The flashcard step reuses the real Review screen directly; the reading step reuses
 * the shared selectable reader via [ReadingSelectionController] (so tap-to-define / save / highlight /
 * passive review behave exactly like the Texts reader).
 */
@HiltViewModel
class RoutinePlayerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val routineRepo: RoutineRepository,
    private val flashcardRepo: FlashcardRepository,
    conversationRepo: ConversationRepository,
    highlightProvider: HighlightTargetProvider,
    private val synthesizer: RoutineSynthesisManager,
    private val adaptation: RoutineAdaptation,
    private val ttsModelStore: SupertonicModelStore,
) : ViewModel() {

    private val runId: Long = savedStateHandle.get<Long>("runId") ?: 0L

    val run: StateFlow<RoutineRunEntity?> =
        routineRepo.observeRun(runId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _index = MutableStateFlow(0)
    val index: StateFlow<Int> = _index.asStateFlow()

    private var started = false

    // The body the current reading step is showing, scanned by the controller for passive review.
    private val _readingText = MutableStateFlow("")

    /**
     * Tap-to-translate/define + save + saved-word highlighting for the reading step. Saves carry no
     * source conversation (the paragraphs are generated, not an imported text).
     */
    private val selection = ReadingSelectionController(
        scope = viewModelScope,
        conversationRepo = conversationRepo,
        flashcardRepo = flashcardRepo,
        highlightProvider = highlightProvider,
        sourceConversationId = null,
        readingText = _readingText,
    )
    val translate = selection.translate
    val highlightTargets = selection.highlightTargets

    fun onSelectionTranslate(text: String, anchorY: Float, sourceText: String) =
        selection.onSelectionTranslate(text, anchorY, sourceText)

    fun saveSelection(headword: String) = selection.saveCurrentTranslation(headword)

    fun downloadDictionary() = selection.downloadDictionary()

    fun dismissTranslate() = selection.dismissTranslate()

    /** Sets the text the reading step is showing (drives passive review of saved words it contains). */
    fun setReadingText(text: String) { _readingText.value = text }

    fun ttsReady(): Boolean = ttsModelStore.isReady()

    /** Called once the plan is ready: mark the run started and resume at its saved position. */
    fun startIfNeeded() {
        if (started) return
        started = true
        viewModelScope.launch {
            routineRepo.markStarted(runId)
            _index.value = routineRepo.getRun(runId)?.currentIndex ?: 0
        }
    }

    fun retry() {
        viewModelScope.launch {
            routineRepo.setStatus(runId, RoutineRunStatus.SYNTHESIZING)
            synthesizer.synthesize(runId)
        }
    }

    /**
     * Records one attempt and updates the adaptation estimate. [correct] is null for no-score items;
     * [speed] is the listening rate a rapid-fire cue was heard at (1.0 for everything else), so faster
     * comprehension earns more mastery credit.
     */
    fun recordAttempt(activity: ActivityInstance, correct: Boolean?, latencyMs: Long?, speed: Float = 1.0f) {
        viewModelScope.launch {
            val current = routineRepo.getRun(runId) ?: return@launch
            routineRepo.logAttempt(
                routineRunId = runId,
                activityType = activity.type,
                targetLanguage = current.targetLanguage,
                category = activity.category,
                correct = correct,
                latencyMs = latencyMs,
                difficulty = activity.difficultyLevel,
            )
            if (correct != null) {
                adaptation.recordOutcome(activity, current.targetLanguage, correct, latencyMs, speed)
            }
        }
    }

    /** Cards for the recognition step — the same sized pool the real review draws, shown L2→L1. */
    suspend fun recognitionCards(limit: Int): List<FlashcardEntity> = flashcardRepo.buildReviewSession(limit)

    /** Seeing a word in recognition is a gentle, ungraded exposure: nudge its next review back. */
    fun noteRecognition(card: FlashcardEntity) {
        viewModelScope.launch { flashcardRepo.passiveReview(card) }
    }

    /** Advances to the next activity, persisting the resume point; completes the run at the end. */
    fun advance(total: Int) {
        viewModelScope.launch {
            val next = _index.value + 1
            _index.value = next
            routineRepo.setCurrentIndex(runId, next)
            if (next >= total) routineRepo.markCompleted(runId)
        }
    }

}
