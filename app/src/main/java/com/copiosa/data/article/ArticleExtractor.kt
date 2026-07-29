package com.copiosa.data.article

import com.copiosa.data.remote.LlmError
import com.copiosa.data.remote.LlmException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.dankito.readability4j.Readability4J
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.io.IOException
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

/** Extracted, reader-friendly article content. */
data class ExtractedArticle(val title: String, val text: String)

/**
 * Fetches a URL and extracts the main article text for translation, running a Readability pass so
 * navigation/ads/boilerplate are stripped and only the body reaches the model (keeping token use
 * down). Pages that need JavaScript or sit behind a paywall usually yield too little text — those
 * raise [LlmError.Extraction] so the caller can steer the user to the Paste-text path; a genuine
 * connectivity failure raises [LlmError.Network].
 */
@Singleton
class ArticleExtractor @Inject constructor(
    private val client: OkHttpClient,
) {
    suspend fun extract(url: String): ExtractedArticle = withContext(Dispatchers.IO) {
        val normalized = normalizeUrl(url)
        val html = fetch(normalized)
        val article = try {
            Readability4J(normalized, html).parse()
        } catch (t: Throwable) {
            throw LlmException(LlmError.Extraction)
        }
        // Prefer the cleaned article HTML so paragraph/heading structure survives; textContent collapses
        // it into a single block. Fall back to the flat text if structured extraction yields nothing.
        val structured = article.content?.let { structuredText(it) }?.takeIf { it.isNotBlank() }
        val text = (structured ?: article.textContent?.trim()).orEmpty()
        if (text.length < MIN_ARTICLE_CHARS) throw LlmException(LlmError.Extraction)
        val title = article.title?.trim().takeUnless { it.isNullOrBlank() } ?: hostOf(normalized)
        ExtractedArticle(title = title, text = text)
    }

    /**
     * Flattens the cleaned article HTML into block-separated Markdown: each paragraph/heading/list
     * item becomes its own block (blank line between blocks), headings as `#`/`##`/`###` and list
     * items as `- `. Preserves the reading structure that [Article.textContent] would discard.
     */
    private fun structuredText(html: String): String {
        val blocks = mutableListOf<String>()
        collectBlocks(Jsoup.parse(html).body(), blocks)
        return blocks.joinToString("\n\n")
    }

    private fun collectBlocks(node: Element, out: MutableList<String>) {
        for (child in node.children()) {
            val prefix = BLOCK_PREFIXES[child.tagName().lowercase()]
            if (prefix != null) {
                val text = child.text().trim()
                if (text.isNotEmpty()) out.add(prefix + text)
            } else {
                // A container (div/section/ul/…): descend to its block-level descendants.
                collectBlocks(child, out)
            }
        }
    }

    private fun fetch(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw LlmException(LlmError.Extraction)
                return response.body?.string().orEmpty()
            }
        } catch (e: LlmException) {
            throw e
        } catch (e: IOException) {
            throw LlmException(LlmError.Network)
        } catch (e: IllegalArgumentException) {
            // Malformed URL handed to OkHttp.
            throw LlmException(LlmError.Extraction)
        }
    }

    companion object {
        private const val MIN_ARTICLE_CHARS = 200

        /** Block-level tags we emit, mapped to their Markdown prefix; others are containers we recurse. */
        private val BLOCK_PREFIXES = mapOf(
            "p" to "", "pre" to "", "blockquote" to "", "figcaption" to "",
            "h1" to "# ", "h2" to "## ", "h3" to "### ",
            "h4" to "### ", "h5" to "### ", "h6" to "### ",
            "li" to "- ",
        )
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0 Mobile Safari/537.36"

        /** [url] with an `https://` scheme prepended when the user typed a bare host/path. */
        fun normalizeUrl(url: String): String =
            if (url.startsWith("http", ignoreCase = true)) url else "https://$url"

        /** Bare host of [url] (without `www.`), used as a fallback title and the list source label. */
        fun hostOf(url: String): String =
            runCatching { URI(url).host?.removePrefix("www.") }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: url
    }
}
