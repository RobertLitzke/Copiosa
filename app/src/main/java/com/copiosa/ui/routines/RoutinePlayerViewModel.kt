package com.copiosa.ui.routines

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.copiosa.data.cloze.ClozeRepository
import com.copiosa.data.conjugation.ConjugationDrillRepository
import com.copiosa.data.local.FlashcardEntity
import com.copiosa.data.local.RoutineRunEntity
import com.copiosa.data.repository.ConversationRepository
import com.copiosa.data.repository.FlashcardRepository
import com.copiosa.data.routine.RoutineAdaptation
import com.copiosa.data.routine.RoutineRepository
import com.copiosa.data.routine.RoutineSynthesisManager
import com.copiosa.data.settings.SettingsRepository
import com.copiosa.domain.conjugation.ConjugatedForm
import com.copiosa.domain.conjugation.DrillGrade
import com.copiosa.domain.conjugation.DrillItem
import com.copiosa.domain.highlight.HighlightTargetProvider
import com.copiosa.domain.routine.ActivityInstance
import com.copiosa.domain.routine.RoutineRunStatus
import com.copiosa.domain.vocab.WordSource
import com.copiosa.ui.components.ReadingSelectionController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drives one routine run: observes its synthesis status, steps through the synthesized plan, and records
 * silent attempts (feeding the adaptation engine).
 * The flashcard step reuses the real Review screen directly; the reading step reuses
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
    private val drillRepo: ConjugationDrillRepository,
    private val clozeRepo: ClozeRepository,
    settingsRepo: SettingsRepository,
) : ViewModel() {

    private val runId: Long = savedStateHandle.get<Long>("runId") ?: 0L

    val run: StateFlow<RoutineRunEntity?> =
        routineRepo.observeRun(runId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * The step being played, or null until the run's saved position has been read. Null rather than
     * a provisional 0 because a resumed run would otherwise compose its first step for a frame
     * before jumping to the right one — and a step's side effects (a recognition card's passive
     * review, a review session being built) are not something to run for a step nobody is on.
     */
    private val _index = MutableStateFlow<Int?>(null)
    val index: StateFlow<Int?> = _index.asStateFlow()

    private var started = false

    init {
        // A run opened while marked "preparing" with nothing preparing it — the process died
        // mid-synthesis — would otherwise wait on that screen for good.
        viewModelScope.launch { synthesizer.resumeIfStalled(runId) }
    }

    // The body the current reading step is showing, scanned by the controller for passive review.
    private val _readingText = MutableStateFlow("")

    /**
     * Tap-to-translate/define + save + saved-word highlighting for the reading step. Saves are
     * stamped with this run: the paragraphs are generated for it, so the run is the only source.
     */
    val readingSelection = ReadingSelectionController(
        scope = viewModelScope,
        conversationRepo = conversationRepo,
        flashcardRepo = flashcardRepo,
        highlightProvider = highlightProvider,
        source = { WordSource.routineRun(runId) },
        readingText = _readingText,
        // A routine's text is generated per run, so there is no row to override: it simply follows
        // the language's highlight setting.
        highlightEnabled = settingsRepo.settings.map { it.highlightSavedWords },
    )

    /** The words saved during this run, newest first. */
    val savedWords: StateFlow<List<FlashcardEntity>> = flashcardRepo.observeSavedFrom(WordSource.routineRun(runId))
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Sets the text the reading step is showing (drives passive review of saved words it contains). */
    fun setReadingText(text: String) { _readingText.value = text }

    /** Called once the plan is ready: mark the run started and resume at its saved position. */
    fun startIfNeeded() {
        if (started) return
        started = true
        viewModelScope.launch {
            routineRepo.markStarted(runId)
            _index.value = routineRepo.getRun(runId)?.currentIndex ?: 0
        }
    }

    /**
     * Builds the run again from scratch. The position goes back to the start with it: the new plan
     * is a different sequence, so a saved index into the old one points at nothing in particular.
     */
    fun retry() {
        started = false
        _index.value = null
        viewModelScope.launch {
            routineRepo.setCurrentIndex(runId, 0)
            routineRepo.setStatus(runId, RoutineRunStatus.SYNTHESIZING)
            synthesizer.synthesize(runId)
        }
    }

    /**
     * Gives up on a run that is still being prepared: stops the synthesis and removes the run, then
     * calls [onDone]. Leaving the screen any other way does neither — the routine goes on being
     * prepared and waits on the Routines tab.
     */
    fun cancelPreparing(onDone: () -> Unit) {
        viewModelScope.launch {
            synthesizer.discard(runId)
            onDone()
        }
    }

    /**
     * Records one attempt and updates the adaptation estimate. [correct] is null for no-score items;
     * [speed] is the listening rate a rapid-fire cue was heard at (1.0 for everything else), so faster
     * comprehension earns more mastery credit.
     *
     * [categoryOverride] lets a block whose items span several proficiency buckets attribute each
     * answer to the right one. The conjugation drill needs it: a single block mixes stem changes,
     * strong preterites and irregular yo forms, so the activity's own category is left null.
     */
    /**
     * Grades a cloze-bank answer into the bank itself, so a sentence practised inside a routine
     * moves the same schedule it would on the standalone screen.
     *
     * The routine's own proficiency bucket is moved by [recordAttempt] alongside this, which is why
     * the card nudge is suppressed here: the routine player has no per-sitting record of which cards
     * it has already credited, and an unguarded nudge is the one thing that could move a card several
     * times in one run.
     */
    fun recordClozeBank(sentenceId: Long, correct: Boolean, latencyMs: Long) {
        viewModelScope.launch { clozeRepo.recordById(sentenceId, correct, latencyMs, nudgeCard = false) }
    }

    fun recordAttempt(
        activity: ActivityInstance,
        correct: Boolean?,
        latencyMs: Long?,
        speed: Float = 1.0f,
        categoryOverride: String? = null,
    ) {
        viewModelScope.launch {
            val current = routineRepo.getRun(runId) ?: return@launch
            val category = categoryOverride ?: activity.category
            routineRepo.logAttempt(
                routineRunId = runId,
                activityType = activity.type,
                targetLanguage = current.targetLanguage,
                category = category,
                correct = correct,
                latencyMs = latencyMs,
                difficulty = activity.difficultyLevel,
            )
            if (correct != null) {
                adaptation.recordOutcome(
                    activity.copy(category = category), current.targetLanguage, correct, latencyMs, speed,
                )
            }
        }
    }

    /**
     * Persists one conjugation answer into the drill's own store, so practice inside a routine trains
     * the same per-item mastery as the standalone drill. The proficiency bucket is handled separately
     * by [recordAttempt], which is why this doesn't touch adaptation itself.
     */
    fun recordConjugation(item: DrillItem, form: ConjugatedForm, grade: DrillGrade, latencyMs: Long) {
        viewModelScope.launch {
            val current = routineRepo.getRun(runId) ?: return@launch
            drillRepo.record(
                lang = current.targetLanguage, item = item, form = form,
                grade = grade, latencyMs = latencyMs,
                // recordAttempt already moves this answer's proficiency bucket.
                updateProficiency = false,
            )
        }
    }

    /** Cards for the recognition step — the same sized pool the real review draws, shown L2→L1. */
    suspend fun recognitionCards(limit: Int): List<FlashcardEntity> = flashcardRepo.buildReviewSession(limit)

    /** Seeing a word in recognition is a gentle, ungraded exposure: nudge its next review back. */
    fun noteRecognition(card: FlashcardEntity) {
        viewModelScope.launch { flashcardRepo.passiveReview(card) }
    }

    /**
     * Moves on from step [from], persisting the resume point; completes the run at the end. Finishing
     * a step and skipping it both come here — a skipped step records no attempts, so it costs the
     * adaptation estimate nothing.
     *
     * Naming the step being left makes this idempotent: a second tap on Skip, or a step that reports
     * completion twice, finds the index already moved and does nothing rather than jumping two.
     */
    fun advance(from: Int, total: Int) {
        if (_index.value != from) return
        val next = from + 1
        _index.value = next
        viewModelScope.launch {
            routineRepo.setCurrentIndex(runId, next)
            if (next >= total) routineRepo.markCompleted(runId)
        }
    }

}
