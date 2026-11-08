package com.copiosa.data.settings

import com.copiosa.domain.model.ModelOption
import com.copiosa.domain.model.OpenRouterModel
import com.copiosa.domain.model.ProviderId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundChoiceTest {

    private val cheap = ModelOption(ProviderId.OPENROUTER, "x/cheap")
    private val lite = ModelOption(ProviderId.GEMINI, "gemini-2.5-flash-lite")

    // --- resolution ---

    @Test
    fun `an untouched profile sends background work to its main model`() {
        val settings = AppSettings(provider = ProviderId.GEMINI, geminiModel = "gemini-2.5-pro")
        assertEquals(ModelOption(ProviderId.GEMINI, "gemini-2.5-pro"), settings.backgroundFor())
    }

    @Test
    fun `the slot keeps its own provider, whichever one the main model is on`() {
        val onGemini = AppSettings(provider = ProviderId.GEMINI, backgroundChoice = cheap)
        assertEquals(cheap, onGemini.backgroundFor())
        // Switching the main provider does not move the slot.
        assertEquals(cheap, onGemini.copy(provider = ProviderId.OPENROUTER).backgroundFor())

        val onOpenRouter = AppSettings(provider = ProviderId.OPENROUTER, backgroundChoice = lite)
        assertEquals(lite, onOpenRouter.backgroundFor())
    }

    @Test
    fun `an item's own model is the fallback only while the slot is empty`() {
        val book = ModelOption(ProviderId.OPENROUTER, "x/novelist")
        assertEquals(book, AppSettings(provider = ProviderId.GEMINI).backgroundFor(book))
        assertEquals(lite, AppSettings(backgroundChoice = lite).backgroundFor(book))
    }

    @Test
    fun `a model is offered only by its own provider's list`() {
        val settings = AppSettings(openRouterModels = listOf(OpenRouterModel("x/cheap", "Cheap")))
        assertTrue(settings.offers(cheap))
        assertTrue(settings.offers(lite))
        assertFalse(settings.offers(ModelOption(ProviderId.OPENROUTER, "x/removed")))
        // The right id on the wrong provider is not an offer.
        assertFalse(settings.offers(ModelOption(ProviderId.GEMINI, "x/cheap")))
        assertFalse(settings.offers(ModelOption(ProviderId.OPENROUTER, "gemini-2.5-flash-lite")))
    }

    // --- storage ---

    private fun stored(provider: String?, model: String?, active: ProviderId = ProviderId.GEMINI, legacy: String? = null) =
        SettingsRepository.backgroundChoiceOf(provider, model, active, legacy)

    @Test
    fun `a stored slot reads back on its own provider, not the active one`() {
        assertEquals(cheap, stored("OPENROUTER", "x/cheap", active = ProviderId.GEMINI))
        assertEquals(lite, stored("GEMINI", "gemini-2.5-flash-lite", active = ProviderId.OPENROUTER))
    }

    @Test
    fun `a blank provider is a deliberate same-as-main, and outranks the legacy slot`() {
        assertNull(stored("", "", legacy = "gemini-2.5-flash-lite"))
    }

    @Test
    fun `a profile from before the slot named its provider carries the active provider's over`() {
        assertEquals(lite, stored(null, null, active = ProviderId.GEMINI, legacy = "gemini-2.5-flash-lite"))
        assertEquals(cheap, stored(null, null, active = ProviderId.OPENROUTER, legacy = "x/cheap"))
        assertNull(stored(null, null, legacy = ""))
        assertNull(stored(null, null, legacy = null))
    }

    @Test
    fun `a provider this build does not know, or a missing model, is no slot at all`() {
        assertNull(stored("HOLOGRAM", "x/y"))
        assertNull(stored("OPENROUTER", ""))
        assertNull(stored("OPENROUTER", null))
    }
}
