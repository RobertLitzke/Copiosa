package com.copiosa.data.cloze

import com.copiosa.domain.cloze.ClozeCandidate
import com.copiosa.domain.cloze.ClozeMineContext

/**
 * One place cloze sentences can come from.
 *
 * The [limit] is part of the contract rather than the caller's problem: a book is thousands of
 * paragraphs and the LLM source costs tokens, so a source that scanned everything and let the caller
 * truncate would already have done the expensive part. A source returns *candidates*; the repository
 * gates, caps and stores them, so quality rules live in one place and every source is held to them.
 */
interface ClozeSource {

    /** Stable slug, stored on each row it produces as provenance and as the undo handle. */
    val id: String

    /** Whether running this source spends LLM calls, so the local mining pass can skip it. */
    val needsLlm: Boolean get() = false

    /** At most [limit] candidates, ungated. Returning fewer (or none) is normal, not a failure. */
    suspend fun mine(ctx: ClozeMineContext, limit: Int): List<ClozeCandidate>
}
