package com.copiosa.domain.prompt

import com.copiosa.domain.model.Cefr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun turnNote_carriesTheWordsTheSystemPromptNoLongerDoes() {
        val note = builder.turnNote(
            vocabToUse = listOf("gato" to "cat", "frotar" to ""),
            reuseWords = listOf("alojar"),
        )
        assertTrue(note.startsWith("[") && note.endsWith("]"))
        assertTrue(note.contains("gato (cat)"))
        assertTrue(note.contains("frotar"))
        assertTrue(note.contains("alojar"))

        // The words are per-turn; a system prompt that named them would differ on every turn, and
        // the provider could then reuse nothing of the chat it has already been sent.
        val prompt = chatPrompt(includeVocabReport = true)
        assertFalse(prompt.contains("gato"))
        assertFalse(prompt.contains("alojar"))
    }

    @Test
    fun turnNote_isEmptyWithNothingToSay() {
        assertEquals("", builder.turnNote())
    }

    @Test
    fun chatSystemPrompts_explainTheNoteAndAskForTheVocabReportOnlyWhenWordsAreSent() {
        val discussion = builder.textDiscussionSystemPrompt(
            country = "Mexico",
            targetLang = "es",
            nativeLang = "en",
            level = Cefr.B1,
            includeVocabReport = true,
        )
        for (prompt in listOf(chatPrompt(includeVocabReport = true), discussion)) {
            assertTrue(prompt.contains("teacher's note"))
            assertTrue(prompt.contains("vocabUsed"))
        }
        assertFalse(chatPrompt(includeVocabReport = false).contains("vocabUsed"))
    }

    private fun chatPrompt(includeVocabReport: Boolean) = builder.buildConversationPrompt(
        core = PromptBuilder.DEFAULT_CORE_PROMPT,
        customInstructions = "",
        country = "Mexico",
        targetLang = "es",
        nativeLang = "en",
        level = Cefr.B1,
        subject = "History of China",
        includeVocabReport = includeVocabReport,
    )
}
