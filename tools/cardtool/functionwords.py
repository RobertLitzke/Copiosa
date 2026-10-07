"""Spanish grammar words, extracted verbatim from ``domain/vocab/freq/FunctionWords.kt``.

The bundled frequency lists are content words only, so without these every ``la``, ``de``
and ``que`` scores as unknown vocabulary and no real sentence clears the gate's
comprehensibility bar. Regenerate with ``tools/cardtool/_regen_functionwords.py`` if the
Kotlin set changes.
"""

from __future__ import annotations

SPANISH: frozenset[str] = frozenset({
    "el", "la", "los", "las", "un", "una", "unos", "unas",
    "lo", "al", "del", "a", "ante", "bajo", "con", "contra",
    "de", "desde", "durante", "en", "entre", "hacia", "hasta", "mediante",
    "para", "por", "según", "segun", "sin", "sobre", "tras", "yo",
    "tú", "tu", "vos", "él", "ella", "ello", "nosotros", "nosotras",
    "vosotros", "vosotras", "ellos", "ellas", "usted", "ustedes", "me", "te",
    "se", "nos", "os", "le", "les", "mí", "mi", "ti",
    "sí", "si", "conmigo", "contigo", "consigo", "este", "esta", "esto",
    "estos", "estas", "ese", "esa", "eso", "esos", "esas", "aquel",
    "aquella", "aquello", "aquellos", "aquellas", "mis", "tus", "su", "sus",
    "nuestro", "nuestra", "nuestros", "nuestras", "vuestro", "vuestra", "vuestros", "vuestras",
    "mío", "mia", "mía", "míos", "mias", "mías", "tuyo", "tuya",
    "tuyos", "tuyas", "suyo", "suya", "suyos", "suyas", "quien", "quién",
    "quienes", "quiénes", "cuyo", "cuya", "cuyos", "cuyas", "y", "e",
    "o", "u", "ni", "pero", "sino", "aunque", "porque", "pues",
    "que", "qué", "como", "cómo", "cuando", "cuándo", "mientras", "donde",
    "dónde", "cual", "cuál", "cuales", "cuáles", "cuanto", "cuánto", "cuanta",
    "cuánta", "cuantos", "cuántos", "cuantas", "cuántas", "no", "tampoco", "también",
    "tambien", "muy", "mas", "más", "menos", "tan", "tanto", "tanta",
    "tantos", "tantas", "todo", "toda", "todos", "todas", "algo", "alguien",
    "alguno", "alguna", "algunos", "algunas", "algún", "algun", "nada", "nadie",
    "ninguno", "ninguna", "ningún", "ningun", "otro", "otra", "otros", "otras",
    "mucho", "mucha", "muchos", "muchas", "poco", "poca", "pocos", "pocas",
    "cada", "varios", "varias", "mismo", "misma", "mismos", "mismas", "ahí",
    "ahi", "allí", "alli", "allá", "alla", "aquí", "aqui", "acá",
    "aca", "ya", "aún", "aun", "todavía", "todavia", "siempre", "nunca",
    "jamás", "jamas", "entonces", "luego", "ahora", "así", "asi", "solo",
    "sólo", "solamente", "incluso", "además", "ademas", "casi", "tal", "bien",
    "hay",
})
