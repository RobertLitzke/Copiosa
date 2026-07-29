package com.copiosa.ui.navigation

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

object Routes {
    const val HOME = "home"
    const val LIBRARY = "library?filter={filter}"
    /** The Library tab's plain address (no filter arg) — what the bottom bar navigates to. */
    const val LIBRARY_BASE = "library"
    const val LEXICON = "lexicon"
    const val REVIEW = "lexicon/review/{limit}"
    const val VOCAB_DETAIL = "lexicon/card/{cardId}"
    const val VOCAB_IMAGES = "lexicon/images"
    const val ROUTINES = "routines"
    const val ROUTINE_STATUS = "routines/status"
    const val ROUTINE_PLAYER = "routines/run/{runId}"
    const val RAPID_FIRE = "routines/rapidfire?type={type}"
    const val RAPID_FIRE_POOL = "routines/rapidfire/pool?type={type}"
    const val CONJUGATION_DRILL = "routines/conjugation"
    const val CONJUGATION_REFERENCE = "routines/conjugation/reference?verb={verb}"
    const val SETTINGS = "settings"
    const val MODEL_SETTINGS = "settings/model"
    const val STATS = "stats"
    const val CONVERSATION = "conversation/{conversationId}"
    const val TEXT_READING = "text/{conversationId}"
    const val BOOK_READING = "book/{bookId}"

    /** Sentinel id for an unsaved draft chat (see ConversationViewModel.DRAFT). */
    const val DRAFT_ID = 0L

    fun library(filter: LibraryFilter) = "library?filter=${filter.name}"
    fun conversation(id: Long) = "conversation/$id"
    fun newConversation() = conversation(DRAFT_ID)
    fun textReading(id: Long) = "text/$id"
    fun bookReading(id: Long) = "book/$id"
    fun vocabDetail(cardId: Long) = "lexicon/card/$cardId"

    /** The reference, optionally opened on the verb a drill card is asking about. */
    fun conjugationReference(verb: String?) =
        "routines/conjugation/reference" + if (verb.isNullOrBlank()) "" else "?verb=$verb"
    fun review(limit: Int) = "lexicon/review/$limit"
    fun routinePlayer(runId: Long) = "routines/run/$runId"
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
    LEXICON(Routes.LEXICON, R.string.nav_lexicon, Icons.Filled.Book),
    ROUTINES(Routes.ROUTINES, R.string.nav_routines, Icons.Filled.Bedtime),
    SETTINGS(Routes.SETTINGS, R.string.nav_settings, Icons.Filled.Tune),
}
