package com.copiosa.domain.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StructuredReplyParserTest {

    @Test
    fun parse_cleanReplyAndBlock_splitsBodyAndMetadata() {
        val raw = "Hola, esto es una respuesta.\n" +
            "${StructuredReplyParser.SENTINEL}\n" +
            """{"topics":["Carlos V","La Reconquista"],"distinctive":["reino","monarquía"],""" +
            """"vocabUsed":["reino"],"vocabUnused":["aduana"]}"""
        val (body, meta) = StructuredReplyParser.parse(raw)

        assertEquals("Hola, esto es una respuesta.", body)
        assertEquals(listOf("Carlos V", "La Reconquista"), meta.topicPhrases)
        assertEquals(listOf("reino", "monarquía"), meta.distinctive)
        assertEquals(listOf("reino"), meta.vocabUsed)
        assertEquals(listOf("aduana"), meta.vocabUnused)
    }

    @Test
    fun parse_noSentinel_wholeTextIsBodyWithEmptyMetadata() {
        val raw = "Just a plain reply with no metadata block."
        val (body, meta) = StructuredReplyParser.parse(raw)

        assertEquals("Just a plain reply with no metadata block.", body)
        assertTrue(meta.isEmpty)
    }

    @Test
    fun parse_malformedJsonTail_preservesBodyAndEmptyMetadata() {
        val raw = "The reply text.\n${StructuredReplyParser.SENTINEL}\n{ topics: this is not valid json "
        val (body, meta) = StructuredReplyParser.parse(raw)

        assertEquals("The reply text.", body)
        assertTrue(meta.isEmpty)
    }

    @Test
    fun parse_garbledSentinel_stillStripsBlock() {
        // Weaker models drift the marker: extra/missing brackets, no closing brackets, spacing, case.
        val variants = listOf(
            "<<<COPIOSA_META>>",      // one bracket short
            "<<<COPIOSA_META>>>>",    // one bracket long
            "<<<COPIOSA_META",        // no closing brackets at all
            "<<< copiosa meta >>>",   // spaces + lowercase
            "<<<COPIOSA_META>>>",     // canonical, for good measure
        )
        for (sentinel in variants) {
            val raw = "Visible reply.\n$sentinel\n{\"topics\":[\"Goya\"]}"
            val (body, meta) = StructuredReplyParser.parse(raw)
            assertEquals("body for [$sentinel]", "Visible reply.", body)
            assertEquals("meta for [$sentinel]", listOf("Goya"), meta.topicPhrases)
        }
    }

    @Test
    fun parse_garbledSentinelUnparseableJson_dropsBlockButKeepsBody() {
        val raw = "Visible reply.\n<<<COPIOSA_META {this is not json at all"
        val (body, meta) = StructuredReplyParser.parse(raw)

        assertEquals("Visible reply.", body)
        assertTrue(meta.isEmpty)
    }

    @Test
    fun parse_lightlyGarbledJson_isSalvaged() {
        // Trailing comma and smart quotes used as delimiters — repaired on the second pass.
        val raw = "Body.\n${StructuredReplyParser.SENTINEL}\n" +
            "{“topics”:[“Goya”,],“distinctive”:[“pincel”]}"
        val (body, meta) = StructuredReplyParser.parse(raw)

        assertEquals("Body.", body)
        assertEquals(listOf("Goya"), meta.topicPhrases)
        assertEquals(listOf("pincel"), meta.distinctive)
    }

    @Test
    fun parse_realFlashLiteLeak_noClosingBrackets_stripsAndSalvages() {
        // Verbatim from a gemini-2.5-flash-lite reply that leaked: the marker arrived with no closing
        // ">>>", so the old exact-match parser dumped the whole block into the visible reply and lost
        // the metadata. The body must come out clean and the (valid) JSON must still be salvaged.
        val raw = "...requiere una adaptación cuidadosa por parte de los atletas.\n\n" +
            "<<<COPIOSA_META\n" +
            """{"topics": ["entrenamiento en altitud", "mal de altura", "la aclimatación"], """ +
            """"distinctive": ["hipoxia", "mitigar", "desestabilizar"], """ +
            """"vocabUsed": ["capacitación", "estropear"], "vocabUnused": ["goleta", "espejo"]}"""
        val (body, meta) = StructuredReplyParser.parse(raw)

        assertEquals("...requiere una adaptación cuidadosa por parte de los atletas.", body)
        assertEquals(listOf("entrenamiento en altitud", "mal de altura", "la aclimatación"), meta.topicPhrases)
        assertEquals(listOf("hipoxia", "mitigar", "desestabilizar"), meta.distinctive)
        assertEquals(listOf("capacitación", "estropear"), meta.vocabUsed)
    }

    @Test
    fun parse_fencedTail_stillDecodes() {
        val raw = "Body here.\n${StructuredReplyParser.SENTINEL}\n" +
            "```json\n{\"topics\":[\"Goya\"],\"distinctive\":[\"pincel\"]}\n```"
        val (body, meta) = StructuredReplyParser.parse(raw)

        assertEquals("Body here.", body)
        assertEquals(listOf("Goya"), meta.topicPhrases)
        assertEquals(listOf("pincel"), meta.distinctive)
    }

    @Test
    fun encode_emptyMetadata_isNull() {
        assertNull(StructuredReplyParser.encode(ReplyMetadata()))
    }

    @Test
    fun encodeThenDecode_roundTrips() {
        val meta = ReplyMetadata(
            topics = listOf(TopicSuggestion("a", "first"), TopicSuggestion("b")),
            distinctive = listOf("c"),
        )
        val encoded = StructuredReplyParser.encode(meta)
        assertEquals(meta, StructuredReplyParser.decode(encoded))
    }

    @Test
    fun parse_topicObjects_captureGloss() {
        val raw = "Reply.\n${StructuredReplyParser.SENTINEL}\n" +
            """{"topics":[{"phrase":"La Reconquista","gloss":"the centuries-long retaking of Iberia"}],""" +
            """"distinctive":["reino"]}"""
        val (_, meta) = StructuredReplyParser.parse(raw)
        assertEquals(listOf("La Reconquista"), meta.topicPhrases)
        assertEquals("the centuries-long retaking of Iberia", meta.topics.single().gloss)
    }

    @Test
    fun parse_legacyBareStringTopics_stillDecode() {
        // Rows stored before topics carried glosses, and weaker models that emit bare strings.
        val raw = "Reply.\n${StructuredReplyParser.SENTINEL}\n{\"topics\":[\"Goya\",\"El Prado\"]}"
        val (_, meta) = StructuredReplyParser.parse(raw)
        assertEquals(listOf("Goya", "El Prado"), meta.topicPhrases)
        assertNull(meta.topics.first().gloss)
    }

    @Test
    fun decode_nullOrGarbage_isEmpty() {
        assertTrue(StructuredReplyParser.decode(null).isEmpty)
        assertTrue(StructuredReplyParser.decode("not json").isEmpty)
    }
}
