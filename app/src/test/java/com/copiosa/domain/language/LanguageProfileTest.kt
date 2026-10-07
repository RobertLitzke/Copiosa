package com.copiosa.domain.language

import com.copiosa.domain.conjugation.ConjugationSpecs
import com.copiosa.domain.routine.ActivityRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The completeness checks for the language seam, in the spirit of `ActivityRegistryTest`: they are
 * what replaced "someone remembers to update the other file". A new [LanguageProfile] that forgets a
 * paradigm's endings, or reuses a slug, fails here rather than at synthesis time.
 */
class LanguageProfileTest {

    @Test
    fun `every profile resolves from its own tag, and region subtags are ignored`() {
        LanguageProfiles.all.forEach { profile ->
            assertEquals(profile, LanguageProfiles.forTag(profile.tag))
            assertEquals(profile, LanguageProfiles.forTag("${profile.tag}-XX"))
            assertEquals(profile, LanguageProfiles.forTag(profile.tag.uppercase()))
        }
        assertNull(LanguageProfiles.forTag("xx"))
    }

    @Test
    fun `no two profiles claim the same tag`() {
        val tags = LanguageProfiles.all.map { it.tag }
        assertEquals("duplicate profile tags", tags.size, tags.toSet().size)
    }

    @Test
    fun `each profile declares contrasts, pronouns and false-friend examples`() {
        LanguageProfiles.all.forEach { profile ->
            val where = profile.tag
            assertTrue("$where has no contrasts", profile.contrasts.isNotEmpty())
            assertEquals(
                "$where lists a contrast twice",
                profile.contrasts.size, profile.contrasts.toSet().size,
            )
            assertTrue("$where has no subject pronouns", profile.subjectPronouns.isNotEmpty())
            assertTrue("$where has no time adverbs", profile.timeAdverbs.isNotEmpty())
            // Cognate hunt interpolates these straight into the prompt; an empty list would ask the
            // model for "1-2 real false friends (e.g. )".
            assertTrue("$where has no false-friend examples", profile.falseFriendExamples.isNotEmpty())
        }
    }

    @Test
    fun `pro-drop cue words are lowercased and cover both pronouns and adverbs`() {
        LanguageProfiles.all.forEach { profile ->
            val cues = profile.proDropCueWords
            assertTrue(profile.tag, cues.all { it == it.lowercase() })
            assertTrue(profile.tag, cues.containsAll(profile.subjectPronouns.map { it.lowercase() }))
            assertTrue(profile.tag, cues.containsAll(profile.timeAdverbs.map { it.lowercase() }))
        }
    }

    @Test
    fun `gender bins name a card gender code and read as an article`() {
        LanguageProfiles.all.mapNotNull { it.genderBins }.forEach { bins ->
            // Swipe-sort maps card gender M -> bin 0 and F -> bin 1, so the order is load-bearing.
            assertEquals(listOf("M", "F"), bins.map { it.code })
            assertTrue(bins.all { it.label.isNotBlank() })
        }
    }

    @Test
    fun `every declared tense has either an ending table or a derivation`() {
        listOf(ConjugationSpecs.SPANISH, ConjugationSpecs.PORTUGUESE).forEach { spec ->
            spec.tenses.forEach { tense ->
                val hasEndings = spec.endings[tense]?.keys?.containsAll(
                    com.copiosa.domain.conjugation.VerbClass.entries.toList(),
                ) == true
                val derived = spec.derived[tense] != null
                assertTrue(
                    "${spec.language} ${tense.slug} has neither ending table nor derivation",
                    hasEndings || derived,
                )
            }
            // Every ending row must cover every person, or a lookup indexes out of bounds.
            spec.endings.forEach { (tense, byClass) ->
                byClass.forEach { (cls, endings) ->
                    assertEquals(
                        "${spec.language} ${tense.slug} $cls",
                        spec.persons.size, endings.size,
                    )
                }
            }
            spec.derived.forEach { (tense, d) ->
                assertEquals("${spec.language} ${tense.slug} derived", spec.persons.size, d.endings.size)
                d.source?.let {
                    assertTrue("${spec.language} derives from a tense it doesn't have", it.tense in spec.tenses)
                }
            }
        }
    }

    @Test
    fun `every spec names a pronoun for every person it drills`() {
        listOf(ConjugationSpecs.SPANISH, ConjugationSpecs.PORTUGUESE).forEach { spec ->
            spec.persons.forEach { person ->
                assertNotNull("${spec.language} has no pronoun for $person", spec.pronouns[person])
            }
            spec.optionalPerson?.let {
                assertTrue("${spec.language} optional person isn't one it drills", it in spec.persons)
            }
        }
    }

    @Test
    fun `a spec's transform rules only reference paradigms it has`() {
        listOf(ConjugationSpecs.SPANISH, ConjugationSpecs.PORTUGUESE).forEach { spec ->
            spec.transformRules.forEach { rule ->
                assertTrue("${spec.language} ${rule.slug} source", rule.sourceTense in spec.tenses)
                assertTrue("${spec.language} ${rule.slug} target", rule.targetTense in spec.tenses)
            }
            // Two rules with the same (source cell, target tense) are indistinguishable to the codec,
            // which resolves a stored transform card that way.
            val keys = spec.transformRules.map { it.sourceCell() to it.targetTense }
            assertEquals("${spec.language} has ambiguous transform rules", keys.size, keys.toSet().size)
        }
    }

    @Test
    fun `every activity requirement is satisfiable by some profile`() {
        val required = ActivityRegistry.all.flatMap { it.definition.requires }.toSet()
        required.forEach { feature ->
            assertTrue(
                "no language profile supplies $feature, so its activities can never be planned",
                LanguageProfiles.all.any { it.supports(feature) },
            )
        }
    }

    @Test
    fun `a language with no profile plans none of the grammar activities`() {
        val gated = ActivityRegistry.all.filter { it.definition.requires.isNotEmpty() }
        assertTrue("expected some capability-gated activities", gated.isNotEmpty())
        gated.forEach {
            assertTrue("${it.type} should not support an unknown language", !it.definition.supports("xx"))
        }
    }
}
