package com.copiosa.ui.conversation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.MarkChatUnread
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.copiosa.R
import com.copiosa.data.local.MessageEntity
import com.copiosa.data.settings.AppSettings
import com.copiosa.domain.model.Cefr
import com.copiosa.domain.model.ProviderId
import com.copiosa.domain.model.Role
import com.copiosa.domain.prompt.StructuredReplyParser
import com.copiosa.domain.stats.FocusActivityType
import com.copiosa.ui.components.ConfirmDeleteDialog
import com.copiosa.ui.components.HighlightWordsMenuItem
import com.copiosa.ui.components.Prompt
import com.copiosa.ui.components.ReaderTranslationCard
import com.copiosa.ui.components.SavedNotice
import com.copiosa.ui.components.SectionLabel
import com.copiosa.ui.components.TrackFocusTime
import com.copiosa.ui.components.SelectableMessage
import com.copiosa.ui.components.TranslationCard
import com.copiosa.ui.audio.PronounceButton
import com.copiosa.ui.audio.TtsDownloadPrompt
import com.copiosa.ui.components.WordColorizer
import com.copiosa.ui.components.llmErrorText
import com.copiosa.ui.components.rememberAudioRequestGate
import com.copiosa.ui.conversation.skin.PersonaChatHeader
import com.copiosa.ui.conversation.skin.PersonaInputBar
import com.copiosa.ui.conversation.skin.PersonaMessageItem
import com.copiosa.ui.conversation.skin.rememberChatSkin
import com.copiosa.ui.theme.skin.LocalAppSkin
import com.copiosa.ui.theme.skin.focusWordStyle
import com.copiosa.ui.theme.skin.messageEnter
import com.copiosa.ui.theme.skin.personaTexture
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun ConversationScreen(
    onBack: () -> Unit,
    onOpenModelSettings: () -> Unit,
    onOpenVocab: (Long) -> Unit,
    viewModel: ConversationViewModel = hiltViewModel(),
) {
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val config by viewModel.config.collectAsStateWithLifecycle()
    val activeId by viewModel.activeId.collectAsStateWithLifecycle()
    val starred by viewModel.starred.collectAsStateWithLifecycle()
    val conversationTitle by viewModel.title.collectAsStateWithLifecycle()
    val reading = viewModel.readingSelection
    val highlightTargets by reading.highlightTargets.collectAsStateWithLifecycle()
    val highlightWords by viewModel.highlightWords.collectAsStateWithLifecycle()
    val translate by reading.translate.collectAsStateWithLifecycle()
    val savedNotice by reading.savedNotice.collectAsStateWithLifecycle()
    val nextTopics by viewModel.nextTopics.collectAsStateWithLifecycle()
    val isTextMode by viewModel.isTextMode.collectAsStateWithLifecycle()
    // An imported text seeds the conversation with a hidden leading user turn; drop it from the chat
    // so the discussion reads as the translated text (assistant) followed by the learner's questions.
    val displayed = remember(messages, isTextMode) {
        if (isTextMode && messages.firstOrNull()?.role == Role.USER) messages.drop(1) else messages
    }
    val snackbarHost = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val addedToAudioText = stringResource(R.string.audio_added)
    val nothingToAddText = stringResource(R.string.audio_nothing_to_add)
    // Says what happened rather than what was asked for: an empty reply queues nothing.
    val notifyAudio: (queued: Boolean) -> Unit = { queued ->
        scope.launch { snackbarHost.showSnackbar(if (queued) addedToAudioText else nothingToAddText) }
    }
    val audioGate = rememberAudioRequestGate(viewModel::isTtsReady, viewModel::startTtsDownload)

    // Persona chat skin; null keeps the stock chat UI untouched.
    val chatSkin = rememberChatSkin()
    val appSkin = LocalAppSkin.current

    // Saved words are styled in the persona's own hand (the unstyled treatment is the stock
    // primary-color tint, so this is behavior-preserving without a persona).
    val focusStyle = remember(appSkin) { focusWordStyle(appSkin) }
    val colorizer = remember(highlightTargets, focusStyle) { WordColorizer(highlightTargets, focusStyle.span) }

    val errorText = ui.error?.let { llmErrorText(it) }

    // A dangling user turn with nothing in flight is a failed/awaited reply — offer an inline retry so
    // a chat that failed (e.g. quota) can still be opened from the list and recovered here.
    val canRetry = activeId != 0L && !ui.sending && displayed.lastOrNull()?.role == Role.USER

    // Dropping focus when the app is backgrounded keeps the keyboard from springing back open on
    // resume (Android otherwise re-shows the IME for the still-focused input field).
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { focusManager.clearFocus(force = true) }

    TrackFocusTime(FocusActivityType.READING)

    LaunchedEffect(errorText) {
        if (errorText != null) {
            snackbarHost.showSnackbar(errorText)
            viewModel.consumeError()
        }
    }
    // On a new reply, anchor the learner's question near the top so a long answer reads from its
    // start; otherwise (e.g. a just-sent question awaiting a reply) keep the latest turn in view.
    // A mid-conversation reply only moves the view if the user was already at the bottom — if
    // they've scrolled up to reread something, the arriving reply shouldn't yank them away.
    var seenCount by remember { mutableIntStateOf(0) }
    // Themed chats prepend the signature band as list item 0, shifting every message index by one.
    val listOffset = if (chatSkin != null) 1 else 0
    LaunchedEffect(displayed.size) {
        val previous = seenCount
        seenCount = displayed.size
        if (displayed.isEmpty()) return@LaunchedEffect
        val isReply = displayed.last().role == Role.ASSISTANT && displayed.size >= 2
        if (isReply && previous > 0) {
            // layoutInfo still reflects the pre-reply list, whose last message was the user's turn
            // at (previous - 1), possibly followed by the "Thinking…" row.
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            if (lastVisible < previous - 1 + listOffset) return@LaunchedEffect
        }
        listState.animateScrollToItem((if (isReply) displayed.lastIndex - 1 else displayed.lastIndex) + listOffset)
    }

    Box(modifier = Modifier.fillMaxSize().personaTexture(appSkin)) {
        Column(modifier = Modifier.fillMaxSize().imePadding()) {
            val headerTitle = conversationTitle.ifBlank { stringResource(R.string.new_chat) }
            if (chatSkin != null) {
                PersonaChatHeader(
                    chatSkin = chatSkin,
                    config = config,
                    title = headerTitle,
                    showMenu = activeId != 0L,
                    starred = starred,
                    highlightWords = highlightWords,
                    onBack = onBack,
                    onLevel = viewModel::setLevel,
                    onProvider = viewModel::setProvider,
                    onOpenModelSettings = onOpenModelSettings,
                    onToggleStar = viewModel::toggleStar,
                    onMarkUnread = viewModel::markUnread,
                    onToggleHighlightWords = viewModel::toggleHighlightWords,
                    onDelete = { showDeleteConfirm = true },
                )
            } else {
                ChatTopBar(
                    config = config,
                    title = headerTitle,
                    showMenu = activeId != 0L,
                    starred = starred,
                    highlightWords = highlightWords,
                    onBack = onBack,
                    onLevel = viewModel::setLevel,
                    onProvider = viewModel::setProvider,
                    onOpenModelSettings = onOpenModelSettings,
                    onToggleStar = viewModel::toggleStar,
                    onMarkUnread = viewModel::markUnread,
                    onToggleHighlightWords = viewModel::toggleHighlightWords,
                    onDelete = { showDeleteConfirm = true },
                )
            }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    // Tapping the message area (outside the input) dismisses the keyboard/cursor.
                    .pointerInput(Unit) { detectTapGestures { focusManager.clearFocus() } }
                    .padding(horizontal = chatSkin?.spec?.padH?.coerceAtLeast(10.dp) ?: 12.dp),
                verticalArrangement = Arrangement.spacedBy(chatSkin?.spec?.msgGap ?: 12.dp),
                contentPadding = PaddingValues(vertical = chatSkin?.spec?.padV ?: 8.dp),
            ) {
                // Re-fires the entry animation when the skin flips (persona/country/dark mode).
                val motionKey = Triple(appSkin.persona, appSkin.country, appSkin.dark)
                itemsIndexed(displayed, key = { _, m -> m.id }) { index, message ->
                    if (chatSkin != null) {
                        Box(Modifier.messageEnter(chatSkin.spec.motion, index = index + 1, themeKey = motionKey)) {
                            PersonaMessageItem(
                                message = message,
                                chatSkin = chatSkin,
                                index = index,
                                colorizer = colorizer,
                                isActiveSelection = ui.selectionMessageId == message.id,
                                onSelected = viewModel::onSelectionTranslate,
                                onCleared = viewModel::dismissTranslate,
                                onSendToAudio = { audioGate.request { notifyAudio(viewModel.sendToAudio(message.id)) } },
                                language = config.targetLanguage,
                            )
                        }
                    } else {
                        MessageBubble(
                            message = message,
                            colorizer = colorizer,
                            isActiveSelection = ui.selectionMessageId == message.id,
                            onSelected = viewModel::onSelectionTranslate,
                            onCleared = viewModel::dismissTranslate,
                            onSendToAudio = { audioGate.request { notifyAudio(viewModel.sendToAudio(message.id)) } },
                            language = config.targetLanguage,
                        )
                    }
                }
                if (ui.sending) {
                    item {
                        Text(
                            stringResource(R.string.thinking),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                } else if (canRetry) {
                    item { FailedReplyRow(onRetry = viewModel::retry) }
                }
                // Continuation-subject chips live at the end of the chat content, below the latest
                // reply (including an explanation), and scroll with it rather than riding the keyboard.
                if (nextTopics.isNotEmpty()) {
                    item(key = "next_topics") {
                        NextTopicsRow(topics = nextTopics, onTopic = viewModel::sendTopic)
                    }
                }
            }

            if (chatSkin != null) {
                PersonaInputBar(
                    chatSkin = chatSkin,
                    value = ui.input,
                    sending = ui.sending,
                    onValueChange = viewModel::setInput,
                    onSend = viewModel::send,
                    onCancel = viewModel::cancelSend,
                    onNewChat = viewModel::newChat,
                )
            } else {
                InputBar(
                    value = ui.input,
                    sending = ui.sending,
                    onValueChange = viewModel::setInput,
                    onSend = viewModel::send,
                    onCancel = viewModel::cancelSend,
                    onNewChat = viewModel::newChat,
                )
            }
        }
        SnackbarHost(snackbarHost, modifier = Modifier.align(Alignment.BottomCenter))

        SavedNotice(visible = savedNotice, onConsume = reading::consumeSavedNotice)
    }

    if (showDeleteConfirm) {
        ConfirmDeleteDialog(
            title = R.string.delete_conversation_title,
            message = R.string.delete_conversation_confirm,
            onConfirm = {
                showDeleteConfirm = false
                viewModel.delete()
                onBack()
            },
            onDismiss = { showDeleteConfirm = false },
        )
    }

    audioGate.Prompt()

    translate?.let { t ->
        val explainText = stringResource(R.string.explain_prompt, t.original)
        ReaderTranslationCard(
            state = t,
            selection = reading,
            onOpenVocab = onOpenVocab,
            // Chat's own wrappers: they also clear which bubble the selection lives in.
            onSave = { viewModel.saveCurrentTranslation(it) },
            onDismiss = viewModel::dismissTranslate,
            canExplain = activeId != 0L,
            onExplain = { viewModel.explain(explainText) },
            onSendToAudio = { audioGate.request { notifyAudio(viewModel.sendSelectionToAudio(t.original)) } },
        )
    }
}

@Composable
internal fun ChatTopBar(
    config: ChatConfig,
    title: String,
    showMenu: Boolean,
    starred: Boolean,
    highlightWords: Boolean,
    onBack: () -> Unit,
    onLevel: (Cefr) -> Unit,
    onProvider: (ProviderId) -> Unit,
    onOpenModelSettings: () -> Unit,
    onToggleStar: () -> Unit,
    onMarkUnread: () -> Unit,
    onToggleHighlightWords: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var overflowOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
        }
        Text(
            title,
            modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Box {
            ModelPill(config = config, onClick = { menuOpen = true })
            ModelMenu(
                expanded = menuOpen,
                config = config,
                onDismiss = { menuOpen = false },
                onLevel = onLevel,
                onProvider = onProvider,
                onOpenModelSettings = onOpenModelSettings,
            )
        }
        // The overflow menu only applies to a saved chat (a fresh draft has nothing to star/delete);
        // a fixed-width spacer keeps the layout stable when it's hidden.
        if (showMenu) {
            Box {
                IconButton(onClick = { overflowOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.cd_more_options))
                }
                ChatOverflowMenu(
                    expanded = overflowOpen,
                    starred = starred,
                    highlightWords = highlightWords,
                    onDismiss = { overflowOpen = false },
                    onToggleStar = onToggleStar,
                    onMarkUnread = onMarkUnread,
                    onToggleHighlightWords = onToggleHighlightWords,
                    onDelete = onDelete,
                )
            }
        } else {
            Spacer(Modifier.width(48.dp))
        }
    }
}

