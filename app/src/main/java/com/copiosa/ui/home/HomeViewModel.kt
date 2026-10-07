package com.copiosa.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.copiosa.data.cloze.ClozeRepository
import com.copiosa.data.conjugation.ConjugationDrillRepository
import com.copiosa.data.course.CourseOverview
import com.copiosa.data.course.CourseRepository
import com.copiosa.data.local.BookEntity
import com.copiosa.data.local.ConversationEntity
import com.copiosa.data.repository.AudioRepository
import com.copiosa.data.repository.BookRepository
import com.copiosa.data.repository.ConversationRepository
import com.copiosa.data.repository.FlashcardRepository
import com.copiosa.data.routine.ResumableRoutine
import com.copiosa.data.routine.RoutineRepository
import com.copiosa.data.settings.AppSettings
import com.copiosa.data.settings.SettingsRepository
import com.copiosa.data.stats.FocusStatsRepository
import com.copiosa.data.stats.WeeklyFocusStats
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** What the "continue reading" callout points at, which decides the screen it opens. */
enum class ContinueKind { CHAT, TEXT, BOOK }

/** The "continue reading" callout: the newest unread chat, unfinished text, or book in progress. */
data class ContinueItem(
    val id: Long,
    val title: String,
    /** A chat's or text's last assistant turn; a book's author. */
    val snippet: String?,
    val kind: ContinueKind,
    /** How far through a book the reader is, by paragraph. Null for anything but a book. */
    val progressPercent: Int? = null,
)

data class HomeUi(
    val loaded: Boolean = false,
    val stats: WeeklyFocusStats? = null,
    /**
     * What is in flight, most recently touched first: the newest unread chat or unfinished text,
     * and the book being read. Both appear when both exist — a book left mid-read stays reachable
     * from Home however many chats have happened since, and equally a newer chat is not buried by
     * the book. Empty when there is nothing to return to.
     */
    val continueItems: List<ContinueItem> = emptyList(),
    /** Today's reviews — due words, with new ones only as far as the daily limit admits them. */
    val dueCards: Int = 0,
    val unplayedAudio: Int = 0,
    val unreadChats: Int = 0,
    /** Target of the "unread conversation" card (the newest unread chat). */
    val latestUnreadChatId: Long? = null,
    /** Daily study goal (minutes) driving the tracker's target line. */
    val dailyGoalMinutes: Int = AppSettings.DEFAULT_DAILY_GOAL_MINUTES,
    /**
     * Whether the active profile has a verb lexicon to drill from. Gates the conjugation session
     * format exactly as it gates the Routines card — offering it without one leads nowhere.
     */
    val conjugationAvailable: Boolean = false,
    /** Whether the cloze bank holds anything — the format is hidden until it does. */
    val clozeAvailable: Boolean = false,
    /** An in-flight course wanting attention: the next lesson, or a checkup that has come due. */
    val courseNudge: CourseNudge? = null,
    /** A routine begun and not finished, offered back from the step it was left on. */
    val routine: ResumableRoutine? = null,
)

/**
 * What the Home screen says about a course. Deliberately just a label and a kind: the card opens
 * the hub rather than starting a sitting, so the learner sees the whole course's state before
 * committing fifteen minutes to it.
 */
