package com.copiosa.ui.cloze

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.copiosa.data.cloze.ClozeMineResult
import com.copiosa.data.cloze.ClozeRepository
import com.copiosa.data.cloze.ClozeSource
import com.copiosa.data.cloze.ClozeTrackProgress
import com.copiosa.data.cloze.sources.BookSentenceSource
import com.copiosa.data.cloze.sources.ContextSentenceSource
import com.copiosa.data.cloze.sources.ConversationSentenceSource
import com.copiosa.data.cloze.sources.DictionaryExampleSource
import com.copiosa.data.generation.AudioGenerationManager
import com.copiosa.data.listening.SentenceClipStore
import com.copiosa.data.local.ClozeSentenceEntity
import com.copiosa.data.tts.SupertonicModelStore
import com.copiosa.data.tts.TtsModelState
import com.copiosa.domain.cloze.ClozePassageItem
import com.copiosa.domain.cloze.ClozeSessionItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The ways a sitting can be taken. The sentences are the same bank's; what is asked of them differs. */
enum class ClozeMode {
    /** Pick the word from four. */
    CHOICE,

    /** Type the word: nothing on screen to recognise. */
    TYPED,

    /** Hear the sentence, then pick the word; the text stays hidden until answered. */
    LISTEN,

    /** [CHOICE], over the frequency list's words in rank order rather than the deck's needs. */
    TRACK,
}

/** The screen outside a sitting: how big the bank is, and what the learner has set aside. */
data class ClozeHomeUi(
    val loading: Boolean = true,
    val lang: String = "",
    val bankSize: Int = 0,
    val stocking: Boolean = false,
    /** What the last on-demand stocking run did, so the button can account for itself. */
    val lastStock: ClozeMineResult? = null,
    val flagged: List<ClozeSentenceEntity> = emptyList(),
    /** The last Start found sentences but none it could build answer options for. */
    val noneUsable: Boolean = false,
    val voice: TtsModelState = TtsModelState.NotDownloaded,
    /** A listening sitting is being put together: sentences picked, first clip rendering. */
    val preparingListen: Boolean = false,
    /** What the last "make a radio track" did: sentences queued, zero for none to be had. */
    val radioQueued: Int? = null,
    /** Where the learner stands on the common-words track; null for a language with no list. */
    val track: ClozeTrackProgress? = null,
    /** The last "read a passage" found no page with enough of the learner's words on it. */
    val noPassages: Boolean = false,
) {
    val canStart: Boolean get() = bankSize > 0
    val voiceReady: Boolean get() = voice is TtsModelState.Ready
}

/** A running or finished sitting. */
data class ClozeSessionUi(
    val items: List<ClozeSessionItem>,
    val mode: ClozeMode = ClozeMode.CHOICE,
    /**
     * For a [ClozeMode.LISTEN] sitting: each item's clip, parallel to [items], null until rendered.
     * Kept beside the items rather than on them — see `MultipleChoiceActivity`'s `audio`.
     */
    val audio: List<String?>? = null,
    val answered: Int = 0,
    val correct: Int = 0,
    val finished: Boolean = false,
    /** Words missed this sitting, for the results pane to send the learner back to. */
    val missed: List<String> = emptyList(),
)

/** A reading sitting: a few passages, one at a time. */
data class ClozeReadingUi(
    val passages: List<ClozePassageItem>,
    val index: Int = 0,
    val blanks: Int = 0,
    val correct: Int = 0,
) {
    val current: ClozePassageItem? get() = passages.getOrNull(index)
    val finished: Boolean get() = index >= passages.size
}