/** Overflow menu shown in an open chat's top bar: star, mark unread, highlighting, delete. */
@Composable
internal fun ChatOverflowMenu(
    expanded: Boolean,
    starred: Boolean,
    highlightWords: Boolean,
    onDismiss: () -> Unit,
    onToggleStar: () -> Unit,
    onMarkUnread: () -> Unit,
    onToggleHighlightWords: () -> Unit,
    onDelete: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(stringResource(if (starred) R.string.action_unstar else R.string.action_star)) },
            leadingIcon = {
                Icon(if (starred) Icons.Outlined.StarBorder else Icons.Filled.Star, contentDescription = null)
            },
            onClick = { onToggleStar(); onDismiss() },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_mark_unread)) },
            leadingIcon = { Icon(Icons.Filled.MarkChatUnread, contentDescription = null) },
            onClick = { onMarkUnread(); onDismiss() },
        )
        HighlightWordsMenuItem(
            checked = highlightWords,
            onToggle = onToggleHighlightWords,
            onDismiss = onDismiss,
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_delete)) },
            leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
            onClick = { onDelete(); onDismiss() },
        )
    }
}

/** Inline row offering to regenerate a reply when the last turn failed/was interrupted. */
@Composable
private fun FailedReplyRow(onRetry: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(R.string.chat_failed_reply),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRetry) {
            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.action_retry))
        }
    }
}

