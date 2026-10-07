package com.copiosa.domain.text

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the behaviour this splitter had while it was `TtsEngine.splitForSpeech`, so the move to
 * `domain/` is provably behaviour-preserving for the TTS renderer that still depends on it.
 */
class SentencesTest {

    @Test
    fun splitsOnSentenceFinalPunctuation() {
        assertEquals(listOf("Hola.", "Adiós."), Sentences.split("Hola. Adiós."))
        assertEquals(listOf("¿Qué?", "¡Vamos!"), Sentences.split("¿Qué? ¡Vamos!"))
    }

    @Test
    fun splitsOnceAcrossARunOfTerminators() {
        assertEquals(listOf("Espera...", "¿Qué?"), Sentences.split("Espera... ¿Qué?"))
        assertEquals(listOf("¿Vienes?!", "Sí."), Sentences.split("¿Vienes?! Sí."))
    }

    /**
     * The case most likely to regress: a period only ends a sentence when what follows is whitespace
     * or the end of the line, which is the sole thing keeping decimals intact.
     */
    @Test
    fun doesNotSplitDecimals() {
        assertEquals(listOf("Caminé 3.5 km hoy."), Sentences.split("Caminé 3.5 km hoy."))
        assertEquals(listOf("Cuesta 4.99 euros.", "Es barato."), Sentences.split("Cuesta 4.99 euros. Es barato."))
    }

    @Test
    fun doesNotSplitAfterKnownAbbreviations() {
        assertEquals(
            listOf("Vino el Sr. García.", "Se fue."),
            Sentences.split("Vino el Sr. García. Se fue."),
        )
        assertEquals(listOf("Pan, queso, etc. y vino."), Sentences.split("Pan, queso, etc. y vino."))
        assertEquals(listOf("Habla con la Dra. Ruiz."), Sentences.split("Habla con la Dra. Ruiz."))
    }

    @Test
    fun splitsOnLineBreaks() {
        assertEquals(listOf("Uno.", "Dos."), Sentences.split("Uno.\nDos."))
        // No terminators at all, but two lines still separate.
        assertEquals(listOf("Uno", "Dos"), Sentences.split("Uno\nDos"))
    }

    @Test
    fun singleSegmentFallsBackToTheWholeTrimmedText() {
        assertEquals(listOf("Hola mundo"), Sentences.split("  Hola mundo  "))
        assertEquals(listOf("Una sola frase."), Sentences.split("Una sola frase."))
    }

    @Test
    fun blankTextYieldsNothing() {
        assertEquals(emptyList<String>(), Sentences.split(""))
        assertEquals(emptyList<String>(), Sentences.split("   \n  \n "))
    }

    @Test
    fun dropsBlankSegmentsBetweenTerminators() {
        assertEquals(listOf("Sí.", "No.", "Tal vez."), Sentences.split("Sí.   No.\n\nTal vez."))
    }
}