@HiltViewModel
class ClozeViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repo: ClozeRepository,
    private val contextSentences: ContextSentenceSource,
    private val dictionaryExamples: DictionaryExampleSource,
    private val bookSentences: BookSentenceSource,
    private val conversationSentences: ConversationSentenceSource,
    private val clips: SentenceClipStore,
    private val modelStore: SupertonicModelStore,
    private val audioQueue: AudioGenerationManager,
) : ViewModel() {

    /**
     * Cards the screen was opened to practise — "these words, in sentences" from the end of a review
     * session or from a word's own screen. Used for the sitting that starts on arrival and for any
     * repeat of it, then forgotten once the learner starts something else.
     */
    private var focus: List<Long> =
        savedStateHandle.get<String>("focus").orEmpty().split(',').mapNotNull { it.trim().toLongOrNull() }

    private val _home = MutableStateFlow(ClozeHomeUi())
    val home: StateFlow<ClozeHomeUi> = _home.asStateFlow()

    private val _session = MutableStateFlow<ClozeSessionUi?>(null)
    val session: StateFlow<ClozeSessionUi?> = _session.asStateFlow()

    private val _reading = MutableStateFlow<ClozeReadingUi?>(null)
    val reading: StateFlow<ClozeReadingUi?> = _reading.asStateFlow()

    /**
     * Cards already credited this sitting.
     *
     * The bank holds up to three sentences per word, so without this a single sitting could nudge
     * one card three times — three quarters of a review for one word. Mirrors how
     * `ReadingSelectionController` guards passive review while reading.
     */
    private val nudged = mutableSetOf<Long>()

    /** Renders a listening sitting's remaining clips while the learner works on the first. */
    private var renderJob: Job? = null

    init {
        viewModelScope.launch {
            refresh()
            // Opened to practise particular words: go straight to them.
            if (focus.isNotEmpty()) start(ClozeMode.CHOICE)
        }
        viewModelScope.launch { modelStore.state.collect { s -> _home.update { it.copy(voice = s) } } }
    }

    fun downloadVoice() = modelStore.download()

    private suspend fun refresh() {
        val lang = repo.activeLanguage()
        _home.update { it.copy(loading = false, lang = lang, track = repo.trackProgress(lang)) }
        viewModelScope.launch {
            repo.observeActiveCount(lang).collect { n -> _home.update { it.copy(bankSize = n) } }
        }
        viewModelScope.launch {
            repo.observeFlagged(lang).collect { rows -> _home.update { it.copy(flagged = rows) } }
        }
    }

    private suspend fun lang(): String = _home.value.lang.ifEmpty { repo.activeLanguage() }

    /**
     * Fills the bank from material the learner already owns.
     *
     * Run on demand from the setup pane rather than left to the background pass, because a bank that
     * is only ever stocked in the background means the first tap on this screen shows nothing and
     * reads as broken. The sources used here are local, so this costs no network and no tokens.
     */
    fun stock() {
        if (_home.value.stocking) return
        viewModelScope.launch {
            _home.update { it.copy(stocking = true, lastStock = null, noneUsable = false) }
            try {
                val lang = lang()
                val ctx = repo.buildContext(lang)
                val result = repo.mine(localSources(), ctx, limitPerSource = STOCK_PER_RUN)
                repo.translateLocally(lang, STOCK_TRANSLATIONS)
                _home.update { it.copy(lastStock = result, track = repo.trackProgress(lang)) }
            } finally {
                _home.update { it.copy(stocking = false) }
            }
        }
    }

    /**
     * The same local sources the background pass uses — no network, no model, no tokens — and in the
     * same order: reading first, the card's own example last.
     */
    private fun localSources(): List<ClozeSource> =
        listOf(bookSentences, conversationSentences, dictionaryExamples, contextSentences)

    /** Starts a sitting in [mode]. Listening has its own way in, since it waits on a clip first. */
    fun start(mode: ClozeMode) {
        if (mode == ClozeMode.LISTEN) return startListening()
        renderJob?.cancel()
        viewModelScope.launch {
            val lang = lang()
            val items = if (mode == ClozeMode.TRACK) {
                repo.trackSession(lang, SESSION_SIZE)
            } else {
                repo.session(lang, SESSION_SIZE, focusCardIds = focus)
            }
            nudged.clear()
            // A sentence with too few wrong options to offer is dropped rather than padded (see
            // ClozeRepository.session), so a small bank can come back empty; the pane says so.
            _home.update { it.copy(noneUsable = items.isEmpty()) }
            _session.value = if (items.isEmpty()) null else ClozeSessionUi(items = items, mode = mode)
        }
    }

    /**
     * A listening sitting: the same items, each heard before it is seen.
     *
     * Starts as soon as the *first* clip exists and renders the rest behind it, as listen-and-rebuild
     * does — fifteen clips up front would be most of a minute of spinner.
     */
    fun startListening() {
        if (_home.value.preparingListen) return
        renderJob?.cancel()
        viewModelScope.launch {
            _home.update { it.copy(preparingListen = true, noneUsable = false) }
            try {
                val lang = lang()
                val picked = repo.session(lang, SESSION_SIZE, focusCardIds = focus)
                // The first clip is the one the learner waits on, so its failure is worth a second
                // sentence's try rather than an empty sitting.
                val first = picked.withIndex().firstNotNullOfOrNull { (i, item) ->
                    render(item, lang)?.let { i to it }
                }
                if (first == null) {
                    _home.update { it.copy(noneUsable = true) }
                    return@launch
                }
                val items = picked.drop(first.first)
                nudged.clear()
                _session.value = ClozeSessionUi(
                    items = items,
                    mode = ClozeMode.LISTEN,
                    audio = listOf<String?>(first.second) + List(items.size - 1) { null },
                )
                renderJob = viewModelScope.launch { renderRest(items, lang) }
            } finally {
                _home.update { it.copy(preparingListen = false) }
            }
        }
    }

    /**
     * Fills in the clips after the first, one at a time and in order. A clip that fails stays
     * missing; that item's play button keeps spinning and Skip moves past it.
     */
    private suspend fun renderRest(items: List<ClozeSessionItem>, lang: String) {
        for (i in 1 until items.size) {
            val path = render(items[i], lang) ?: continue
            _session.update { s ->
                val audio = s?.audio
                if (audio == null || s.items !== items) s
                else s.copy(audio = audio.toMutableList().also { it[i] = path })
            }
        }
    }

    private suspend fun render(item: ClozeSessionItem, lang: String): String? = try {
        clips.clipFor(item.sentenceId, item.sentence, lang).absolutePath
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Clip failed for sentence ${item.sentenceId}", e)
        null
    }

    /**
     * Queues a radio track from the bank: one playlist item in the [tag] folder, rendered by the same
     * queue as every other track. [title] and [tag] come from the screen because they are words the
     * learner reads, and the view model has no resources to find them in.
     */
    fun makeRadio(withTranslation: Boolean, title: String, tag: String) {
        viewModelScope.launch {
            val lang = lang()
            val lines = repo.radioLines(lang, RADIO_SIZE, withTranslation)
            if (lines.isNotEmpty()) audioQueue.enqueueRadio(lines, title, tag, lang)
            _home.update { it.copy(radioQueued = lines.size) }
        }
    }

    fun onAnswer(index: Int, correct: Boolean, latencyMs: Long) {
        val current = _session.value ?: return
        val item = current.items.getOrNull(index) ?: return
        _session.value = current.copy(
            answered = current.answered + 1,
            correct = current.correct + if (correct) 1 else 0,
            missed = if (correct) current.missed else current.missed + item.answer,
        )
        // A listening answer scores the sitting and nothing else. The clip says the missing word
        // aloud, so picking it out is hearing, not recall — and writing it to the sentence's memory
        // estimate, or nudging the card, would credit a memory that was never tested.
        if (current.mode == ClozeMode.LISTEN) return
        // One nudge per card per sitting; the sentence's own record always moves.
        val shouldNudge = item.cardId != null && nudged.add(item.cardId)
        viewModelScope.launch {
            repo.record(item, correct, latencyMs, nudgeCard = shouldNudge, typed = current.mode == ClozeMode.TYPED)
        }
    }

    fun onSessionComplete() {
        renderJob?.cancel()
        _session.value = _session.value?.copy(finished = true)
        viewModelScope.launch { _home.update { it.copy(track = repo.trackProgress(lang())) } }
    }

    /** Another sitting of the kind just finished. */
    fun again() {
        val mode = _session.value?.mode ?: ClozeMode.CHOICE
        dismissSession()
        start(mode)
    }

    fun dismissSession() {
        renderJob?.cancel()
        _session.value = null
        // The words the screen was opened for have had their sitting; what follows is the bank's own.
        focus = emptyList()
    }

    // --- reading passages ----------------------------------------------------

    fun startReading() {
        viewModelScope.launch {
            val passages = repo.readingPassages(lang(), PASSAGES)
            _home.update { it.copy(noPassages = passages.isEmpty()) }
            _reading.value = if (passages.isEmpty()) null else ClozeReadingUi(passages)
        }
    }

    /** One passage checked: [results] is each blank's card and whether it was filled right. */
    fun onPassageChecked(results: List<Pair<Long, Boolean>>) {
        _reading.update { r ->
            r?.copy(blanks = r.blanks + results.size, correct = r.correct + results.count { it.second })
        }
        viewModelScope.launch { repo.recordPassage(results.toMap()) }
    }

    fun nextPassage() {
        _reading.update { it?.copy(index = it.index + 1) }
    }

    fun dismissReading() {
        _reading.value = null
    }

    // --- throwing an item out ------------------------------------------------

    fun flagAmbiguous(index: Int) {
        val id = _session.value?.items?.getOrNull(index)?.sentenceId ?: return
        viewModelScope.launch { repo.flagAmbiguous(id) }
    }

    fun flagWrong(index: Int) {
        val id = _session.value?.items?.getOrNull(index)?.sentenceId ?: return
        viewModelScope.launch { repo.flagWrong(id) }
    }

    fun unflag(id: Long) {
        viewModelScope.launch { repo.unflag(id) }
    }

    fun deleteFlagged(id: Long) {
        viewModelScope.launch { repo.delete(id) }
    }

    private companion object {
        const val TAG = "ClozeBank"
        const val SESSION_SIZE = 15

        /** Sentences in one radio track — about five minutes with translations at normal speed. */
        const val RADIO_SIZE = 30

        /** Sentences one on-demand stocking pass may add — enough for several sittings, not a stall. */
        const val STOCK_PER_RUN = 60

        /** Translations back-filled on the device while the learner waits on the stocking button. */
        const val STOCK_TRANSLATIONS = 150

        /** Passages in one reading sitting. */
        const val PASSAGES = 3
    }
}
