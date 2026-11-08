package com.copiosa.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.copiosa.data.anki.AnkiMedia
import com.copiosa.data.local.FlashcardDao
import com.copiosa.data.remote.COMMONS_USER_AGENT
import com.copiosa.data.remote.CommonsApi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** One pickable image: a locally-cached thumbnail, both shown in the picker and attached on selection. */
data class ImageCandidate(
    val id: String,
    val title: String?,
    val thumbnailFile: File,
)

/**
 * Finds candidate photos for a flashcard via Wikimedia Commons (keyless image search) and attaches a
 * chosen one to a card. Search pre-downloads each thumbnail to the cache so the picker can render
 * them with the same [BitmapFactory] path the cards already use (no image-loading library). Attaching
 * downscales that same cached thumbnail — no second download — and stores it in the shared media
 * store so it renders via [com.copiosa.data.local.FlashcardEntity.imageFile] and travels through
 * Anki export.
 */
@Singleton
class ImageSearchRepository @Inject constructor(
    private val api: CommonsApi,
    private val http: OkHttpClient,
    private val dao: FlashcardDao,
    @ApplicationContext private val context: Context,
) {
    /** Searches Commons and returns up to [count] candidates whose thumbnails downloaded OK. */
    suspend fun search(query: String, count: Int = 4): List<ImageCandidate> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext emptyList()
        val cacheDir = File(context.cacheDir, CACHE_SUBDIR).apply { mkdirs() }
        // The pages map is unordered — sort by the search rank, and keep only formats we can decode.
        val pages = api.search(q, count).query?.pages?.values
            ?.sortedBy { it.index }
            .orEmpty()
        val attempts = coroutineScope {
            pages.map { page ->
                async {
                    runCatching {
                        val info = page.imageinfo.firstOrNull() ?: return@runCatching null
                        val thumb = info.thumburl?.takeIf { it.isNotBlank() } ?: return@runCatching null
                        if (info.mime !in DECODABLE_MIME) return@runCatching null
                        val dest = File(cacheDir, "thumb_${safe(page.title)}_${System.currentTimeMillis()}.img")
                        download(thumb, dest)
                        ImageCandidate(
                            id = page.title,
                            title = page.title.removePrefix("File:"),
                            thumbnailFile = dest,
                        )
                    }
                }
            }.awaitAll()
        }
        val candidates = attempts.mapNotNull { it.getOrNull() }
        // Every thumbnail failing is a broken fetch, not an empty search — say so rather than
        // reporting "no images" for a query Commons answered.
        if (candidates.isEmpty()) attempts.firstNotNullOfOrNull { it.exceptionOrNull() }?.let { throw it }
        candidates
    }

    /**
     * Downscales the chosen image's cached thumbnail to [MAX_EDGE]px, writes it as a JPEG into the
     * media store, points the card at it, and returns the stored filename.
     */
    suspend fun attachImage(cardId: Long, candidate: ImageCandidate): String = withContext(Dispatchers.IO) {
        val bitmap = decodeScaled(candidate.thumbnailFile, MAX_EDGE)
            ?: throw IOException("Could not decode the selected image")

        val name = "la_img_${cardId}_${System.currentTimeMillis()}.jpg"
        val dest = AnkiMedia.file(context, name)
        dest.parentFile?.mkdirs()
        dest.outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out) }
        bitmap.recycle()
        dao.setImageFile(cardId, name)
        name
    }

    private fun download(url: String, dest: File) {
        val request = Request.Builder().url(url).header("User-Agent", COMMONS_USER_AGENT).build()
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("Empty response body")
            dest.outputStream().use { out -> body.byteStream().copyTo(out) }
        }
    }

    /** Decodes [file] subsampled, then scales so its longest edge is at most [maxEdge]px. */
    private fun decodeScaled(file: File, maxEdge: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return null
        var sample = 1
        while (w / (sample * 2) >= maxEdge && h / (sample * 2) >= maxEdge) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null
        val longEdge = maxOf(bmp.width, bmp.height)
        if (longEdge <= maxEdge) return bmp
        val scale = maxEdge.toFloat() / longEdge
        val scaled = Bitmap.createScaledBitmap(
            bmp,
            (bmp.width * scale).toInt().coerceAtLeast(1),
            (bmp.height * scale).toInt().coerceAtLeast(1),
            true,
        )
        if (scaled != bmp) bmp.recycle()
        return scaled
    }

    private fun safe(s: String): String = s.replace(Regex("[^A-Za-z0-9]"), "_").take(40)

    companion object {
        private const val CACHE_SUBDIR = "img_search"
        // Raster formats BitmapFactory can decode; skips Commons results that are SVG/TIFF/PDF/etc.
        private val DECODABLE_MIME = setOf("image/jpeg", "image/png", "image/webp", "image/gif")
        // Stored at a higher resolution than the old 256 so the ~50%-larger card display stays crisp.
        private const val MAX_EDGE = 384
        private const val JPEG_QUALITY = 85
    }
}
