package com.copiosa.domain.vocab.freq

import com.copiosa.domain.vocab.freq.data.FrequencyListEsData
import com.copiosa.domain.vocab.freq.data.FrequencyListPtData

/**
 * The target languages that ship a frequency-ranked vocabulary list.
 *
 * Each list is constructed with a lambda rather than its parsed contents, so selecting Spanish never
 * touches the Portuguese data — and a language with no list at all simply resolves to null, which is
 * every caller's cue to hide the coverage figures, chips and drill rather than show empty ones.
 */
object FrequencyLists {

    private val SPANISH = FrequencyList { FrequencyListEsData.lines }
    private val PORTUGUESE = FrequencyList { FrequencyListPtData.lines }

    private val byTag = mapOf("es" to SPANISH, "pt" to PORTUGUESE)

    /**
     * The list for a BCP-47 tag, or null when the app bundles none for it. The region subtag is
     * ignored, matching how [com.copiosa.domain.language.LanguageProfiles] resolves.
     */
    fun forTag(tag: String): FrequencyList? = byTag[tag.substringBefore('-').lowercase()]

    /** Whether [tag] has a frequency list, without forcing the data to parse. */
    fun supports(tag: String): Boolean = forTag(tag) != null

    /** The base tags a list exists for — what gates the frequent-word routine activity. */
    val supportedTags: Set<String> get() = byTag.keys
}
