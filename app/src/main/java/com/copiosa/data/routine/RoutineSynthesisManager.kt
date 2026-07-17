package com.copiosa.data.routine

import android.util.Log
import com.copiosa.data.local.FlashcardDao
import com.copiosa.data.local.ReviewLogDao
import com.copiosa.data.quiz.QuizPoolRepository
import com.copiosa.data.quiz.toReadyCue
import com.copiosa.domain.quiz.QuizPoolConfig
import com.copiosa.domain.quiz.QuizSessionBuilder
import com.copiosa.domain.srs.ReviewWindows
import com.copiosa.data.remote.LlmMessage
import com.copiosa.data.remote.LlmRequest
import com.copiosa.data.remote.ProviderRegistry
import com.copiosa.data.settings.SettingsRepository
import com.copiosa.domain.model.Role
import com.copiosa.domain.prompt.RoutineContentParser
import com.copiosa.domain.prompt.RoutineContentPrompt
import com.copiosa.domain.routine.ActivityBuildContext
import com.copiosa.domain.routine.ActivityDefinition
import com.copiosa.domain.routine.ActivityInstance
import com.copiosa.domain.routine.ActivityRegistry
import com.copiosa.domain.routine.ActivityStats
import com.copiosa.domain.routine.ActivityType
import com.copiosa.domain.routine.AdaptationMath
import com.copiosa.domain.routine.RapidFireContent
import com.copiosa.domain.routine.RapidFireItem
import com.copiosa.domain.routine.RoutineBuildServices
import com.copiosa.domain.routine.SerialStoryContent
import com.copiosa.domain.routine.SwipeItem
import com.copiosa.domain.routine.RoutineBlueprint
import com.copiosa.domain.routine.RoutineContentValidator
import com.copiosa.domain.routine.RoutinePlan
import com.copiosa.domain.routine.RoutinePlanCodec
import com.copiosa.domain.routine.RoutinePlanner
import com.copiosa.domain.routine.RoutineRunStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.concurrent.CancellationException
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
) : RoutineBuildServices {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** LLM calls used by the most recent synthesis — for the ≤5-call budget assertion / telemetry. */
    @Volatile var lastSynthesisLlmCalls: Int = 0
        private set

    fun synthesize(runId: Long): Job = scope.launch {
        var llmCalls = 0
        try {
            val run = repo.getRun(runId) ?: return@launch
            val settings = settingsRepo.settings.first()
            val lang = run.targetLanguage

            // 1. Plan locally (no LLM), biased by the adaptation engine's per-bucket stats. Rapid-fire
            // listening only enters the plan when its pre-rendered pool can actually field a block.
            val stats = repo.proficiencyFor(lang)
                .associate { it.bucketKey to ActivityStats(it.selectionWeight, it.difficultyLevel, it.mastery) }
            val unavailable = if (quizPoolRepo.readyCueCountAllTypes(lang) < RAPID_FIRE_MIN_CUES) {
                setOf(ActivityType.RAPID_FIRE_LISTENING)
            } else {
                emptySet()
            }
            val blueprint = RoutinePlanner().plan(run.routineType, run.durationMinutes, lang, stats, unavailable)

            // 2. One consolidated generation call for every activity that needs text. A serialized-story
            // slot continues the running thread, so its prior recap is fed into the same single request.
            val vocab = recentVocab(lang)
            val hasSerial = blueprint.slots.any { it.type == ActivityType.SERIAL_STORY }
            val storyThread = if (hasSerial) repo.getStoryThread(lang) else null
            val nextChapter = (storyThread?.chapter ?: 0) + 1
            val needsGen = blueprint.slots.any { ActivityDefinition[it.type].needsLlm }
            val dtoById = if (needsGen) {
                val provider = providers.active()
                val raw = provider.chat(
                    LlmRequest(
                        systemPrompt = contentPrompt.systemPrompt(settings.defaultCountry, lang, settings.nativeLanguage, run.level),
                        messages = listOf(
                            LlmMessage(
                                Role.USER,
                                contentPrompt.userRequest(
                                    blueprint, run.topic, run.topicContext, vocab,
                                    storyContext = storyThread?.summary?.ifBlank { null },
                                ),
                            ),
                        ),
                        model = settings.activeModel,
                        maxTokens = ROUTINE_MAX_TOKENS,
                    ),
                ).text
                llmCalls++
                RoutineContentParser.parse(raw)
            } else {
                emptyMap()
            }

            // 3. Assemble + validate per slot, then 4. persist.
            val activities = buildActivities(blueprint, dtoById, lang, nextChapter, stats)
            if (activities.none { it.type == ActivityType.BEDTIME_STORY }) {
                repo.setStatus(runId, RoutineRunStatus.FAILED, "Could not generate the routine. Tap to retry.")
                return@launch
            }
            val plan = RoutinePlan(run.routineType, run.durationMinutes, lang, run.topic, activities)
            repo.savePlan(runId, RoutinePlanCodec.encode(plan))
            // Advance the story thread only once a chapter actually made it into the plan.
            if (hasSerial && activities.any { it.content is SerialStoryContent }) {
                val serialId = blueprint.slots.indexOfFirst { it.type == ActivityType.SERIAL_STORY }
                val serialDto = dtoById[serialId]
                val summary = serialDto?.storySummary?.trim()?.ifBlank { null }
                    ?: serialDto?.paragraphs?.joinToString(" ")?.take(STORY_SUMMARY_CAP).orEmpty()
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
        nextChapter: Int,
        stats: Map<String, ActivityStats> = emptyMap(),
    ): List<ActivityInstance> {
        val ctx = ActivityBuildContext(lang, nextChapter, stats, validator, services = this)
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

    /** Recently-reviewed lemmas (today) plus soonest-due vocab, for recycling into the generated text. */
    private suspend fun recentVocab(lang: String): List<String> {
        val reviewedToday = reviewLogDao.cardIdsReviewedSince(startOfTodayMillis())
            .mapNotNull { id ->
                flashcardDao.getById(id)?.takeIf { it.targetLanguage == lang && it.cardKind.isLexicalVocab && it.frontIsTarget }?.front
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
        const val SWIPE_SORT_POOL = 10
        const val STORY_SUMMARY_CAP = 400
        const val VOCAB_POOL = 40
        const val MAX_VOCAB = 20
        const val RAPID_FIRE_MIN_CUES = 12
        const val RAPID_FIRE_ITEMS = 10
    }
}
