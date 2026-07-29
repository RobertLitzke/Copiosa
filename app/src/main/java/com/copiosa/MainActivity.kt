package com.copiosa

import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.copiosa.data.settings.AppSettings
import com.copiosa.data.settings.SettingsRepository
import com.copiosa.domain.model.ThemeMode
import com.copiosa.ui.navigation.CopiosaNavGraph
import com.copiosa.ui.navigation.LaunchAction
import com.copiosa.ui.theme.CopiosaTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Uses AppCompatActivity so per-app language switching via
 * [androidx.appcompat.app.AppCompatDelegate.setApplicationLocales] takes effect.
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    @Inject lateinit var settingsRepo: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        // Edge-to-edge on every API level so IME insets reach Compose (the window never resizes
        // out from under imePadding); without this the keyboard double-shifts the input bar.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // A share or launcher shortcut carries a destination; parsed once here, performed by the nav
        // graph (guarded there so an activity recreation doesn't re-run the import).
        val launchAction = LaunchAction.from(intent)
        setContent {
            val settings by settingsRepo.settings.collectAsState(initial = AppSettings())
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
                CopiosaNavGraph(launchAction = launchAction)
            }
        }
    }
}
