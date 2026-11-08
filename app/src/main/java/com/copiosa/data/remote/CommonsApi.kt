package com.copiosa.data.remote

import com.copiosa.BuildConfig
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Query

/**
 * Wikimedia Commons image search — a free, **keyless** media library
 * (https://commons.wikimedia.org). Used to find a few candidate photos for a flashcard so the user
 * can pick one; we never generate images. Chosen over a generic CC aggregator because its full-text
 * search ranks concept photos far better for single-word glosses (e.g. "gill" → fish gills, not a
 * surname). Anonymous use is fine, but MediaWiki requires a descriptive User-Agent; its contact is
 * `BuildConfig.CONTACT`, which the build takes from local.properties so that no address lives in
 * the source.
 *
 * A single MediaWiki query drives the whole search: [generator=search] finds matching File: pages
 * and [prop=imageinfo] returns, per result, a scaled [iiurlwidth] thumbnail — so one round-trip
 * yields everything the picker needs. That thumbnail is also what gets attached: it is already
 * larger than a stored card image, where the originals routinely run to several megabytes.
 */
/**
 * The User-Agent every Wikimedia request must carry — the API *and* the image hosts
 * (upload/thumb.wikimedia.org), which answer OkHttp's default `okhttp/x.y` with a 403.
 */
const val COMMONS_USER_AGENT = "Copiosa/1.0 (language-learning app; contact ${BuildConfig.CONTACT})"

interface CommonsApi {
    @Headers("User-Agent: $COMMONS_USER_AGENT")
    @GET("w/api.php")
    suspend fun search(
        @Query("gsrsearch") query: String,
        @Query("gsrlimit") limit: Int = 4,
        // One of the widths Wikimedia pre-renders; any other is rounded up to the next of them.
        @Query("iiurlwidth") thumbWidth: Int = 500,
        // Fixed request shape; callers only vary the three params above.
        @Query("action") action: String = "query",
        @Query("format") format: String = "json",
        @Query("generator") generator: String = "search",
        @Query("gsrnamespace") namespace: Int = 6, // 6 = File: namespace
        @Query("prop") prop: String = "imageinfo",
        @Query("iiprop") iiprop: String = "url|mime",
    ): CommonsResponse
}

@Serializable
data class CommonsResponse(val query: CommonsQuery? = null)

/** [pages] is keyed by page id (unordered) — sort results by [CommonsPage.index] to restore rank. */
@Serializable
data class CommonsQuery(val pages: Map<String, CommonsPage> = emptyMap())

@Serializable
data class CommonsPage(
    /** Search rank (1-based); the [pages] map itself is unordered. */
    val index: Int = 0,
    /** The "File:Name.jpg" page title — used as a stable candidate id. */
    val title: String = "",
    val imageinfo: List<CommonsImageInfo> = emptyList(),
)

@Serializable
data class CommonsImageInfo(
    /** A scaled thumbnail (width [CommonsApi.search]'s thumbWidth) — reliable and already small. */
    val thumburl: String? = null,
    /** MIME type; used to skip formats BitmapFactory can't decode (svg/tiff/pdf). */
    val mime: String? = null,
)
