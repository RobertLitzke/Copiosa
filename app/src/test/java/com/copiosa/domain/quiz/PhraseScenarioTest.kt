package com.copiosa.domain.quiz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhraseScenarioTest {

    @Test
    fun `every PHRASE cue type has a scenario`() {
        CueType.entries.filter { it.kind == CueKind.PHRASE }.forEach { type ->
            assertNotNull("no PhraseScenario for $type", PhraseScenarios.forType(type))
        }
    }

    @Test
    fun `every scenario targets a PHRASE cue type`() {
        PhraseScenarios.ALL.forEach { scenario ->
            assertEquals(CueKind.PHRASE, scenario.type.kind)
        }
    }

    @Test
    fun `algorithmic cue types have no scenario`() {
        assertNull(PhraseScenarios.forType(CueType.NUMBER))
        assertNull(PhraseScenarios.forType(CueType.TIME))
    }

    @Test
    fun `restaurant scenario keeps its stable slug and setting prompt`() {
        assertEquals("rf_restaurant", CueType.RESTAURANT.slug)
        assertTrue(PhraseScenarios.RESTAURANT.settingPrompt.isNotBlank())
    }
}
