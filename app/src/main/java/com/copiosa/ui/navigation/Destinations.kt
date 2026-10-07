package com.copiosa.ui.navigation

import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Tune
import androidx.compose.ui.graphics.vector.ImageVector
import com.copiosa.R
import com.copiosa.domain.quiz.CueType
import com.copiosa.ui.library.LibraryFilter
import com.copiosa.ui.reference.ReferenceTopic

object Routes {
    const val HOME = "home"
    const val LIBRARY = "library?filter={filter}"
    /** The Library tab's plain address (no filter arg) — what the bottom bar navigates to. */
    const val LIBRARY_BASE = "library"
    const val VOCAB = "vocab"
    /** A review session. `freqTop` > 0 restricts it to words inside that frequency rank. */
    const val REVIEW = "vocab/review/{limit}?freqTop={freqTop}"
    const val VOCAB_DETAIL = "vocab/card/{cardId}"
    const val VOCAB_IMAGES = "vocab/images"
    const val ROUTINES = "routines"
    const val ROUTINE_STATUS = "routines/status"
    const val ROUTINE_PLAYER = "routines/run/{runId}"
    const val RAPID_FIRE = "routines/rapidfire?type={type}"
    const val RAPID_FIRE_POOL = "routines/rapidfire/pool?type={type}"
    const val CONJUGATION_DRILL = "routines/conjugation"
    const val CLOZE = "routines/cloze"
    const val LISTEN_REBUILD = "routines/rebuild"

    /** The tense-course hub: every authored course for the profile's language. */
    const val COURSES = "courses"

    /** One course: its hero, its progress, and its lessons grouped into parts. */
    const val COURSE_DETAIL = "courses/{courseId}"

    /** One course sitting (lesson, checkup, or micro-lesson), played by the lesson player. */
    const val COURSE_SESSION = "courses/session/{sessionId}"

    /** The reference section's hub — every topic the app can explain. */
    const val REFERENCE = "reference"

    /**
     * One reference topic, addressed by its [com.copiosa.ui.reference.ReferenceTopic] slug.
     *
     * `focus` is an optional item for the topic to open on, so a screen can hand its context across:
     * today that is the verb a drill card was asking about, and it is named generally because the next
     * topic's equivalent won't be a verb.
     */
    const val REFERENCE_TOPIC = "reference/{topic}?focus={focus}"

    const val SETTINGS = "settings"
    const val MODEL_SETTINGS = "settings/model"
    const val STATS = "stats"
    const val CONVERSATION = "conversation/{conversationId}"
    const val TEXT_READING = "text/{conversationId}"
    const val BOOK_READING = "book/{bookId}"

    /** One audio folder's tracks. The tag *is* the key, so it travels URL-encoded in the path. */
    const val AUDIO_TAG = "audio/tag/{tag}"

    /** Sentinel id for an unsaved draft chat (see ConversationViewModel.DRAFT). */
    const val DRAFT_ID = 0L

    fun library(filter: LibraryFilter) = "library?filter=${filter.name}"
    fun conversation(id: Long) = "conversation/$id"
    fun newConversation() = conversation(DRAFT_ID)
    fun textReading(id: Long) = "text/$id"
    fun bookReading(id: Long) = "book/$id"
    fun audioTag(tag: String) = "audio/tag/${Uri.encode(tag)}"
    fun vocabDetail(cardId: Long) = "vocab/card/$cardId"

    /** A reference topic, optionally opened on one item — see [REFERENCE_TOPIC]. */
    fun referenceTopic(topic: ReferenceTopic, focus: String? = null) =
        "reference/${topic.slug}" + if (focus.isNullOrBlank()) "" else "?focus=${Uri.encode(focus)}"
    fun review(limit: Int, freqTop: Int = 0) = "vocab/review/$limit?freqTop=$freqTop"
    fun routinePlayer(runId: Long) = "routines/run/$runId"
    fun courseDetail(courseId: String) = "courses/${Uri.encode(courseId)}"
    fun courseSession(sessionId: Long) = "courses/session/$sessionId"
    fun rapidFire(type: CueType) = "routines/rapidfire?type=${type.name}"
    fun rapidFirePool(type: CueType) = "routines/rapidfire/pool?type=${type.name}"
}

/** A tab in the bottom navigation bar. Enum order is tab order. */
enum class TopDestination(
    /** The route *pattern*, matched against the back stack for selection/top-level checks. */
    val route: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
    /** What the bar navigates to — differs from [route] only when the pattern carries an arg. */
    val navTarget: String = route,
) {
    HOME(Routes.HOME, R.string.nav_home, Icons.Filled.Home),
    LIBRARY(Routes.LIBRARY, R.string.nav_library, Icons.AutoMirrored.Filled.LibraryBooks, Routes.LIBRARY_BASE),
    VOCAB(Routes.VOCAB, R.string.nav_vocab, Icons.Filled.Book),
    ROUTINES(Routes.ROUTINES, R.string.nav_routines, Icons.Filled.Bedtime),
    SETTINGS(Routes.SETTINGS, R.string.nav_settings, Icons.Filled.Tune),
}