data class CourseNudge(
    val courseTitle: String,
    val lessonTitle: String?,
    val checkupDue: Boolean,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    settingsRepo: SettingsRepository,
    statsRepo: FocusStatsRepository,
    private val conversationRepo: ConversationRepository,
    bookRepo: BookRepository,
    flashcardRepo: FlashcardRepository,
    audioRepo: AudioRepository,
    private val drillRepo: ConjugationDrillRepository,
    private val clozeRepo: ClozeRepository,
    courseRepo: CourseRepository,
    routineRepo: RoutineRepository,
) : ViewModel() {

    private data class Counts(val due: Int, val audio: Int, val unreadChats: Int, val cloze: Int)

    /**
     * The one course worth mentioning, if any. A due checkup outranks an unfinished lesson — decay
     * is time-sensitive in a way that the next lesson is not — and a course the learner has not
     * started says nothing at all: Home reports what is waiting, and an unopened course isn't.
     */
    private fun pickCourseNudge(courses: List<CourseOverview>): CourseNudge? {
        val now = System.currentTimeMillis()
        courses.firstOrNull { it.checkupDue(now) }?.let {
            return CourseNudge(it.definition.title, null, checkupDue = true)
        }
        return courses
            .filter { it.started && !it.graduated }
            .firstNotNullOfOrNull { overview ->
                overview.nextLesson?.let { CourseNudge(overview.definition.title, it.title, false) }
            }
    }

    val ui: StateFlow<HomeUi> =
        settingsRepo.activeProfileTag.flatMapLatest { lang ->
            combine(
                statsRepo.observeWeekly(lang),
                combine(
                    conversationRepo.observeLatestUnreadChat(),
                    conversationRepo.observeLatestUnfinishedText(),
                    bookRepo.observeBooks(lang).map { bookInProgress(it) },
                ) { chat, text, book -> Triple(chat, text, book) },
                combine(
                    flashcardRepo.observeDueCount(),
                    audioRepo.observeUnplayedReadyCount(),
                    conversationRepo.observeUnreadChatCount(),
                    clozeRepo.observeActiveCount(lang),
                ) { due, audio, unread, cloze -> Counts(due, audio, unread, cloze) },
                settingsRepo.settings.map { it.dailyGoalMinutes }.distinctUntilChanged(),
                // Paired up because combine stops at five flows; the two have nothing else in common.
                combine(courseRepo.observeOverviews(lang), routineRepo.observeResumable(lang)) { courses, routine ->
                    courses to routine
                },
            ) { stats, (chat, text, book), counts, goalMinutes, (courses, routine) ->
                HomeUi(
                    loaded = true,
                    stats = stats,
                    continueItems = pickContinue(chat, text, book),
                    dueCards = counts.due,
                    unplayedAudio = counts.audio,
                    unreadChats = counts.unreadChats,
                    latestUnreadChatId = chat?.id,
                    dailyGoalMinutes = goalMinutes,
                    conjugationAvailable = drillRepo.supports(lang),
                    clozeAvailable = counts.cloze > 0,
                    courseNudge = pickCourseNudge(courses),
                    routine = routine,
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUi())

    /**
     * The callouts worth offering, most recently touched first. The chat and the text compete for a
     * single slot — they are the same kind of thing, and two of them would read as a list of
     * everything rather than a place to resume — but the book gets its own, because losing that
     * race is exactly how a half-read book used to disappear from Home entirely.
     *
     * A chat's or text's snippet is the last assistant turn — for a text that's the translated
     * body, so both preview in the target language; a book's is its author, progress carried
     * separately.
     */
    private suspend fun pickContinue(
        chat: ConversationEntity?,
        text: ConversationEntity?,
        book: BookEntity?,
    ): List<ContinueItem> {
        val conversation = listOfNotNull(chat, text).maxByOrNull { it.updatedAt }
        val conversationItem = conversation?.let {
            val snippet = conversationRepo.lastAssistantMessage(it.id)?.content
                ?.trim()?.take(SNIPPET_CHARS)?.takeIf { body -> body.isNotBlank() }
            ContinueItem(
                id = it.id,
                title = it.title,
                snippet = snippet,
                kind = if (it === chat) ContinueKind.CHAT else ContinueKind.TEXT,
            )
        }
        return listOfNotNull(
            conversationItem?.let { it to conversation.updatedAt },
            book?.let { bookItem(it) to it.touchedAt },
        ).sortedByDescending { (_, touchedAt) -> touchedAt }.map { (item, _) -> item }
    }

    /** A book in progress as a callout: the author as the snippet, the saved position as progress. */
    private fun bookItem(book: BookEntity) = ContinueItem(
        id = book.id,
        title = book.title,
        snippet = book.author?.takeIf { it.isNotBlank() },
        kind = ContinueKind.BOOK,
        progressPercent = if (book.totalParagraphs > 0) {
            (book.posParagraph * 100 / book.totalParagraphs).coerceIn(0, 100)
        } else {
            null
        },
    )

    private companion object {
        const val SNIPPET_CHARS = 220
    }
}

/**
 * The book to offer as "continue reading": the one read most recently, among those opened and not
 * finished. A freshly imported book is deliberately not "continued" — nothing has been read yet,
 * and the Library is where a new book is started from.
 *
 * Ordered by [BookEntity.lastReadAt], not `updatedAt`: that one moves every time a section is
 * translated, so a book downloading in the background used to take the slot from the book actually
 * being read. A book with no recorded read time (last read before the column existed) ranks behind
 * any that has one, and among themselves those keep the old `updatedAt` order.
 */
internal fun bookInProgress(books: List<BookEntity>): BookEntity? =
    books
        .filter { !it.finishedReading && it.opened }
        .maxWithOrNull(compareBy<BookEntity> { it.lastReadAt ?: 0L }.thenBy { it.updatedAt })

/** Whether the reader has ever scrolled into this book — the saved position is past the start. */
private val BookEntity.opened: Boolean
    get() = posParagraph > 0 || posOffset > 0

/** When the reader last had this book open, as far as is known; see [bookInProgress]. */
private val BookEntity.touchedAt: Long
    get() = lastReadAt ?: updatedAt
