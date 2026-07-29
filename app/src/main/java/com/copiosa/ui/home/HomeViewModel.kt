package com.copiosa.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.copiosa.data.local.ConversationEntity
import com.copiosa.data.repository.AudioRepository
import com.copiosa.data.repository.ConversationRepository
import com.copiosa.data.repository.FlashcardRepository
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

/** The "continue reading" callout: the newest unread chat or unfinished text. */
data class ContinueItem(
    val id: Long,
    val title: String,
    val snippet: String?,
    /** True for a translated text (opens the reader); false for a chat (opens the conversation). */
    val isText: Boolean,
)

data class HomeUi(
    val loaded: Boolean = false,
    val stats: WeeklyFocusStats? = null,
    val continueReading: ContinueItem? = null,
    val dueCards: Int = 0,
    val unplayedAudio: Int = 0,
    val unreadChats: Int = 0,
    /** Target of the "unread conversation" card (the newest unread chat). */
    val latestUnreadChatId: Long? = null,
    /** Daily study goal (minutes) driving the tracker's target line. */
    val dailyGoalMinutes: Int = AppSettings.DEFAULT_DAILY_GOAL_MINUTES,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    settingsRepo: SettingsRepository,
    statsRepo: FocusStatsRepository,
    private val conversationRepo: ConversationRepository,
    flashcardRepo: FlashcardRepository,
    audioRepo: AudioRepository,
) : ViewModel() {

    private data class Counts(val due: Int, val audio: Int, val unreadChats: Int)

    val ui: StateFlow<HomeUi> =
        settingsRepo.activeProfileTag.flatMapLatest { lang ->
            combine(
                statsRepo.observeWeekly(lang),
                combine(
                    conversationRepo.observeLatestUnreadChat(),
                    conversationRepo.observeLatestUnfinishedText(),
                ) { chat, text -> chat to text },
                combine(
                    flashcardRepo.observeDueCount(),
                    audioRepo.observeUnplayedReadyCount(),
                    conversationRepo.observeUnreadChatCount(),
                ) { due, audio, unread -> Counts(due, audio, unread) },
                settingsRepo.settings.map { it.dailyGoalMinutes }.distinctUntilChanged(),
            ) { stats, (chat, text), counts, goalMinutes ->
                HomeUi(
                    loaded = true,
                    stats = stats,
                    continueReading = pickContinue(chat, text),
                    dueCards = counts.due,
                    unplayedAudio = counts.audio,
                    unreadChats = counts.unreadChats,
                    latestUnreadChatId = chat?.id,
                    dailyGoalMinutes = goalMinutes,
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUi())

    /**
     * Whichever of the unread chat / unfinished text was touched more recently. The snippet is the
     * last assistant turn — for a text that's the translated body, so both preview in the target
     * language.
     */
    private suspend fun pickContinue(
        chat: ConversationEntity?,
        text: ConversationEntity?,
    ): ContinueItem? {
        val winner = listOfNotNull(chat, text).maxByOrNull { it.updatedAt } ?: return null
        val snippet = conversationRepo.lastAssistantMessage(winner.id)?.content
            ?.trim()?.take(SNIPPET_CHARS)?.takeIf { it.isNotBlank() }
        return ContinueItem(
            id = winner.id,
            title = winner.title,
            snippet = snippet,
            isText = winner !== chat,
        )
    }

    private companion object {
        const val SNIPPET_CHARS = 220
    }
}
