package com.copiosa.ui.navigation

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.copiosa.R
import com.copiosa.domain.quiz.CueType
import com.copiosa.ui.batch.BatchViewModel
import com.copiosa.data.epub.ImportOptions
import com.copiosa.ui.books.BookImportDialog
import com.copiosa.ui.books.BookReadingScreen
import com.copiosa.ui.books.BooksEntryViewModel
import com.copiosa.ui.components.BatchChatSettings
import com.copiosa.ui.components.BatchEntrySheet
import com.copiosa.ui.components.ExpandableFab
import com.copiosa.ui.components.FabAction
import com.copiosa.ui.components.TextEntrySheet
import com.copiosa.ui.conversation.ConversationScreen
import com.copiosa.ui.vocab.VocabScreen
import com.copiosa.ui.vocab.ReviewScreen
import com.copiosa.ui.vocab.VocabImagesScreen
import com.copiosa.ui.vocab.VocabDetailScreen
import com.copiosa.ui.home.HomeScreen
import com.copiosa.ui.library.LibraryFilter
import com.copiosa.ui.audio.AudioTagScreen
import com.copiosa.ui.library.LibraryScreen
import com.copiosa.ui.cloze.ClozeBrowseScreen
import com.copiosa.ui.cloze.ClozeScreen
import com.copiosa.ui.listening.ListenRebuildScreen
import com.copiosa.ui.conjugation.ConjugationDrillScreen
import com.copiosa.ui.conjugation.ConjugationReferenceScreen
import com.copiosa.ui.course.CourseDetailScreen
import com.copiosa.ui.course.CourseHubScreen
import com.copiosa.ui.course.LessonPlayerScreen
import com.copiosa.ui.quiz.QuizPoolScreen
import com.copiosa.ui.quiz.RapidFireScreen
import com.copiosa.ui.reference.ReferenceHubScreen
import com.copiosa.ui.reference.ReferenceTopic
import com.copiosa.ui.reference.TenseReferenceScreen
import com.copiosa.ui.routines.RoutinePlayerScreen
import com.copiosa.ui.routines.RoutineStatusScreen
import com.copiosa.ui.routines.RoutinesScreen
import com.copiosa.ui.settings.SettingsScreen
import com.copiosa.ui.settings.SettingsTab
import com.copiosa.ui.stats.StatsScreen
import com.copiosa.ui.texts.TextReadingScreen
import com.copiosa.ui.texts.TextsEntryViewModel
import com.copiosa.ui.theme.skin.LocalAppSkin
import com.copiosa.ui.theme.skin.MotifBand
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