@Composable
internal fun ModelPill(config: ChatConfig, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                Icons.Filled.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
            Text(
                "${config.modelLabel} · ${config.level.name}",
                style = MaterialTheme.typography.labelLarge,
            )
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ModelMenu(
    expanded: Boolean,
    config: ChatConfig,
    onDismiss: () -> Unit,
    onLevel: (Cefr) -> Unit,
    onProvider: (ProviderId) -> Unit,
    onOpenModelSettings: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, modifier = Modifier.width(300.dp)) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SectionLabel(stringResource(R.string.model_menu_model))
            ProviderId.entries.forEach { p ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { onProvider(p) },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RadioButton(selected = p == config.provider, onClick = { onProvider(p) })
                    Column(modifier = Modifier.weight(1f)) {
                        Text(p.displayLabel(), style = MaterialTheme.typography.titleSmall)
                        if (p == config.provider) {
                            Text(
                                config.modelLabel,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            HorizontalDivider()
            SectionLabel(stringResource(R.string.model_menu_level))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Cefr.entries.forEach { lvl ->
                    LevelChip(label = lvl.name, selected = lvl == config.level, onClick = { onLevel(lvl) })
                }
            }

            HorizontalDivider()
            SectionLabel(stringResource(R.string.model_menu_variant))
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(variantText(config), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(
                    stringResource(R.string.model_menu_more) + " ›",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { onDismiss(); onOpenModelSettings() },
                )
            }
        }
    }
}

@Composable
private fun LevelChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = fg)
    }
}

