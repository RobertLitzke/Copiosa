package com.copiosa.data.stats

import com.copiosa.data.local.ActivityTotal
import com.copiosa.data.local.AudioItemDao
import com.copiosa.data.local.ConjugationDrillDao
import com.copiosa.data.local.ConversationDao
import com.copiosa.data.local.DayTotal
import com.copiosa.data.local.FlashcardDao
import com.copiosa.data.local.FocusStatsDao
import com.copiosa.data.local.MessageDao
import com.copiosa.data.local.RatingCount
import com.copiosa.data.local.ReviewLogDao
import com.copiosa.data.local.StateCount
import com.copiosa.domain.model.FlashcardState
import com.copiosa.domain.model.ReviewRating
import com.copiosa.domain.model.Mode
import com.copiosa.domain.stats.FocusActivityType
import com.copiosa.domain.stats.Streak
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** All-time study time for one activity type, in whole minutes. */
data class ActivityMinutes(val type: FocusActivityType, val minutes: Int)

/** Time & consistency: everything derived from the focus-time log, kept indefinitely. */
data class TimeStats(
    val totalMinutes: Int,
    val currentStreak: Int,
    val longestStreak: Int,
    val daysStudied: Int,
    val bestDayMinutes: Int,
    val avgMinutesPerActiveDay: Int,
    /** Per-activity breakdown, largest first. */
    val byActivity: List<ActivityMinutes>,
    /** The last [StatsRepository.HEATMAP_DAYS] days, oldest first, zero-filled — the calendar grid. */
    val heatmap: List<DayMinutes>,
)

/** Vocabulary & reviews, from the flashcard table and the review log. */
data class VocabStats(
    val totalCards: Int,
    val newCards: Int,
    val learningCards: Int,
    /** Cards in the REVIEW/RELEARNING lifecycle — the "known, on a schedule" bucket. */
    val reviewCards: Int,
    val suspendedCards: Int,
    val addedLast7: Int,
    val addedLast30: Int,
    val reviewsDone: Int,
    /** Share of reviews graded better than "Again", 0..100; null when there are no reviews yet. */
    val reviewAccuracyPct: Int?,
    /** Reviews graded per day over the last [StatsRepository.REVIEW_SPARK_DAYS] days, oldest first. */
    val reviewsPerDay: List<Int>,
    /** How many reviews were graded with each rating (Again/Hard/Good/Easy) — the answer distribution. */
    val ratingCounts: Map<ReviewRating, Int>,
)

/** Reading & listening, from conversations, messages, texts, and the audio playlist. */
data class ReadingStats(
    val conversations: Int,
    val messages: Int,
    val textsFinished: Int,
    val audioPlayed: Int,
    val listeningMinutes: Int,
)

/**
 * Conjugation drilling. Unlike the review stats, [avgLatencyMs] is a genuine measure of *recall
 * speed* — the drill stops the clock when the learner asks to see the answer — so it is the one
 * number here that says something reviews can't.
 */
data class ConjugationStats(
    val drills: Int,
    val accuracyPercent: Int?,
    val avgLatencyMs: Long,
    val itemsSeen: Int,
    val itemsMastered: Int,
    /** bucket slug -> (asked, right), for the per-tense and per-pattern splits. */
    val byTense: List<Triple<String, Int, Int>>,
    val byFamily: List<Triple<String, Int, Int>>,
)

/** The full stats-screen payload for one scope (a profile, or all profiles combined). */
data class LearnerStats(
    val time: TimeStats,
    val vocab: VocabStats,
    val reading: ReadingStats,
    val conjugation: ConjugationStats,
)

/**
 * Read-side aggregation for the stats screen. Everything here is all-time and derived from data the
 * app already retains indefinitely (daily focus rollups, flashcards, the review log, conversations,
 * audio) — so it needs only queries, never new tracking. A null `lang` means "every profile
 * combined"; otherwise the payload is scoped to that profile.
 *
 * The write side ([FocusTimeTracker]) and the home-header read side ([FocusStatsRepository]) are
 * unchanged; this sits alongside them.
 */
