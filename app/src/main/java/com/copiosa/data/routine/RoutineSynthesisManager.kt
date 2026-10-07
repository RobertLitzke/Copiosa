package com.copiosa.data.routine

import android.util.Log
import com.copiosa.data.cloze.ClozeRepository
import com.copiosa.data.listening.ListenRebuildRepository
import com.copiosa.data.conjugation.ConjugationDrillRepository
import com.copiosa.data.course.CourseRepository
import com.copiosa.data.local.FlashcardDao
import com.copiosa.data.local.ReviewLogDao
import com.copiosa.data.quiz.QuizPoolRepository
import com.copiosa.data.quiz.toReadyCue
import com.copiosa.domain.conjugation.ConjugationDrillConfig
import com.copiosa.domain.conjugation.ConjugationSpec
import com.copiosa.domain.conjugation.DrillDirection
import com.copiosa.domain.conjugation.DrillItemCodec
import com.copiosa.domain.conjugation.Tense
import com.copiosa.domain.quiz.QuizPoolConfig
import com.copiosa.domain.quiz.QuizSessionBuilder
import com.copiosa.domain.srs.ReviewWindows
import com.copiosa.domain.usage.LlmFeature
import com.copiosa.domain.vocab.freq.FrequencyLists
import com.copiosa.domain.vocab.freq.VocabCoverage
import com.copiosa.data.remote.JsonShape
import com.copiosa.data.remote.LlmMessage
import com.copiosa.data.remote.LlmRequest
import com.copiosa.data.remote.ProviderRegistry
import com.copiosa.data.settings.SettingsRepository
import com.copiosa.di.AppScope
import com.copiosa.domain.model.Role
import com.copiosa.domain.prompt.RoutineContentParser
import com.copiosa.domain.prompt.RoutineContentPrompt
import com.copiosa.domain.routine.ActivityBuildContext
import com.copiosa.domain.routine.ActivityDefinition
import com.copiosa.domain.routine.ActivityInstance
import com.copiosa.domain.routine.ActivityRegistry
import com.copiosa.domain.routine.ActivitySlot
import com.copiosa.domain.routine.ActivityStats
import com.copiosa.domain.routine.ActivityType
import com.copiosa.domain.routine.BankedClozeItem
import com.copiosa.domain.routine.ClozeBankContent
import com.copiosa.domain.routine.ClozeItem
import com.copiosa.domain.routine.ConjugationDrillContent
import com.copiosa.domain.routine.ListenRebuildContent
import com.copiosa.domain.routine.AdaptationMath
import com.copiosa.domain.routine.RapidFireContent
import com.copiosa.domain.routine.RapidFireItem
import com.copiosa.domain.routine.RoutineBuildServices
import com.copiosa.domain.routine.RoutineType
import com.copiosa.domain.routine.ShortTextContent
import com.copiosa.domain.routine.SwipeItem
import com.copiosa.domain.routine.RoutineBlueprint
import com.copiosa.domain.routine.RoutineContentValidator
import com.copiosa.domain.routine.RoutinePlan
import com.copiosa.domain.routine.RoutinePlanCodec
import com.copiosa.domain.routine.RoutinePlanner
import com.copiosa.domain.routine.RoutineRunStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns a freshly-created [com.copiosa.data.local.RoutineRunEntity] into a playable [RoutinePlan].
 * The flow honours the ≤5-LLM-calls budget (target 1): plan the activity sequence locally, make ONE
 * consolidated generation call for all activity text, validate the grammar items locally against the
 * dictionary forms table, then persist the assembled plan. App-lifetime scope so synthesis survives
 * navigation; the UI observes progress via the run's [RoutineRunStatus].
 */
