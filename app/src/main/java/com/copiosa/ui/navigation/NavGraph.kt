package com.copiosa.ui.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.copiosa.R
import com.copiosa.data.settings.AppSettings
import com.copiosa.domain.quiz.CueType
import com.copiosa.ui.batch.BatchViewModel
import com.copiosa.ui.components.BatchChatSettings
import com.copiosa.ui.components.BatchEntrySheet
import com.copiosa.ui.components.ExpandableFab
import com.copiosa.ui.components.FabAction
import com.copiosa.ui.components.TextEntrySheet
import com.copiosa.ui.audio.AudioScreen
import com.copiosa.ui.conversation.ConversationScreen
import com.copiosa.ui.flashcards.FlashcardsScreen
import com.copiosa.ui.flashcards.ReviewScreen
import com.copiosa.ui.flashcards.VocabImagesScreen
import com.copiosa.ui.flashcards.VocabularyDetailScreen
import com.copiosa.ui.library.LibraryScreen
import com.copiosa.ui.quiz.QuizPoolScreen
import com.copiosa.ui.quiz.RapidFireScreen
import com.copiosa.ui.routines.RoutinePlayerScreen
import com.copiosa.ui.routines.RoutineStatusScreen
import com.copiosa.ui.routines.RoutinesScreen
import com.copiosa.ui.settings.SettingsScreen
import com.copiosa.ui.settings.SettingsTab
import com.copiosa.ui.texts.TextReadingScreen
import com.copiosa.ui.texts.TextsEntryViewModel
import com.copiosa.ui.theme.skin.LocalAppSkin
import com.copiosa.ui.theme.skin.MotifBand
import kotlinx.coroutines.launch

