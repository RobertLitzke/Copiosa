package com.copiosa.domain.routine

import com.copiosa.domain.model.Cefr
import com.copiosa.domain.prompt.RoutineContentPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the registry contract that replaced the per-type when-statements: every [ActivityType] must
 * have exactly one descriptor, and every LLM-generated activity must contribute both prompt parts —
 * a missing branch used to be a compile error, so these assertions are its replacement.
 */
class ActivityRegistryTest {

    @Test
    fun `every activity type has exactly one descriptor`() {
        assertEquals(ActivityType.entries.toSet(), ActivityRegistry.all.map { it.type }.toSet())
        assertEquals(ActivityType.entries.size, ActivityRegistry.all.size)
    }

    @Test
    fun `definition type matches descriptor type`() {
        for (descriptor in ActivityRegistry.all) {
            assertEquals(descriptor.type, descriptor.definition.type)
        }
    }

    @Test
    fun `llm activities contribute both prompt parts`() {
        val promptCtx = PromptContext("Spanish", "English")
        val requestCtx = RequestContext(durationMinutes = 10, storyContext = null)
        for (descriptor in ActivityRegistry.all.filter { it.definition.needsLlm }) {
            val slot = slotFor(descriptor)
            assertNotNull("${descriptor.type} needs an LLM but has no system rule", descriptor.systemRule(promptCtx))
            assertNotNull("${descriptor.type} needs an LLM but has no request line", descriptor.requestLine(0, slot, requestCtx))
        }
    }

    @Test
    fun `offline activities request nothing from the llm`() {
        val promptCtx = PromptContext("Spanish", "English")
        val requestCtx = RequestContext(durationMinutes = 10, storyContext = null)
        for (descriptor in ActivityRegistry.all.filterNot { it.definition.needsLlm }) {
            assertEquals(null, descriptor.systemRule(promptCtx))
            assertEquals(null, descriptor.requestLine(0, slotFor(descriptor), requestCtx))
        }
    }

    @Test
    fun `status category slugs are globally unique`() {
        val slugs = ActivityRegistry.all.flatMap { it.statusCategories }.map { it.slug }
        assertEquals(slugs, slugs.distinct())
    }

    @Test
    fun `categorized types bucket by category, others by type name`() {
        for (descriptor in ActivityRegistry.all) {
            val bucket = descriptor.definition.bucketKey("some_category")
            if (descriptor.statusCategories.isNotEmpty()) {
                assertEquals("some_category", bucket)
            } else {
                assertEquals(descriptor.type.name, bucket)
            }
        }
    }

    @Test
    fun `system prompt emits a rule for every llm activity`() {
        val system = RoutineContentPrompt().systemPrompt("Mexico", "es", "en", Cefr.B2)
        for (descriptor in ActivityRegistry.all.filter { it.definition.needsLlm }) {
            val slug = descriptor.type.name.lowercase()
            assertTrue("system prompt is missing a rule for $slug", system.contains("- $slug:"))
        }
    }

    private fun slotFor(descriptor: ActivityDescriptor) = ActivitySlot(
        type = descriptor.type,
        band = descriptor.definition.eligibleBands.first(),
        arousalWeight = descriptor.definition.arousalWeight,
        estMinutes = descriptor.definition.estMinutes,
        difficultyLevel = 0,
        category = descriptor.statusCategories.firstOrNull()?.slug,
    )
}
