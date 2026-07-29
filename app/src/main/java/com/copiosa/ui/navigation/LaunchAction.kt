package com.copiosa.ui.navigation

import android.content.Intent
import android.net.Uri
import com.copiosa.data.maintenance.ReviewReminderNotifier

/**
 * A navigation-worthy action carried by the launching intent — a shared URL/text to import, or a
 * launcher-shortcut destination. Parsed once in MainActivity and handed to [CopiosaNavGraph], which
 * performs it after the nav host is up.
 */
sealed interface LaunchAction {
    /** A shared URL: import it as a reading text and open the reader. */
    data class ShareUrl(val url: String) : LaunchAction

    /** Shared plain text: import it as a reading text and open the reader. */
    data class ShareText(val text: String) : LaunchAction

    /** An .epub opened from a file manager: import it and open the book reader. */
    data class OpenEpub(val uri: Uri) : LaunchAction

    /** A launcher shortcut: navigate straight to [route]. */
    data class OpenRoute(val route: String) : LaunchAction

    companion object {
        // Shared with the due-review reminder notification's content intent.
        private const val ACTION_REVIEW = ReviewReminderNotifier.ACTION_OPEN_REVIEW
        private const val ACTION_NEW_CHAT = "com.copiosa.action.NEW_CHAT"
        private const val ACTION_ROUTINE = "com.copiosa.action.ROUTINE"

        /** The default standalone review-session size (mirrors ReviewViewModel.DEFAULT_LIMIT). */
        private const val REVIEW_LIMIT = 20

        /** Maps the launching [intent] to an action, or null for a plain launch. */
        fun from(intent: Intent?): LaunchAction? = when (intent?.action) {
            Intent.ACTION_SEND -> if (intent.type == "text/plain") {
                fromSharedText(intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty().trim())
            } else null
            // "Open with" from a file manager. The MIME type is unreliable for epubs (often
            // octet-stream), so the filename extension counts too.
            Intent.ACTION_VIEW -> intent.data?.takeIf {
                intent.type == EPUB_MIME || it.toString().endsWith(".epub", ignoreCase = true)
            }?.let(::OpenEpub)
            ACTION_REVIEW -> OpenRoute(Routes.review(REVIEW_LIMIT))
            ACTION_NEW_CHAT -> OpenRoute(Routes.newConversation())
            ACTION_ROUTINE -> OpenRoute(Routes.ROUTINES)
            else -> null
        }

        /**
         * Classifies shared text as a URL or a body to translate. Browsers often share
         * "Title\nhttps://…", so a short share containing a link counts as a URL share; a long text
         * that merely mentions links is imported as text.
         */
        private fun fromSharedText(text: String): LaunchAction? {
            if (text.isEmpty()) return null
            val urlToken = text.split(WHITESPACE).firstOrNull {
                it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true)
            }
            return when {
                urlToken != null && text.length <= SHORT_SHARE_CHARS -> ShareUrl(urlToken)
                else -> ShareText(text)
            }
        }

        private val WHITESPACE = Regex("\\s+")
        private const val SHORT_SHARE_CHARS = 300

        private const val EPUB_MIME = "application/epub+zip"
    }
}
