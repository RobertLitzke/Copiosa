package com.copiosa.ui.flashcards

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageQueryTest {
    @Test fun `keeps a clean single-word gloss`() {
        assertEquals("dog", imageSearchQuery("dog"))
    }

    @Test fun `takes only the first sense`() {
        assertEquals("call", imageSearchQuery("to call; to phone, to be named"))
    }

    @Test fun `strips parentheticals and brackets`() {
        assertEquals("be named", imageSearchQuery("(reflexive) to be named [fig.]"))
    }

    @Test fun `drops a leading article`() {
        assertEquals("dog", imageSearchQuery("the dog, the cat"))
    }

    @Test fun `strips html tags`() {
        assertEquals("house", imageSearchQuery("<b>house</b>"))
    }

    @Test fun `blank when nothing usable remains`() {
        assertEquals("", imageSearchQuery("(informal)"))
    }
}