@Composable
fun CopiosaNavGraph() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val isTopLevel = TopDestination.entries.any { it.route == currentRoute }

    val snackbarHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Batch entry (long-press the Chats +). The sheet lives here, above the screen VMs, so it can be
    // opened from the global FAB; the work itself runs in the app-scoped GenerationManager.
    val batchViewModel: BatchViewModel = hiltViewModel()
    val textsEntryViewModel: TextsEntryViewModel = hiltViewModel()
    val context = LocalContext.current
    var chatBatchSheet by remember { mutableStateOf(false) }
    var textEntrySheet by remember { mutableStateOf(false) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        bottomBar = {
            // The bottom bar only shows on the tab screens; the conversation view is full-screen.
            if (isTopLevel) {
                SkinnedBottomBar(
                    currentRoute = currentRoute,
                    onNavigate = { dest ->
                        navController.navigate(dest.route) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            // One FAB for the whole Library. Tap = a single new chat (the default); long-press expands to
            // that, a batch of many chats, and "Translate" (import a URL or paste text to read).
            if (currentRoute == Routes.LIBRARY) {
                ExpandableFab(
                    icon = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.new_chat),
                    onClick = { navController.navigate(Routes.newConversation()) },
                    actions = listOf(
                        FabAction(
                            label = stringResource(R.string.fab_new_chat),
                            icon = Icons.Filled.ChatBubbleOutline,
                        ) { navController.navigate(Routes.newConversation()) },
                        FabAction(
                            label = stringResource(R.string.fab_many_chats),
                            icon = Icons.Filled.LibraryAdd,
                        ) { chatBatchSheet = true },
                        FabAction(
                            label = stringResource(R.string.action_translate),
                            icon = Icons.Filled.Translate,
                        ) { textEntrySheet = true },
                    ),
                )
            }
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).consumeWindowInsets(padding)) {
        // The motif band sits directly under the status bar on tab screens; full-screen routes
        // (conversation, reader) bring their own chrome instead.
        val skin = LocalAppSkin.current
        if (isTopLevel && skin.motif != null) {
            MotifBand(skin.motif!!, Modifier.fillMaxWidth(), height = 10.dp)
        }
        NavHost(
            navController = navController,
            startDestination = Routes.LIBRARY,
            // Apply the scaffold's system-bar insets, and mark them consumed so a screen's own
            // imePadding() doesn't re-add the navigation-bar inset on top of them.
            modifier = Modifier.weight(1f),
        ) {
            composable(Routes.LIBRARY) {
                LibraryScreen(
                    onOpenConversation = { id -> navController.navigate(Routes.conversation(id)) },
                    onOpenText = { id -> navController.navigate(Routes.textReading(id)) },
                )
            }
            composable(Routes.LEXICON) {
                FlashcardsScreen(
                    onOpenDetail = { id -> navController.navigate(Routes.vocabDetail(id)) },
                    onOpenReview = { limit -> navController.navigate(Routes.review(limit)) },
                    onAddImages = { navController.navigate(Routes.VOCAB_IMAGES) },
                )
            }
            composable(Routes.VOCAB_IMAGES) {
                VocabImagesScreen(onBack = { navController.popBackStack() })
            }
            composable(
                route = Routes.REVIEW,
                arguments = listOf(navArgument("limit") { type = NavType.IntType }),
            ) {
                ReviewScreen(onBack = { navController.popBackStack() })
            }
            composable(
                route = Routes.VOCAB_DETAIL,
                arguments = listOf(navArgument("cardId") { type = NavType.LongType }),
            ) {
                VocabularyDetailScreen(
                    onBack = { navController.popBackStack() },
                    onOpenConversation = { id -> navController.navigate(Routes.conversation(id)) },
                )
            }
            composable(Routes.AUDIO) {
                AudioScreen()
            }
            composable(Routes.ROUTINES) {
                RoutinesScreen(
                    onStartRun = { runId -> navController.navigate(Routes.routinePlayer(runId)) },
                    onOpenStatus = { navController.navigate(Routes.ROUTINE_STATUS) },
                    onOpenRapidFire = { type -> navController.navigate(Routes.rapidFire(type)) },
                )
            }
            composable(
                route = Routes.RAPID_FIRE,
                arguments = listOf(
                    navArgument("type") {
                        type = NavType.StringType
                        defaultValue = CueType.NUMBER.name
                    },
                ),
            ) { entry ->
                val focusType = entry.arguments?.getString("type")
                    ?.let { runCatching { CueType.valueOf(it) }.getOrNull() }
                    ?: CueType.NUMBER
                RapidFireScreen(
                    focusType = focusType,
                    onBack = { navController.popBackStack() },
                    onOpenPool = { navController.navigate(Routes.rapidFirePool(focusType)) },
                )
            }
            composable(
                route = Routes.RAPID_FIRE_POOL,
                arguments = listOf(
                    navArgument("type") {
                        type = NavType.StringType
                        defaultValue = CueType.NUMBER.name
                    },
                ),
            ) {
                QuizPoolScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.ROUTINE_STATUS) {
                RoutineStatusScreen(onBack = { navController.popBackStack() })
            }
            composable(
                route = Routes.ROUTINE_PLAYER,
                arguments = listOf(navArgument("runId") { type = NavType.LongType }),
            ) {
                RoutinePlayerScreen(onExit = { navController.popBackStack() })
            }
            composable(Routes.SETTINGS) {
                SettingsScreen()
            }
            // The chat's "model settings" shortcut opens the same screen on the Models tab, with a back
            // arrow (the old standalone model sub-screen is gone — settings are tabbed now).
            composable(Routes.MODEL_SETTINGS) {
                SettingsScreen(
                    initialTab = SettingsTab.MODELS,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                route = Routes.CONVERSATION,
                arguments = listOf(navArgument("conversationId") { type = NavType.LongType }),
            ) {
                ConversationScreen(
                    onBack = { navController.popBackStack() },
                    onOpenModelSettings = { navController.navigate(Routes.MODEL_SETTINGS) },
                    onOpenVocab = { id -> navController.navigate(Routes.vocabDetail(id)) },
                )
            }
            composable(
                route = Routes.TEXT_READING,
                arguments = listOf(navArgument("conversationId") { type = NavType.LongType }),
            ) { entry ->
                val cid = entry.arguments?.getLong("conversationId") ?: Routes.DRAFT_ID
                TextReadingScreen(
                    onBack = { navController.popBackStack() },
                    onDiscuss = { navController.navigate(Routes.conversation(cid)) },
                    onOpenVocab = { id -> navController.navigate(Routes.vocabDetail(id)) },
                )
            }
        }
        }
    }

    if (chatBatchSheet) {
        val batchSettings by batchViewModel.settings.collectAsState()
        BatchEntrySheet(
            title = stringResource(R.string.batch_chats_title),
            helper = stringResource(R.string.batch_chats_helper),
            placeholder = stringResource(R.string.batch_chats_hint),
            settings = batchSettings?.let {
                BatchChatSettings(
                    model = AppSettings.displayName(it.activeModel),
                    level = it.defaultLevel.name,
                    country = it.defaultCountry,
                )
            },
            buttonLabel = { count ->
                context.resources.getQuantityString(R.plurals.batch_generate, count, count)
            },
            onSubmit = { topics ->
                batchViewModel.startChatBatch(topics)
                chatBatchSheet = false
            },
            onDismiss = { chatBatchSheet = false },
        )
    }

    if (textEntrySheet) {
        TextEntrySheet(
            onImportUrl = { url ->
                textEntrySheet = false
                scope.launch { navController.navigate(Routes.textReading(textsEntryViewModel.importUrl(url))) }
            },
            onImportText = { text ->
                textEntrySheet = false
                scope.launch { navController.navigate(Routes.textReading(textsEntryViewModel.importText(text))) }
            },
            onDismiss = { textEntrySheet = false },
        )
    }
}
