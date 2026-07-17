package com.copiosa.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Tune
import androidx.compose.ui.graphics.vector.ImageVector
import com.copiosa.R
import com.copiosa.domain.quiz.CueType

object Routes {
    const val LIBRARY = "library"
    const val LEXICON = "lexicon"
    const val REVIEW = "lexicon/review/{limit}"
    const val VOCAB_DETAIL = "lexicon/card/{cardId}"
    const val VOCAB_IMAGES = "lexicon/images"
    const val AUDIO = "audio"
    const val ROUTINES = "routines"
    const val ROUTINE_STATUS = "routines/status"
    const val ROUTINE_PLAYER = "routines/run/{runId}"
    const val RAPID_FIRE = "routines/rapidfire?type={type}"
    const val RAPID_FIRE_POOL = "routines/rapidfire/pool?type={type}"
    const val SETTINGS = "settings"
    const val MODEL_SETTINGS = "settings/model"
    const val CONVERSATION = "conversation/{conversationId}"
    const val TEXT_READING = "text/{conversationId}"

    /** Sentinel id for an unsaved draft chat (see ConversationViewModel.DRAFT). */
    const val DRAFT_ID = 0L

    fun conversation(id: Long) = "conversation/$id"
    fun newConversation() = conversation(DRAFT_ID)
    fun textReading(id: Long) = "text/$id"
    fun vocabDetail(cardId: Long) = "lexicon/card/$cardId"
    fun review(limit: Int) = "lexicon/review/$limit"
    fun routinePlayer(runId: Long) = "routines/run/$runId"
    fun rapidFire(type: CueType) = "routines/rapidfire?type=${type.name}"
    fun rapidFirePool(type: CueType) = "routines/rapidfire/pool?type=${type.name}"
}

/** A tab in the bottom navigation bar. */
enum class TopDestination(
    val route: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
) {
    LIBRARY(Routes.LIBRARY, R.string.nav_library, Icons.AutoMirrored.Filled.LibraryBooks),
    LEXICON(Routes.LEXICON, R.string.nav_lexicon, Icons.Filled.Book),
    AUDIO(Routes.AUDIO, R.string.nav_audio, Icons.Filled.Headphones),
    ROUTINES(Routes.ROUTINES, R.string.nav_routines, Icons.Filled.Bedtime),
    SETTINGS(Routes.SETTINGS, R.string.nav_settings, Icons.Filled.Tune),
}
