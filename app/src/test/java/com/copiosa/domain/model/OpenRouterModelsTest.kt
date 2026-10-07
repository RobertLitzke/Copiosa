package com.copiosa.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRouterModelsTest {

    @Test
    fun label_prefersNicknameOverId() {
        assertEquals("Cheap", OpenRouterModel("openai/gpt-4o-mini", "Cheap").label)
        assertEquals("openai/gpt-4o-mini", OpenRouterModel("openai/gpt-4o-mini").label)
        assertEquals("openai/gpt-4o-mini", OpenRouterModel("openai/gpt-4o-mini", "  ").label)
    }

    @Test
    fun roundTrip_preservesIdsAndNames() {
        val models = listOf(OpenRouterModel("a/one", "One"), OpenRouterModel("b/two"))
        assertEquals(models, OpenRouterModels.decode(OpenRouterModels.encode(models)))
    }

    @Test
    fun decode_returnsNullWhenUnsetOrUnreadable() {
        assertNull(OpenRouterModels.decode(null))
        assertNull(OpenRouterModels.decode(""))
        assertNull(OpenRouterModels.decode("not json"))
    }

    @Test
    fun normalize_trimsDropsBlanksAndDeduplicates() {
        val normalized = OpenRouterModels.normalize(
            listOf(
                OpenRouterModel("  a/one  ", "  One  "),
                OpenRouterModel(""),
                OpenRouterModel("a/one", "Duplicate"),
            ),
            "a/one",
        )
        assertEquals(listOf(OpenRouterModel("a/one", "One")), normalized)
    }

    @Test
    fun normalize_keepsTheActiveModelInTheRoster() {
        // A model still in use by a book or chat must stay offered even after it's edited out here.
        val normalized = OpenRouterModels.normalize(listOf(OpenRouterModel("a/one")), "b/two")
        assertEquals(OpenRouterModel("b/two"), normalized.first())
        assertTrue(OpenRouterModel("a/one") in normalized)
    }

    @Test
    fun normalize_leavesRosterAloneWhenNothingIsActive() {
        assertEquals(
            listOf(OpenRouterModel("a/one")),
            OpenRouterModels.normalize(listOf(OpenRouterModel("a/one")), "  "),
        )
    }
}