private fun variantText(config: ChatConfig): String {
    val lang = Locale.forLanguageTag(config.targetLanguage)
        .getDisplayLanguage(Locale.getDefault())
        .replaceFirstChar { it.uppercase() }
    return "$lang · ${config.country}"
}

@Composable
private fun MessageBubble(
    message: MessageEntity,
    colorizer: WordColorizer,
    isActiveSelection: Boolean,
    onSelected: (Long, String, Float) -> Unit,
    onCleared: () -> Unit,
    onSendToAudio: () -> Unit,
    language: String,
) {
    if (message.role == Role.USER) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomEnd = 4.dp, bottomStart = 18.dp),
                modifier = Modifier.align(Alignment.TopEnd).fillMaxWidth(0.85f),
            ) {
                Text(
                    message.content,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
    } else {
        // Assistant replies render as selectable serif text; finishing/adjusting a selection opens
        // the on-device translation card while keeping the highlight in place.
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            SelectableMessage(
                content = message.content,
                colorizer = colorizer,
                isActive = isActiveSelection,
                onSelected = { text, anchorY -> onSelected(message.id, text, anchorY) },
                onCleared = onCleared,
            )
            AssistantActionRow(message = message, language = language, onSendToAudio = onSendToAudio)
        }
    }
}

/**
 * The action row under an assistant reply: play (read it aloud now, in [language]), headphones
 * (queue to on-device TTS in the Audio tab), a pill showing the level this reply was generated at, and an overflow revealing the rest of that
 * reply's settings and target-word debug info. Shared by the stock and persona chat layouts.
 */