@Singleton
class StatsRepository @Inject constructor(
    private val focusDao: FocusStatsDao,
    private val flashcardDao: FlashcardDao,
    private val reviewDao: ReviewLogDao,
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val audioDao: AudioItemDao,
    private val drillDao: ConjugationDrillDao,
) {
    // Seams for tests; production always uses the defaults.
    internal var now: () -> Long = System::currentTimeMillis
    internal var zone: () -> ZoneId = ZoneId::systemDefault

    /** The full stats payload for [lang] (or all profiles when null), reacting to any underlying change. */
    fun observeStats(lang: String?): Flow<LearnerStats> {
        val nowMs = now()
        val today = today()
        val since7 = nowMs - 7 * DAY_MS
        val since30 = nowMs - 30 * DAY_MS
        // Start-of-day of the oldest sparkline day, so the query window aligns to day boundaries.
        val sparkFrom = LocalDate.ofEpochDay(today - (REVIEW_SPARK_DAYS - 1))
            .atStartOfDay(zone()).toInstant().toEpochMilli()

        val timeFlow = combine(
            focusDao.observeDayTotalsScoped(lang),
            focusDao.observeActivityTotals(lang),
        ) { dayTotals, activity -> timeStats(dayTotals, activity, today) }

        val cardsFlow = combine(
            flashcardDao.observeCardCount(lang),
            flashcardDao.observeStateCounts(lang),
            flashcardDao.observeSuspendedCount(lang),
            flashcardDao.observeCardsAddedSince(lang, since7),
            flashcardDao.observeCardsAddedSince(lang, since30),
        ) { total, states, suspended, added7, added30 ->
            Cards(total, states, suspended, added7, added30)
        }
        val reviewsFlow = combine(
            reviewDao.observeReviewCount(lang),
            reviewDao.observeCorrectReviewCount(lang),
            reviewDao.observeReviewTimestampsSince(lang, sparkFrom),
            reviewDao.observeRatingCounts(lang),
        ) { done, correct, timestamps, ratingRows ->
            Reviews(done, correct, reviewsPerDay(timestamps, today), ratingRows.associate { it.rating to it.count })
        }
        val vocabFlow = combine(cardsFlow, reviewsFlow) { c, r ->
            val byState = c.states.associate { it.state to it.count }
            VocabStats(
                totalCards = c.total,
                newCards = byState[FlashcardState.NEW] ?: 0,
                learningCards = byState[FlashcardState.LEARNING] ?: 0,
                reviewCards = (byState[FlashcardState.REVIEW] ?: 0) + (byState[FlashcardState.RELEARNING] ?: 0),
                suspendedCards = c.suspended,
                addedLast7 = c.added7,
                addedLast30 = c.added30,
                reviewsDone = r.done,
                reviewAccuracyPct = if (r.done == 0) null else (r.correct * 100) / r.done,
                reviewsPerDay = r.perDay,
                ratingCounts = r.ratings,
            )
        }

        val readingFlow = combine(
            conversationDao.observeCountByMode(Mode.CONVERSATION, lang),
            messageDao.observeMessageCount(lang),
            conversationDao.observeFinishedTextCount(Mode.URL_TRANSLATION, lang),
            audioDao.observePlayedCount(lang),
            audioDao.observePlayedDurationMs(lang),
        ) { conv, msg, texts, played, durMs ->
            ReadingStats(
                conversations = conv,
                messages = msg,
                textsFinished = texts,
                audioPlayed = played,
                listeningMinutes = (durMs / MS_PER_MINUTE).toInt(),
            )
        }

        val drillFlow = combine(
            drillDao.observeAttemptCount(lang),
            drillDao.observeCorrectCount(lang),
            drillDao.observeAvgLatencyMs(lang),
            drillDao.observeItemsSeen(lang),
            drillDao.observeMasteredCount(lang, MASTERED_THRESHOLD),
        ) { attempts, correct, avgLatency, seen, mastered ->
            Drills(attempts, correct, (avgLatency ?: 0.0).toLong(), seen, mastered)
        }
        val drillSplitFlow = combine(
            drillDao.observeByTense(lang),
            drillDao.observeByFamily(lang),
        ) { byTense, byFamily -> byTense to byFamily }

        val conjugationFlow = combine(drillFlow, drillSplitFlow) { d, splits ->
            ConjugationStats(
                drills = d.attempts,
                accuracyPercent = if (d.attempts == 0) null else (d.correct * 100) / d.attempts,
                avgLatencyMs = d.avgLatencyMs,
                itemsSeen = d.seen,
                itemsMastered = d.mastered,
                byTense = splits.first.map { Triple(it.bucket, it.total, it.correct) },
                byFamily = splits.second.map { Triple(it.bucket, it.total, it.correct) },
            )
        }

        return combine(timeFlow, vocabFlow, readingFlow, conjugationFlow) { time, vocab, reading, conjugation ->
            LearnerStats(time, vocab, reading, conjugation)
        }
    }

    /** Clears focus-time history for [lang] (or every profile when null). Flashcards/reviews are untouched. */
    suspend fun clearFocusStats(lang: String?) {
        focusDao.clearSessions(lang)
        focusDao.clearDailyStats(lang)
    }

    /**
     * Clears the conjugation drill's history for [lang] (or every profile when null): both the
     * attempt log and the per-item mastery the selector reads, so the drill starts cold. The routine
     * engine's `activity_proficiency` buckets are left alone — they are shared with other activities.
     */
    suspend fun clearConjugationStats(lang: String?) {
        drillDao.clearAttempts(lang)
        drillDao.clearItemStats(lang)
    }

    private fun timeStats(dayTotals: List<DayTotal>, activity: List<ActivityTotal>, today: Long): TimeStats {
        val minutesByDay = dayTotals.associate { it.epochDay to (it.totalMs / MS_PER_MINUTE).toInt() }
        val activeDays = dayTotals.map { it.epochDay } // present in the log == a day with study time
        val totalMinutes = (dayTotals.sumOf { it.totalMs } / MS_PER_MINUTE).toInt()
        val daysStudied = activeDays.size
        return TimeStats(
            totalMinutes = totalMinutes,
            currentStreak = Streak.compute(activeDays, today),
            longestStreak = Streak.longest(activeDays),
            daysStudied = daysStudied,
            bestDayMinutes = minutesByDay.values.maxOrNull() ?: 0,
            avgMinutesPerActiveDay = if (daysStudied == 0) 0 else totalMinutes / daysStudied,
            byActivity = activity
                .map { ActivityMinutes(it.activityType, (it.totalMs / MS_PER_MINUTE).toInt()) }
                .sortedByDescending { it.minutes },
            heatmap = (0 until HEATMAP_DAYS).map { i ->
                val day = today - (HEATMAP_DAYS - 1) + i
                DayMinutes(epochDay = day, minutes = minutesByDay[day] ?: 0, isToday = day == today)
            },
        )
    }

    /** Buckets review timestamps into per-day counts over the trailing sparkline window, oldest first. */
    private fun reviewsPerDay(timestamps: List<Long>, today: Long): List<Int> {
        val z = zone()
        val counts = HashMap<Long, Int>()
        for (ts in timestamps) {
            val day = Instant.ofEpochMilli(ts).atZone(z).toLocalDate().toEpochDay()
            counts[day] = (counts[day] ?: 0) + 1
        }
        return (0 until REVIEW_SPARK_DAYS).map { i -> counts[today - (REVIEW_SPARK_DAYS - 1) + i] ?: 0 }
    }

    private fun today(): Long =
        Instant.ofEpochMilli(now()).atZone(zone()).toLocalDate().toEpochDay()

    /** Intermediate carrier for the five card-count flows, so [combine] stays within its 5-arg form. */
    private data class Cards(
        val total: Int,
        val states: List<StateCount>,
        val suspended: Int,
        val added7: Int,
        val added30: Int,
    )

    /** Intermediate carrier for the drill's five scalar flows, keeping [combine] in its 5-arg form. */
    private data class Drills(
        val attempts: Int,
        val correct: Int,
        val avgLatencyMs: Long,
        val seen: Int,
        val mastered: Int,
    )

    /** Intermediate carrier for the review-count/accuracy/sparkline/distribution flows. */
    private data class Reviews(
        val done: Int,
        val correct: Int,
        val perDay: List<Int>,
        val ratings: Map<ReviewRating, Int>,
    )

    companion object {
        const val MS_PER_MINUTE = 60_000L
        const val DAY_MS = 86_400_000L

        /** The calendar heatmap spans 12 weeks. */
        const val HEATMAP_DAYS = 84

        /** The reviews-per-day sparkline spans the last 30 days. */
        const val REVIEW_SPARK_DAYS = 30

        /** Mastery at or above which a drilled form counts as solid. Matches the drill repository. */
        const val MASTERED_THRESHOLD = 0.85
    }
}
