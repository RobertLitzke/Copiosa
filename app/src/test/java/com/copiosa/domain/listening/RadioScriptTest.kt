package com.copiosa.domain.listening

import org.junit.Assert.assertEquals
import org.junit.Test

class RadioScriptTest {

    @Test
    fun `a script survives the trip to a row's text and back`() {
        val lines = listOf(
            RadioLine("El gato duerme en el sofá.", "The cat sleeps on the sofa."),
            RadioLine("¿Dónde está la estación?", null),
        )
        assertEquals(lines, RadioScript.decode(RadioScript.encode(lines)))
    }

    @Test
    fun `a sentence without a translation is a line with no tab`() {
        assertEquals("Hola.\nAdiós.\tGoodbye.", RadioScript.encode(listOf(RadioLine("Hola.", " "), RadioLine("Adiós.", "Goodbye."))))
    }

    @Test
    fun `tabs and line breaks inside a sentence cannot split it`() {
        val encoded = RadioScript.encode(listOf(RadioLine("Uno\tdos\ntres.", "One\r\ntwo.")))
        assertEquals(listOf(RadioLine("Uno dos tres.", "One two.")), RadioScript.decode(encoded))
    }

    @Test
    fun `blank lines and blank sentences are dropped`() {
        assertEquals("Sí.", RadioScript.encode(listOf(RadioLine("  ", "nothing"), RadioLine("Sí.", null))))
        assertEquals(listOf(RadioLine("Sí.", null)), RadioScript.decode("\n\tstray\nSí.\n\n"))
    }
}