@Composable
internal fun AssistantActionRow(message: MessageEntity, language: String, onSendToAudio: () -> Unit) {
    var showMeta by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        PronounceButton(
            text = message.content,
            langCode = language,
            iconSize = 18.dp,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        IconButton(onClick = onSendToAudio, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Filled.Headphones,
                contentDescription = stringResource(R.string.audio_send_to),
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        message.level?.let { MetaPill(it.name) }
        IconButton(onClick = { showMeta = true }, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Filled.MoreVert,
                contentDescription = stringResource(R.string.message_meta_more),
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (showMeta) {
        MessageMetaSheet(message = message, onDismiss = { showMeta = false })
    }
}

/** A small, non-interactive pill labeling a per-reply value (its CEFR level, or a debug word). */
@Composable
private fun MetaPill(label: String) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/**
 * Bottom sheet revealing the settings that produced a reply (country, model, level) plus target-word
 * debug info: the saved words we injected into the prompt, which of them actually appeared in the reply
 * (a ground-truth scan, not the model's guess), and — for recent chats — the raw model output. Sections
 * with no data are omitted, so older replies predate injected-word tracking and raw capture.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageMetaSheet(message: MessageEntity, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val meta = remember(message.metadata) { StructuredReplyParser.decode(message.metadata) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(stringResource(R.string.message_meta_title), style = MaterialTheme.typography.titleLarge)
            MetaTextRow(stringResource(R.string.message_meta_country), message.country)
            MetaTextRow(stringResource(R.string.message_meta_model), message.model?.let { AppSettings.displayName(it) })
            MetaTextRow(stringResource(R.string.message_meta_level), message.level?.name)
            MetaWordsRow(stringResource(R.string.message_meta_injected), meta.vocabSupplied)
            MetaWordsRow(stringResource(R.string.message_meta_used), meta.vocabUsed)
            MetaWordsRow(stringResource(R.string.message_meta_unused), meta.vocabUnused)
            MetaWordsRow(stringResource(R.string.message_meta_distinctive), meta.distinctive)
            RawResponseRow(message.rawResponse)
        }
    }
}

/**
 * The unparsed model output for this reply (body + trailing metadata block), shown as a debug aid.
 * Scrollable and selectable, with a copy button; kept only for recent chats, so it's absent (renders
 * nothing) on older or user turns.
 */
@Composable
private fun RawResponseRow(raw: String?) {
    if (raw.isNullOrBlank()) return
    val clipboard = LocalClipboardManager.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                stringResource(R.string.message_meta_raw),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IconButton(onClick = { clipboard.setText(AnnotatedString(raw)) }, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Filled.ContentCopy,
                    contentDescription = stringResource(R.string.action_copy),
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
            SelectionContainer {
                Text(
                    raw,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .heightIn(max = 260.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp),
                )
            }
        }
    }
}

/** One labeled single-value row in the metadata sheet; renders nothing when [value] is null/blank. */
@Composable
private fun MetaTextRow(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

/** A labeled wrap of word chips for one debug list; renders nothing when [words] is empty. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MetaWordsRow(label: String, words: List<String>) {
    if (words.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            words.forEach { MetaPill(it) }
        }
    }
}

/** Continuation-subject chips above the input; tapping one sends it as the next turn immediately. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NextTopicsRow(topics: List<String>, onTopic: (String) -> Unit) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        topics.forEach { topic ->
            SuggestionChip(onClick = { onTopic(topic) }, label = { Text(topic) })
        }
    }
}

@Composable
private fun InputBar(
    value: String,
    sending: Boolean,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit,
    onNewChat: () -> Unit,
) {
    fun trySend() {
        if (!sending && value.isNotBlank()) onSend()
    }
    Surface(tonalElevation = 2.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(onClick = onNewChat) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.new_chat))
            }
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .weight(1f)
                    // Hardware/IME Enter (without Shift) sends rather than inserting a newline.
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown &&
                            event.key == Key.Enter && !event.isShiftPressed
                        ) {
                            trySend(); true
                        } else {
                            false
                        }
                    },
                placeholder = { Text(stringResource(R.string.chat_hint)) },
                maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { trySend() }),
                shape = RoundedCornerShape(24.dp),
            )
            if (sending) {
                FilledIconButton(onClick = onCancel) {
                    Icon(Icons.Filled.Stop, contentDescription = stringResource(R.string.action_stop))
                }
            } else {
                FilledIconButton(onClick = onSend, enabled = value.isNotBlank()) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.action_send))
                }
            }
        }
    }
}
