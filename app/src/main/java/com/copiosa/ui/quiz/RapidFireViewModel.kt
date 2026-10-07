package com.copiosa.ui.quiz

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.copiosa.data.quiz.PoolProgress
import com.copiosa.data.quiz.QuizPoolRepository
import com.copiosa.data.quiz.toReadyCue
import com.copiosa.data.routine.RoutineAdaptation
import com.copiosa.data.routine.RoutineRepository
import com.copiosa.data.settings.SettingsRepository
import com.copiosa.data.tts.SupertonicModelStore
import com.copiosa.data.tts.TtsModelState
import com.copiosa.domain.quiz.CueType
import com.copiosa.domain.quiz.QuizAnswer
import com.copiosa.domain.quiz.QuizPoolConfig
import com.copiosa.domain.quiz.QuizQuestion
import com.copiosa.domain.quiz.QuizScoring
import com.copiosa.domain.quiz.QuizSessionBuilder
import com.copiosa.domain.quiz.SessionStats
import com.copiosa.domain.routine.RapidFireItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The quiz screen outside a session (scoped to one cue type): what gates it, and how ready its pool is. */
data class RapidFireHomeUi(
    val loading: Boolean = true,
    val modelReady: Boolean = false,
    val modelDownloading: Boolean = false,
    val progress: PoolProgress? = null,
    /** Ready-clip count for this exercise's (single-type) pool. */
    val readyCount: Int = 0,
) {
    /** Enough clips rendered to start (generation can keep draining in the background). */
    fun canStart(): Boolean = readyCount >= START_THRESHOLD

    companion object {
        const val START_THRESHOLD = 8
    }
}

/** A running or finished session. */
data class RapidFireSessionUi(
    val items: List<RapidFireItem>,
    val score: Int = 0,
    val streak: Int = 0,
    val answered: Int = 0,
    val lastPoints: Int? = null,
    val finished: Boolean = false,
    val stats: SessionStats? = null,
    /** Correct/answered per cue-type label, for the results split. */
    val topicSplit: Map<String, Pair<Int, Int>> = emptyMap(),
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class RapidFireViewModel @Inject constructor(
    savedStateHandle: androidx.lifecycle.SavedStateHandle,
    private val poolRepo: QuizPoolRepository,
    private val settingsRepo: SettingsRepository,
    private val modelStore: SupertonicModelStore,
    private val adaptation: RoutineAdaptation,
    private val routineRepo: RoutineRepository,
) : ViewModel() {

    /** Which single exercise this screen runs (Numbers or Times), from the nav route. */
    private val focusType: CueType = savedStateHandle.get<String>("type")
        ?.let { runCatching { CueType.valueOf(it) }.getOrNull() } ?: CueType.NUMBER

    val home: StateFlow<RapidFireHomeUi> =
        settingsRepo.activeProfileTag.flatMapLatest { lang ->
            combine(
                modelStore.state,
                poolRepo.observeProgress(lang, focusType),
            ) { model, progress ->
                RapidFireHomeUi(
                    loading = false,
                    modelReady = model is TtsModelState.Ready,
                    modelDownloading = model is TtsModelState.Downloading,
                    progress = progress,
                    readyCount = progress?.ready ?: 0,
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RapidFireHomeUi())

    private val _session = MutableStateFlow<RapidFireSessionUi?>(null)
    val session: StateFlow<RapidFireSessionUi?> = _session.asStateFlow()

    /** How many questions a session runs — user-picked on the home screen, capped by ready clips. */
    private val _questionCount = MutableStateFlow(SESSION_ITEMS)
    val questionCount: StateFlow<Int> = _questionCount.asStateFlow()

    /** Manual listening speed (1.0 = normal) chosen on the home screen; scoped to this screen, not saved. */
    private val _playbackSpeed = MutableStateFlow(1.0f)
    val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()

    /** The last Start came back with no questions, so the home screen can say so beside the button. */
    private val _nothingToPlay = MutableStateFlow(false)
    val nothingToPlay: StateFlow<Boolean> = _nothingToPlay.asStateFlow()

    private var questions: Map<Long, QuizQuestion> = emptyMap()
    private val answers = mutableListOf<QuizAnswer>()

    fun downloadModel() = modelStore.download()

    /** Sets how many questions the next session (and "play again") will run. */
    fun setQuestionCount(count: Int) { _questionCount.value = count }

    /** Sets the manual listening speed for the next session (and "play again"), clamped to ±50%. */
    fun setPlaybackSpeed(speed: Float) {
        _playbackSpeed.value = speed.coerceIn(QuizSessionBuilder.MANUAL_SPEED_MIN, QuizSessionBuilder.MANUAL_SPEED_MAX)
    }

    /** Restarts this exercise (the results screen's "again"). */
    fun restart() = startSession()

    fun startSession(count: Int = _questionCount.value) {
        viewModelScope.launch {
            val built = buildSession(count)
            _nothingToPlay.value = built.isEmpty()
            if (built.isEmpty()) return@launch
            questions = built.associateBy { it.cueId }
            answers.clear()
            _session.value = RapidFireSessionUi(
                items = built.map { RapidFireItem(it.cueId, it.audioPath, it.displayAnswer, it.options, it.answerIndex) },
            )
        }
    }

    /**
     * The questions for a session of [count], or none when one can't be put together: the pool or
     * its settings are gone, the ready clips don't hold up, or the builder finds no usable question.
     */
    private suspend fun buildSession(count: Int): List<QuizQuestion> {
        val lang = settingsRepo.activeProfileTag.first()
        val pool = poolRepo.poolFor(lang, focusType) ?: return emptyList()
        val config = QuizPoolConfig.decode(pool.configJson) ?: return emptyList()
        val cues = poolRepo.readyCues(lang, focusType).mapNotNull { it.toReadyCue() }
        if (cues.isEmpty()) return emptyList()
        val difficulty = routineRepo.getProficiency(focusType.slug, lang)?.difficultyLevel ?: 0
        return QuizSessionBuilder().build(cues, config, count, difficultyFor = { difficulty })
    }

    /** Scores the answer, feeds the shared proficiency bucket, and updates the live chrome. */
    fun onAnswer(item: RapidFireItem, correct: Boolean, latencyMs: Long) {
        val question = questions[item.cueId] ?: return
        val current = _session.value ?: return
        val speed = _playbackSpeed.value
        val points = QuizScoring.points(correct, latencyMs, current.streak, speed)
        answers += QuizAnswer(question, if (correct) question.answerIndex else -1, latencyMs, points)
        _session.value = current.copy(
            score = current.score + points,
            streak = if (correct) current.streak + 1 else 0,
            answered = current.answered + 1,
            lastPoints = points.takeIf { correct },
        )
        viewModelScope.launch {
            val lang = settingsRepo.activeProfileTag.first()
            adaptation.recordStandalone(question.cueType.slug, lang, correct, latencyMs, speed)
        }
    }

    fun onSessionComplete() {
        val current = _session.value ?: return
        val split = answers.groupBy { it.question.cueType }.mapKeys { it.key.label }
            .mapValues { (_, list) -> list.count { it.correct } to list.size }
        _session.value = current.copy(
            finished = true,
            stats = SessionStats.from(answers),
            topicSplit = split,
        )
    }

    fun dismissSession() {
        _session.value = null
        questions = emptyMap()
        answers.clear()
    }

    private companion object {
        const val SESSION_ITEMS = 20
    }
}
