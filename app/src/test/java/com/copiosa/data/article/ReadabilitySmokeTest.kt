package com.copiosa.data.article

import net.dankito.readability4j.Readability4J
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Readability4J is compiled against an old jsoup and resolves whichever one the app pins, so a jsoup
 * bump can break it at link time. [ArticleExtractor] catches every Throwable around the parse and
 * reports it as "couldn't read this page", which would hide that — so this drives the same two calls
 * it makes (the Readability pass, then the block walk) on a fixed page.
 */
class ReadabilitySmokeTest {

    private val page = """
        <html><head><title>La casa del río</title></head><body>
          <nav><a href="/">Inicio</a><a href="/x">Contacto</a></nav>
          <article>
            <h1>La casa del río</h1>
            <p>${"En la orilla del río había una casa pequeña con un jardín lleno de flores. ".repeat(6)}</p>
            <p>${"Cada mañana la mujer que vivía allí salía a regar las plantas y a saludar a los vecinos. ".repeat(6)}</p>
            <ul><li>Primera cosa</li><li>Segunda cosa</li></ul>
          </article>
          <footer>Todos los derechos reservados</footer>
        </body></html>
    """.trimIndent()

    @Test
    fun `the readability pass runs on the pinned jsoup and keeps the article`() {
        val article = Readability4J("https://example.com/casa", page).parse()
        assertEquals("La casa del río", article.title?.trim())
        val text = article.textContent.orEmpty()
        assertTrue(text, "orilla del río" in text)
        assertFalse(text, "Todos los derechos reservados" in text)
    }

    @Test
    fun `the cleaned content parses into blocks`() {
        val content = Readability4J("https://example.com/casa", page).parse().content.orEmpty()
        val paragraphs = Jsoup.parse(content).body().select("p")
        assertTrue(paragraphs.size >= 2)
        assertTrue(paragraphs.first()?.text()?.startsWith("En la orilla") == true)
    }

    @Test
    fun `untrusted markup is parsed without executing or throwing`() {
        val hostile = "<p onclick='x()'>texto<script>alert(1)</script><iframe src='javascript:1'></iframe>" +
            "<table><tr><td>a</table><b><i>mal anidado</b></i>" + "<div>".repeat(5_000)
        val body = Jsoup.parse(hostile).body()
        assertTrue("texto" in body.text())
    }
}
