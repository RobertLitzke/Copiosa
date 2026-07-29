package com.copiosa.domain.topics

/**
 * The single optional model call behind topic suggestions: turns the scorer's ranked interests into
 * fresh, specific conversation topics.
 *
 * The response format is one topic per line, `phrase | rationale`, parsed leniently — a malformed
 * line is dropped rather than failing the batch, and an unusable response leaves the scorer's own
 * ranking in place.
 */
object TopicSuggestionPrompt {

    const val SYSTEM_PROMPT =
        "You suggest conversation topics for a language learner. " +
            "Given the topics they have engaged with recently, propose new ones they would " +
            "plausibly enjoy: adjacent to their interests, not repeats of them. " +
            "Reply with exactly one topic per line in the form: phrase | reason. " +
            "The phrase is a short noun phrase in the learner's native language (under 8 words). " +
            "The reason is one short clause explaining the connection. " +
            "No numbering, no headers, no extra commentary."

    /** [interests] is the scorer's ranked output, best first. */
    fun build(interests: List<TopicSuggestionScorer.Scored>, targetLanguage: String, count: Int): String {
        val listed = interests.joinToString("\n") { scored ->
            val gloss = scored.description?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
            "- ${scored.phrase}$gloss"
        }
        return "The learner is studying $targetLanguage. Their recent interests, strongest first:\n" +
            "$listed\n\n" +
            "Suggest $count new topics."
    }

    /** Parsed suggestions in the model's order; malformed and empty lines are skipped. */
    fun parse(raw: String, limit: Int): List<Suggestion> =
        raw.lineSequence()
            .map { it.trim().removePrefix("-").removePrefix("*").trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { line ->
                val phrase = line.substringBefore('|').trim()
                if (phrase.isEmpty()) return@mapNotNull null
                val rationale = line.substringAfter('|', "").trim().ifBlank { null }
                Suggestion(phrase, rationale)
            }
            .distinctBy { it.phrase.lowercase() }
            .take(limit)
            .toList()

    data class Suggestion(val phrase: String, val rationale: String?)
}
