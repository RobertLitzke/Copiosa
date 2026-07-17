package com.copiosa.domain.prompt

import com.copiosa.domain.model.Cefr
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptBuilderTest {

    private val builder = PromptBuilder()

    @Test
    fun languageName_resolvesTags() {
        assertTrue(builder.languageName("es").equals("Spanish", ignoreCase = true))
        assertTrue(builder.languageName("en").equals("English", ignoreCase = true))
    }

    @Test
    fun conversationPrompt_includesLevelLanguagesAndSubject() {
        val prompt = builder.conversationSystemPrompt(
            country = "Mexico",
            targetLang = "es",
            nativeLang = "en",
            level = Cefr.B1,
            subject = "History of China",
        )
        assertTrue(prompt.contains("Spanish"))
        assertTrue(prompt.contains("English"))
        assertTrue(prompt.contains("B1"))
        assertTrue(prompt.contains("Mexico"))
        assertTrue(prompt.contains("History of China"))
        // Occasional one-level-up vocabulary.
        assertTrue(prompt.contains("B2"))
    }
}