@Singleton
class RoutineSynthesisManager @Inject constructor(
    private val repo: RoutineRepository,
    private val topicRepo: TopicRepository,
    private val settingsRepo: SettingsRepository,
    private val providers: ProviderRegistry,
    private val contentPrompt: RoutineContentPrompt,
    private val validator: RoutineContentValidator,
    private val flashcardDao: FlashcardDao,
    private val reviewLogDao: ReviewLogDao,
    private val quizPoolRepo: QuizPoolRepository,
    private val drillRepo: ConjugationDrillRepository,
    private val clozeRepo: ClozeRepository,
    private val rebuildRepo: ListenRebuildRepository,
    private val courseRepo: CourseRepository,
    private val coverage: VocabCoverage,
    @AppScope private val scope: CoroutineScope,
) : RoutineBuildServices {

    /** LLM calls used by the most recent synthesis — for the ≤5-call budget assertion / telemetry. */
    @Volatile var lastSynthesisLlmCalls: Int = 0
        private set

    /** The synthesis in flight for each run, so it can be found again: to cancel it, or not to repeat it. */
    private val jobs = ConcurrentHashMap<Long, Job>()

    /** Whether [runId] is being synthesized right now, in this process. */
    fun isActive(runId: Long): Boolean = jobs[runId]?.isActive == true

    /**
     * Starts synthesizing [runId], or returns the synthesis already under way for it — a second
     * request for the same run would spend a second model call to write the same plan.
     *
     * The job is registered before this returns, which is what lets a caller that has just created
     * a run rely on [isActive] straight away.
     */
    @Synchronized
    fun synthesize(runId: Long): Job {
        jobs[runId]?.takeIf { it.isActive }?.let { return it }
        val job = scope.launch(start = CoroutineStart.LAZY) { runSynthesis(runId) }
        jobs[runId] = job
        job.invokeOnCompletion { jobs.remove(runId, job) }
        job.start()
        return job
    }

    /**
     * Picks a run back up if it is marked as being prepared but nothing is preparing it — the
     * state a process killed mid-synthesis leaves behind, which would otherwise sit on "Preparing…"
     * for good. A no-op for a run in any other state, or one whose synthesis is alive.
     */
    suspend fun resumeIfStalled(runId: Long) {
        if (isActive(runId)) return
        if (repo.getRun(runId)?.status == RoutineRunStatus.SYNTHESIZING) synthesize(runId)
    }

    /**
     * Abandons [runId]: stops its synthesis if one is running — taking the model call with it, if
     * the reply has not landed yet — and deletes the run. Waits for the job to finish unwinding
     * first, so it cannot write a plan or a failure onto a row that is about to be gone.
     */
    suspend fun discard(runId: Long) {
        jobs.remove(runId)?.cancelAndJoin()
        repo.deleteRun(runId)
    }

    private suspend fun runSynthesis(runId: Long) {
        var llmCalls = 0
        try {
            val run = repo.getRun(runId) ?: return
            val settings = settingsRepo.settings.first()
            val lang = run.targetLanguage

            // 1. Plan locally (no LLM), biased by the adaptation engine's per-bucket stats. Rapid-fire
            // listening only enters the plan when its pre-rendered pool can actually field a block.
            val stats = repo.proficiencyFor(lang)
                .associate { it.bucketKey to ActivityStats(it.selectionWeight, it.difficultyLevel, it.mastery) }
            val unavailable = buildSet {
                if (quizPoolRepo.readyCueCountAllTypes(lang) < RAPID_FIRE_MIN_CUES) add(ActivityType.RAPID_FIRE_LISTENING)
                // Same reasoning for listen-and-rebuild: without the voice or a bank there is nothing
                // to hear, and a slot planned anyway would just be dropped, shortening the routine.
                if (!rebuildRepo.voiceReady() || clozeRepo.observeActiveCount(lang).first() < MIN_REBUILD_ITEMS) {
                    add(ActivityType.LISTEN_REBUILD)
                }
            }
            val blueprint = RoutinePlanner().plan(run.routineType, run.durationMinutes, lang, stats, unavailable)

            // 2. One consolidated generation call for every activity that needs text. A bedtime prose
            // slot continues the running story thread, so its prior recap is fed into the same single
            // request — and is also what makes that slot a chapter rather than a standalone story.
            val vocab = recentVocab(lang)
            val textSlot = blueprint.slots.indexOfFirst { it.type == ActivityType.SHORT_TEXT }
            val storyThread =
                if (textSlot >= 0 && run.routineType == RoutineType.BEDTIME) repo.getStoryThread(lang) else null
            val storyContext = storyThread?.summary?.ifBlank { null }
            val nextChapter = (storyThread?.chapter ?: 0) + 1
            val needsGen = blueprint.slots.any { ActivityDefinition[it.type].needsLlm }
            val dtoById = if (needsGen) {
                val provider = providers.active()
                val raw = provider.chat(
                    LlmRequest(
                        systemPrompt = contentPrompt.systemPrompt(
                            settings.defaultCountry, lang, settings.nativeLanguage, run.level, run.routineType,
                        ),
                        messages = listOf(
                            LlmMessage(
                                Role.USER,
                                contentPrompt.userRequest(
                                    blueprint, run.topic, run.topicContext, vocab, storyContext,
                                ),
                            ),
                        ),
                        model = settings.activeModel,
                        feature = LlmFeature.ROUTINE,
                        maxTokens = ROUTINE_MAX_TOKENS,
                        json = JsonShape.OBJECT,
                    ),
                ).text
                llmCalls++
                RoutineContentParser.parse(raw)
            } else {
                emptyMap()
            }

            // 3. Assemble + validate per slot, then 4. persist. A routine that lost its defining
            // activity, or shrank to a single step, is not worth showing the learner.
            val activities = buildActivities(blueprint, dtoById, lang, run.routineType, nextChapter, storyContext, stats)
            val missingRequired = run.routineType.requiredType?.let { req -> activities.none { it.type == req } } == true
            if (missingRequired || activities.size < MIN_ACTIVITIES) {
                repo.setStatus(runId, RoutineRunStatus.FAILED, "Could not generate the routine. Tap to retry.")
                return
            }
            val plan = RoutinePlan(run.routineType, run.durationMinutes, lang, run.topic, activities)
            repo.savePlan(runId, RoutinePlanCodec.encode(plan))
            // Advance the story thread once the prose actually made it into a bedtime plan — whether it
            // continued the thread or, on the first night, started it. Daytime never writes the thread.
            val story = activities.map { it.content }.filterIsInstance<ShortTextContent>().firstOrNull()
            if (story != null && run.routineType == RoutineType.BEDTIME) {
                val dto = dtoById[textSlot]
                val summary = dto?.storySummary?.trim()?.ifBlank { null }
                    ?: story.blocks.joinToString(" ").take(STORY_SUMMARY_CAP)
                repo.advanceStoryThread(lang, nextChapter, summary)
            }
            if (!run.topic.isNullOrBlank()) topicRepo.markUsed(run.topic, lang)
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            Log.w(TAG, "Routine synthesis failed for run $runId", t)
            repo.setStatus(runId, RoutineRunStatus.FAILED, t.message ?: "Synthesis failed")
        } finally {
            lastSynthesisLlmCalls = llmCalls
            Log.i(TAG, "Routine $runId synthesized with $llmCalls LLM call(s)")
        }
    }

    /** Maps each blueprint slot to validated content; drops a slot whose content is missing/unusable. */
    private suspend fun buildActivities(
        blueprint: RoutineBlueprint,
        dtoById: Map<Int, RoutineContentParser.ActivityDto>,
        lang: String,
        routineType: RoutineType,
        nextChapter: Int,
        storyContext: String?,
        stats: Map<String, ActivityStats> = emptyMap(),
    ): List<ActivityInstance> {
        val ctx = ActivityBuildContext(lang, routineType, nextChapter, storyContext, stats, validator, services = this)
        val out = mutableListOf<ActivityInstance>()
        blueprint.slots.forEachIndexed { id, slot ->
            val content = ActivityRegistry[slot.type].buildContent(slot, dtoById[id], ctx) ?: return@forEachIndexed
            // Rapid-fire picks its topic (proficiency bucket) at build time, not plan time.
            val category = (content as? RapidFireContent)?.topic ?: slot.category
            out += ActivityInstance(slot.type, slot.band, slot.difficultyLevel, category, content)
        }
        return out
    }

    /** Built offline from the user's own gendered nouns — no LLM, no generated text. */
    override suspend fun swipeSortItems(lang: String): List<SwipeItem> =
        flashcardDao.nounsWithGender(lang, SWIPE_SORT_POOL).mapNotNull { card ->
            when (card.gender?.uppercase()) {
                "M" -> SwipeItem(card.front.trim(), 0)
                "F" -> SwipeItem(card.front.trim(), 1)
                else -> null
            }
        }

    /**
     * A single-topic rapid-fire block from the rendered pool: the cue type (= proficiency bucket) is
     * weighted-picked so weaker topics surface more, mirroring how contrast categories are chosen; the
     * questions get fresh distractors at the bucket's current difficulty. Null when the pool can't
     * field a block (the slot is dropped like any other unusable content).
     */
    override suspend fun buildRapidFire(lang: String, stats: Map<String, ActivityStats>): RapidFireContent? {
        // Draw from both type pools; each type is now its own independent pool.
        val cues = quizPoolRepo.readyCuesAllTypes(lang).mapNotNull { it.toReadyCue() }
        val byType = cues.groupBy { it.type }.filterValues { it.size >= RAPID_FIRE_MIN_CUES }
        if (byType.isEmpty()) return null
        val types = byType.keys.toList()
        val weights = types.map { (stats[it.slug]?.selectionWeight ?: 1.0).coerceAtLeast(0.05) }
        var roll = kotlin.random.Random.nextDouble() * weights.sum()
        var topic = types.last()
        for ((i, w) in weights.withIndex()) {
            roll -= w
            if (roll <= 0.0) { topic = types[i]; break }
        }
        // Distractors re-derive from the chosen type's own pool config (number range / time style).
        val config = quizPoolRepo.poolFor(lang, topic)?.let { QuizPoolConfig.decode(it.configJson) } ?: return null
        val bucketStats = stats[topic.slug]
        val difficulty = bucketStats?.difficultyLevel ?: 0
        // Auto listening speed as a difficulty lever: mastered topics play faster, struggling ones slower.
        val speed = bucketStats?.let { AdaptationMath.speedFor(it.mastery) } ?: 1.0f
        val questions = QuizSessionBuilder()
            .build(byType.getValue(topic), config, count = RAPID_FIRE_ITEMS, difficultyFor = { difficulty })
        if (questions.size < MIN_ITEMS) return null
        return RapidFireContent(
            topic = topic.slug,
            items = questions.map { RapidFireItem(it.cueId, it.audioPath, it.displayAnswer, it.options, it.answerIndex) },
            playbackSpeed = speed,
        )
    }

    /**
     * A fill-the-blank block from the cloze bank.
     *
     * Reuses the standalone screen's own session builder, so the items are chosen, ordered and
     * option-filled by exactly the same rules — including dropping any item whose options can't be
     * made safely wrong. Below [MIN_CLOZE_BANK_ITEMS] it returns null and the planner drops the slot,
     * which is what a learner with an empty bank should get rather than a two-question activity.
     */
    override suspend fun buildClozeBank(lang: String, slot: ActivitySlot): ClozeBankContent? {
        val items = clozeRepo.session(lang, CLOZE_BANK_ITEMS)
        if (items.size < MIN_CLOZE_BANK_ITEMS) return null
        return ClozeBankContent(
            items.map { item ->
                BankedClozeItem(
                    sentenceId = item.sentenceId,
                    item = ClozeItem(
                        sentence = item.prompt,
                        options = item.options,
                        answerIndex = item.answerIndex,
                        explanation = item.translation,
                    ),
                )
            },
        )
    }

    /**
     * A listen-and-rebuild block: banked sentences, each rendered to a clip now rather than when the
     * step comes up, so the player never waits on the voice model mid-routine. A sentence whose
     * render fails is dropped like an unfillable cloze item; below [MIN_REBUILD_ITEMS] the slot goes.
     */
    override suspend fun buildListenRebuild(lang: String): ListenRebuildContent? {
        if (!rebuildRepo.voiceReady()) return null
        val picked = rebuildRepo.pick(lang, REBUILD_ITEMS)
        if (picked.size < MIN_REBUILD_ITEMS) return null
        val items = picked.mapNotNull { item ->
            try {
                rebuildRepo.withClip(item, lang)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Rebuild clip failed for sentence ${item.sentenceId}", e)
                null
            }
        }
        return if (items.size < MIN_REBUILD_ITEMS) null else ListenRebuildContent(items)
    }

    /**
     * A conjugation block from the bundled verb lexicon — no LLM, no pool. It is gated only on the
     * profile's language having a lexicon at all, which the activity's `CONJUGATION` requirement has
     * already checked by the time a slot exists.
     *
     * The slot's adaptation difficulty widens the block rather than just relabelling it: a learner
     * who is finding conjugation easy meets more tenses and, at the top level, the two derived card
     * types that test whether the pattern actually generalized.
     */
    override suspend fun buildConjugationDrill(lang: String, slot: ActivitySlot): ConjugationDrillContent? {
        val spec = drillRepo.specFor(lang) ?: return null
        val stored = ConjugationDrillConfig.decode(settingsRepo.settingsFor(lang).conjugationDrillConfigJson)
        val config = (stored ?: ConjugationDrillConfig()).copy(
            itemCount = CONJUGATION_ITEMS,
            tenses = conjugationTenses(spec, slot.difficultyLevel),
            directions = conjugationDirections(slot.difficultyLevel),
        )
        // A course in progress steers half the block onto what it has already taught, so routine
        // practice reinforces the current lesson instead of running ahead of it. Only half: the
        // routine is still the learner's general practice, not an extension of the course.
        val courseScope = courseRepo.activeCourseScope(lang)
            ?.takeIf { it.tenses.isNotEmpty() }
        val items = if (courseScope == null) {
            drillRepo.selectItems(lang, config)
        } else {
            val taught = drillRepo.selectItems(
                lang,
                config.copy(
                    itemCount = CONJUGATION_ITEMS / 2,
                    tenses = courseScope.tenses.filter { it in spec.tenses }.toSet(),
                ),
            )
            val rest = drillRepo.selectItems(lang, config.copy(itemCount = CONJUGATION_ITEMS - taught.size))
            // De-duplicated on (verb, cell), not itemKey: the selector's own rule is one question per
            // cell per session, because asking for "tuvo" twice — once to produce, once to decode —
            // is one question as far as the learner is concerned. Two select() calls can't see each
            // other's picks, so that rule has to be re-applied across them here.
            val takenCells = taught.map { it.infinitive to it.target }.toMutableSet()
            (taught + rest.filter { takenCells.add(it.infinitive to it.target) })
                .take(CONJUGATION_ITEMS)
        }
        if (items.size < MIN_ITEMS) return null
        return ConjugationDrillContent(items.map(DrillItemCodec::encode), language = spec.language)
    }

    /**
     * How many active cards the deck holds for words inside [maxRank]. The same join the vocabulary
     * screen's rank chips use, so the drill is offered exactly when the learner has words to drill.
     */
    override suspend fun frequentWordCardCount(lang: String, maxRank: Int): Int {
        val list = FrequencyLists.forTag(lang) ?: return 0
        return coverage.ranksByCard(flashcardDao.allActive(lang), list).count { it.value <= maxRank }
    }

    /**
     * Drawn from the language's own paradigm list, never `Tense.entries` — otherwise Portuguese's
     * future subjunctive and personal infinitive would leak into a Spanish drill at difficulty 2.
     */
    private fun conjugationTenses(spec: ConjugationSpec, difficulty: Int): Set<Tense> {
        val ladder = when {
            difficulty >= 2 -> spec.tenses
            difficulty == 1 -> listOf(Tense.PRESENT, Tense.PRETERITE, Tense.IMPERFECT, Tense.FUTURE)
            else -> listOf(Tense.PRESENT, Tense.PRETERITE)
        }
        return ladder.filter { it in spec.tenses }.toSet().ifEmpty { spec.tenses.toSet() }
    }

    private fun conjugationDirections(difficulty: Int): Set<DrillDirection> =
        if (difficulty >= 2) DrillDirection.entries.toSet() else ConjugationDrillConfig.DEFAULT_DIRECTIONS

    /** Recently-reviewed lemmas (today) plus soonest-due vocab, for recycling into the generated text. */
    private suspend fun recentVocab(lang: String): List<String> {
        val reviewedToday = reviewLogDao.cardIdsReviewedSince(startOfTodayMillis())
            .mapNotNull { id ->
                flashcardDao.getById(id)?.takeIf { it.targetLanguage == lang && it.cardKind.isLexicalVocab }?.front
            }
        val now = System.currentTimeMillis()
        // Prefer words due this week; widen to the locked-in window when too few qualify.
        val dueSoon = flashcardDao.countUpcomingByLanguage(lang, now + ReviewWindows.HINT_DUE_MS)
        val maxDue = ReviewWindows.hintMaxDue(now, dueSoon)
        val upcoming = flashcardDao.upcomingByLanguage(lang, maxDue, VOCAB_POOL).map { it.front }
        return (reviewedToday + upcoming).map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(MAX_VOCAB)
    }

    private fun startOfTodayMillis(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private companion object {
        const val TAG = "RoutineSynthesis"
        const val ROUTINE_MAX_TOKENS = 4096
        const val MIN_ITEMS = 2

        /** A one-step routine isn't a routine; below this the run fails and offers a retry. */
        const val MIN_ACTIVITIES = 2
        const val SWIPE_SORT_POOL = 10
        const val STORY_SUMMARY_CAP = 400
        const val VOCAB_POOL = 40
        const val MAX_VOCAB = 20
        const val RAPID_FIRE_MIN_CUES = 12
        const val RAPID_FIRE_ITEMS = 10
        const val CONJUGATION_ITEMS = 8

        /** Items in a routine's fill-the-blank block, and the floor below which the slot is dropped. */
        const val CLOZE_BANK_ITEMS = 8
        const val MIN_CLOZE_BANK_ITEMS = 4

        /** Sentences in a routine's listen-and-rebuild block; each is a clip rendered at build time. */
        const val REBUILD_ITEMS = 5
        const val MIN_REBUILD_ITEMS = 3
    }
}
