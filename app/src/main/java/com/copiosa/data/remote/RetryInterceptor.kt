package com.copiosa.data.remote

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * Retries transient server failures (429 + 5xx) with exponential backoff. Overloaded-model
 * 503s from the LLM providers are common on free tiers; a few short retries usually ride them
 * out without bubbling an error up to the user. Sits outside the logging interceptor so each
 * attempt is logged separately.
 */
class RetryInterceptor(
    private val maxRetries: Int = 3,
    private val baseDelayMs: Long = 500L,
    private val maxDelayMs: Long = 4_000L,
) : Interceptor {

    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        var attempt = 0
        var response = chain.proceed(chain.request())
        while (response.code in RETRYABLE_CODES && attempt < maxRetries) {
            val delay = backoffMs(attempt, response)
            response.close()
            try {
                Thread.sleep(delay)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("Retry interrupted", e)
            }
            attempt++
            response = chain.proceed(chain.request())
        }
        return response
    }

    /** Exponential backoff, honoring a server `Retry-After` (in seconds) when it asks for longer. */
    private fun backoffMs(attempt: Int, response: Response): Long {
        val exponential = (baseDelayMs shl attempt).coerceAtMost(maxDelayMs)
        val retryAfterMs = response.header("Retry-After")?.toLongOrNull()?.times(1000) ?: 0L
        return maxOf(exponential, retryAfterMs)
    }

    private companion object {
        val RETRYABLE_CODES = setOf(429, 500, 502, 503, 504)
    }
}