@Composable
fun CopiosaNavGraph(launchActions: Flow<LaunchAction> = emptyFlow()) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val isTopLevel = TopDestination.entries.any { it.route == currentRoute }

    val snackbarHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Batch entry (from the Library +'s dial). The sheet lives here, above the screen VMs, so it can
    // be opened from the global FAB; the work itself runs in the app-scoped GenerationManager.
    val batchViewModel: BatchViewModel = hiltViewModel()
    val textsEntryViewModel: TextsEntryViewModel = hiltViewModel()
    val booksEntryViewModel: BooksEntryViewModel = hiltViewModel()
    val context = LocalContext.current
    var chatBatchSheet by remember { mutableStateOf(false) }
    var textEntrySheet by remember { mutableStateOf(false) }

    // The Library FAB's speed-dial. Hoisted here so the scaffold body can dim itself and close the
    // dial on a tap anywhere outside it; leaving the Library closes it too.
    var fabExpanded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(currentRoute) { if (currentRoute != Routes.LIBRARY) fabExpanded = false }

    // EPUB import (FAB action and "open with"). Importing parses the whole book, so the picker's
    // result is handled here rather than in a screen VM that navigation could dispose. A picked
    // file waits in `pendingBookUri` while the dialog asks which language it is in.
    // The import in flight, if any; the progress dialog is up exactly while this is set.
    var bookImport by remember { mutableStateOf<Job?>(null) }
    var bookImportError by remember { mutableStateOf<String?>(null) }
    var pendingBookUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val importBook: (android.net.Uri) -> Unit = { uri -> pendingBookUri = uri }
    val startImport: (android.net.Uri, ImportOptions) -> Unit = { uri, options ->
        bookImport = scope.launch {
            val self = coroutineContext[Job]
            try {
                navController.navigate(Routes.bookReading(booksEntryViewModel.importEpub(uri, options)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                bookImportError = e.message
            } finally {
                // A cancelled import unwinds late (parsing has no suspension points), by which time
                // the slot may already belong to the next one.
                if (bookImport === self) bookImport = null
            }
        }
    }
    // Cancelling drops the dialog at once rather than when the work notices. An import stopped before
    // its rows are committed leaves nothing behind; one that had already committed leaves the book in
    // the Library, just not opened — its translation starts when the reader is.
    val cancelBookImport: () -> Unit = {
        bookImport?.cancel()
        bookImport = null
    }
    // OpenDocument with a wildcard: file managers routinely report .epub as octet-stream, so a
    // narrow MIME filter would grey out the very files we want (the Anki-import precedent).
    val epubPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(importBook)
    }

    // Perform each share/shortcut action once the graph is up — the launching intent's, and any that
    // reach the running app afterwards. MainActivity sees to it that a recreation re-sends nothing.
    LaunchedEffect(launchActions) {
        launchActions.collect { action ->
            when (action) {
                is LaunchAction.ShareUrl ->
                    navController.navigate(Routes.textReading(textsEntryViewModel.importUrl(action.url)))
                is LaunchAction.ShareText ->
                    navController.navigate(Routes.textReading(textsEntryViewModel.importText(action.text)))
                is LaunchAction.OpenEpub -> importBook(action.uri)
                // singleTop: a shortcut tapped while its screen is already showing has nowhere to go.
                is LaunchAction.OpenRoute -> navController.navigate(action.route) { launchSingleTop = true }
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        bottomBar = {
            // The bottom bar only shows on the tab screens; the conversation view is full-screen.
            if (isTopLevel) {
                SkinnedBottomBar(
                    currentRoute = currentRoute,
                    onNavigate = { dest ->
                        navController.navigate(dest.navTarget) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            // One FAB for the whole Library. Tap opens the dial — a new chat, a batch of many chats,
            // "Translate" (import a URL or paste text to read), and an EPUB import; long-press is a
            // shortcut straight to a single new chat, the commonest of the four.
            if (currentRoute == Routes.LIBRARY) {
                ExpandableFab(
                    icon = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.library_add),
                    onClick = { navController.navigate(Routes.newConversation()) },
                    expanded = fabExpanded,
                    onExpandedChange = { fabExpanded = it },
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
                        FabAction(
                            label = stringResource(R.string.fab_import_book),
                            icon = Icons.AutoMirrored.Filled.MenuBook,
                        ) { epubPicker.launch(arrayOf("*/*")) },
                    ),
                )
            }
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding).consumeWindowInsets(padding)) {
        Column(modifier = Modifier.fillMaxSize()) {
        // The motif band sits directly under the status bar on tab screens; full-screen routes
        // (conversation, reader) bring their own chrome instead.
        val skin = LocalAppSkin.current
        if (isTopLevel && skin.motif != null) {
            MotifBand(skin.motif!!, Modifier.fillMaxWidth(), height = 10.dp)
        }
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            // Apply the scaffold's system-bar insets, and mark them consumed so a screen's own
            // imePadding() doesn't re-add the navigation-bar inset on top of them.
            modifier = Modifier.weight(1f),
        ) {
            composable(Routes.HOME) {
                HomeScreen(
                    onOpenConversation = { id -> navController.navigate(Routes.conversation(id)) },
                    onOpenText = { id -> navController.navigate(Routes.textReading(id)) },
                    onOpenBook = { id -> navController.navigate(Routes.bookReading(id)) },
                    onOpenReview = { limit -> navController.navigate(Routes.review(limit)) },
                    onOpenRapidFire = { navController.navigate(Routes.rapidFire(CueType.NUMBER)) },
                    onOpenStats = { navController.navigate(Routes.STATS) },
                    onOpenReference = { navController.navigate(Routes.REFERENCE) },
                    onOpenConjugation = { navController.navigate(Routes.CONJUGATION_DRILL) },
                    onOpenCloze = { navController.navigate(Routes.CLOZE) },
                    onOpenCourses = { navController.navigate(Routes.COURSES) },
                    onOpenNewChat = { navController.navigate(Routes.newConversation()) },
                    onOpenRoutine = { runId -> navController.navigate(Routes.routinePlayer(runId)) },
                    // Jumps straight to the Library's Audio pane. Deliberately no restoreState:
                    // a restored Library would resurrect its previously-saved filter instead.
                    onOpenAudio = {
                        navController.navigate(Routes.library(LibraryFilter.AUDIO)) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                        }
                    },
                )
            }
            composable(
                route = Routes.LIBRARY,
                arguments = listOf(
                    navArgument("filter") {
                        type = NavType.StringType
                        defaultValue = LibraryFilter.ALL.name
                    },
                ),
            ) {
                LibraryScreen(
                    onOpenConversation = { id -> navController.navigate(Routes.conversation(id)) },
                    onOpenText = { id -> navController.navigate(Routes.textReading(id)) },
                    onOpenBook = { id -> navController.navigate(Routes.bookReading(id)) },
                    onOpenAudioTag = { tag -> navController.navigate(Routes.audioTag(tag)) },
                    // The empty list's buttons: the same three ways in as the FAB's dial.
                    onNewChat = { navController.navigate(Routes.newConversation()) },
                    onAddText = { textEntrySheet = true },
                    onImportBook = { epubPicker.launch(arrayOf("*/*")) },
                )
            }
            composable(
                route = Routes.AUDIO_TAG,
                arguments = listOf(navArgument("tag") { type = NavType.StringType }),
            ) {
                AudioTagScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.VOCAB) {
                VocabScreen(
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
                arguments = listOf(
                    navArgument("limit") { type = NavType.IntType },
                    // 0 = an ordinary session; anything higher scopes it to the frequency drill.
                    navArgument("freqTop") { type = NavType.IntType; defaultValue = 0 },
                ),
            ) {
                ReviewScreen(
                    onBack = { navController.popBackStack() },
                    onOpenDetail = { id -> navController.navigate(Routes.vocabDetail(id)) },
                    onPractiseInSentences = { ids -> navController.navigate(Routes.clozeFor(ids)) },
                )
            }
            composable(
                route = Routes.VOCAB_DETAIL,
                arguments = listOf(navArgument("cardId") { type = NavType.LongType }),
            ) {
                VocabDetailScreen(
                    onBack = { navController.popBackStack() },
                    onOpenConversation = { id -> navController.navigate(Routes.conversation(id)) },
                    onOpenText = { id -> navController.navigate(Routes.textReading(id)) },
                    onOpenBook = { id -> navController.navigate(Routes.bookReading(id)) },
                    onPractiseInSentences = { id -> navController.navigate(Routes.clozeFor(listOf(id))) },
                )
            }
            composable(Routes.ROUTINES) {
                RoutinesScreen(
                    onStartRun = { runId -> navController.navigate(Routes.routinePlayer(runId)) },
                    onOpenStatus = { navController.navigate(Routes.ROUTINE_STATUS) },
                    onOpenRapidFire = { type -> navController.navigate(Routes.rapidFire(type)) },
                    onOpenConjugationDrill = { navController.navigate(Routes.CONJUGATION_DRILL) },
                    onOpenCloze = { navController.navigate(Routes.CLOZE) },
                    onOpenListenRebuild = { navController.navigate(Routes.LISTEN_REBUILD) },
                    onOpenFrequencyDrill = { size, maxRank ->
                        navController.navigate(Routes.review(size, maxRank))
                    },
                    onOpenCourses = { navController.navigate(Routes.COURSES) },
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
            composable(
                route = Routes.CLOZE_ROUTE,
                arguments = listOf(
                    navArgument("focus") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) {
                ClozeScreen(
                    onBack = { navController.popBackStack() },
                    onOpenAudioTag = { tag -> navController.navigate(Routes.audioTag(tag)) },
                    onBrowse = { navController.navigate(Routes.CLOZE_BROWSE) },
                )
            }
            composable(Routes.CLOZE_BROWSE) {
                ClozeBrowseScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.LISTEN_REBUILD) {
                ListenRebuildScreen(
                    onBack = { navController.popBackStack() },
                    onOpenCloze = { navController.navigate(Routes.CLOZE) },
                )
            }

            composable(Routes.CONJUGATION_DRILL) {
                ConjugationDrillScreen(
                    onBack = { navController.popBackStack() },
                    onOpenReference = { verb ->
                        navController.navigate(Routes.referenceTopic(ReferenceTopic.CONJUGATION, verb))
                    },
                )
            }
            composable(Routes.COURSES) {
                CourseHubScreen(
                    onBack = { navController.popBackStack() },
                    onOpenCourse = { courseId -> navController.navigate(Routes.courseDetail(courseId)) },
                )
            }
            composable(
                route = Routes.COURSE_DETAIL,
                arguments = listOf(navArgument("courseId") { type = NavType.StringType }),
            ) {
                CourseDetailScreen(
                    onBack = { navController.popBackStack() },
                    onOpenSession = { sessionId -> navController.navigate(Routes.courseSession(sessionId)) },
                )
            }
            composable(
                route = Routes.COURSE_SESSION,
                arguments = listOf(navArgument("sessionId") { type = NavType.LongType }),
            ) {
                LessonPlayerScreen(
                    onExit = { navController.popBackStack() },
                    // The replacement takes this entry's place, so backing out of a restarted
                    // lesson still lands on the hub rather than on the sitting that was discarded.
                    onRestart = { sessionId ->
                        navController.navigate(Routes.courseSession(sessionId)) {
                            popUpTo(Routes.COURSE_SESSION) { inclusive = true }
                        }
                    },
                )
            }
            composable(Routes.REFERENCE) {
                ReferenceHubScreen(
                    onBack = { navController.popBackStack() },
                    onOpenTopic = { topic -> navController.navigate(Routes.referenceTopic(topic)) },
                )
            }
            // One entry for the whole section: the topic slug picks the screen, so adding a topic is
            // an enum constant and a branch rather than another route.
            composable(
                route = Routes.REFERENCE_TOPIC,
                arguments = listOf(
                    navArgument("topic") { type = NavType.StringType },
                    navArgument("focus") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) { entry ->
                val back = { navController.popBackStack(); Unit }
                when (ReferenceTopic.fromSlug(entry.arguments?.getString("topic"))) {
                    ReferenceTopic.CONJUGATION -> ConjugationReferenceScreen(
                        onBack = back,
                        currentVerb = entry.arguments?.getString("focus"),
                    )
                    ReferenceTopic.TENSES -> TenseReferenceScreen(onBack = back)
                    // Only reachable from a link to a topic that no longer exists; the hub lists
                    // what does.
                    null -> ReferenceHubScreen(
                        onBack = back,
                        onOpenTopic = { topic -> navController.navigate(Routes.referenceTopic(topic)) },
                    )
                }
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
            composable(Routes.STATS) {
                StatsScreen(onBack = { navController.popBackStack() })
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
            composable(
                route = Routes.BOOK_READING,
                arguments = listOf(navArgument("bookId") { type = NavType.LongType }),
            ) {
                BookReadingScreen(
                    onBack = { navController.popBackStack() },
                    onOpenVocab = { id -> navController.navigate(Routes.vocabDetail(id)) },
                )
            }
        }
        }
        // Dims the screen while the FAB's dial is open, and a tap anywhere on it closes the dial.
        // Drawn after the column so it sits on top of the content; the FAB itself lives in the
        // scaffold's own slot, above both, so it stays tappable.
        if (fabExpanded) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
                    .pointerInput(Unit) { detectTapGestures { fabExpanded = false } },
            )
        }
        }
    }

    // Ask before parsing: the questions (which language, what level) need no book metadata, and the
    // answers decide whether importing costs an LLM call at all. Waits for the settings snapshot,
    // which is what supplies the two language names the question is phrased in.
    pendingBookUri?.let { uri ->
        val defaults by booksEntryViewModel.importDefaults.collectAsStateWithLifecycle()
        defaults?.let { d ->
            BookImportDialog(
                defaults = d,
                onConfirm = { options ->
                    pendingBookUri = null
                    startImport(uri, options)
                },
                onDismiss = { pendingBookUri = null },
            )
        }
    }

    if (bookImport != null) {
        AlertDialog(
            // Only the button cancels: a stray tap outside must not throw away a long import.
            onDismissRequest = {},
            confirmButton = {
                TextButton(onClick = cancelBookImport) { Text(stringResource(R.string.action_cancel)) }
            },
            title = { Text(stringResource(R.string.book_importing)) },
            text = { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) },
        )
    }

    bookImportError?.let { message ->
        AlertDialog(
            onDismissRequest = { bookImportError = null },
            confirmButton = {
                TextButton(onClick = { bookImportError = null }) { Text(stringResource(R.string.action_ok)) }
            },
            title = { Text(stringResource(R.string.book_import_failed)) },
            text = { Text(message) },
        )
    }

    if (chatBatchSheet) {
        val batchSettings by batchViewModel.settings.collectAsStateWithLifecycle()
        BatchEntrySheet(
            title = stringResource(R.string.batch_chats_title),
            helper = stringResource(R.string.batch_chats_helper),
            placeholder = stringResource(R.string.batch_chats_hint),
            settings = batchSettings?.let {
                BatchChatSettings(
                    model = it.label(it.activeModel),
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
