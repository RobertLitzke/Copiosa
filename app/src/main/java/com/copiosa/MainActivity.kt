package com.copiosa

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.copiosa.data.settings.AppSettings
import com.copiosa.data.settings.SettingsRepository
import com.copiosa.domain.model.ThemeMode
import com.copiosa.ui.navigation.CopiosaNavGraph
import com.copiosa.ui.navigation.LaunchAction
import com.copiosa.ui.theme.CopiosaTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import javax.inject.Inject

/**
 * Uses AppCompatActivity so per-app language switching via
 * [androidx.appcompat.app.AppCompatDelegate.setApplicationLocales] takes effect.
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    @Inject lateinit var settingsRepo: SettingsRepository

    /**
     * Destinations carried by intents — a share, a launcher shortcut, the review reminder — waiting
     * for the nav graph to perform them. A channel rather than a value: each is performed once, the
     * same shortcut twice in a row is still two actions, and one that arrives before the graph is up
     * is held for it.
     */
    private val launchActions = Channel<LaunchAction>(Channel.UNLIMITED)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Edge-to-edge on every API level so IME insets reach Compose (the window never resizes
        // out from under imePadding); without this the keyboard double-shifts the input bar.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Only a fresh start performs its intent. A recreated activity (rotation, a language switch,
        // a restore after process death) is handed the same one again, and would repeat the import.
        if (savedInstanceState == null) deliver(intent)
        val pendingActions = launchActions.receiveAsFlow()
        setContent {
            val settings by settingsRepo.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
            val darkTheme = when (settings.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            // Status-bar icon contrast must follow the resolved theme (setting + country palette),
            // not the OS mode that the parameterless enableEdgeToEdge() auto-detects.
            LaunchedEffect(darkTheme) {
                val style = if (darkTheme) {
                    SystemBarStyle.dark(Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            CopiosaTheme(
                darkTheme = darkTheme,
                readingFont = settings.readingFont,
                readingTextSize = settings.readingTextSize,
                persona = settings.persona,
                themeCountry = settings.themeCountry,
                profileLanguage = settings.targetLanguage,
            ) {
                CopiosaNavGraph(launchActions = pendingActions)
            }
        }
    }

    /**
     * An intent for an app that is already running (the activity is singleTop, so it arrives here
     * instead of stacking a second copy). Without this a shortcut or the reminder notification only
     * brought the app forward, wherever it had been left.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deliver(intent)
    }

    private fun deliver(intent: Intent?) {
        LaunchAction.from(intent)?.let(launchActions::trySend)
    }
}
