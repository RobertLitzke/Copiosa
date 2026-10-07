package com.copiosa.domain.routine

/**
 * A cheap heuristic for how calming a topic seems (0 = agitating, 1 = soothing, 0.5 = neutral), so a
 * bedtime routine can prefer soothing seeds. Not a classifier — just keyword nudges around neutral,
 * in the app's interface languages plus each supported target language. The routine generator is also
 * told "calming", so this only needs to bias the suggestion ordering, not be exact.
 *
 * A language with no words here simply scores every topic 0.5, which orders suggestions arbitrarily
 * rather than wrongly.
 */
object CalmHeuristic {
    private val calming = setOf(
        "nature", "forest", "sea", "ocean", "garden", "rain", "sleep", "dream", "star", "moon", "tea",
        "walk", "quiet", "calm", "river", "lake", "mountain", "tree", "bird", "cloud",
        "naturaleza", "bosque", "mar", "oceano", "océano", "jardin", "jardín", "lluvia", "sueño", "sueno",
        "estrella", "luna", "paseo", "calma", "tranquilo", "tranquila", "silencio", "montaña", "montana",
        "río", "rio", "lago", "árbol", "arbol", "pájaro", "pajaro", "nube",
        // Portuguese. Overlaps Spanish where the words coincide; only the divergent ones are listed.
        "natureza", "floresta", "jardim", "chuva", "sonho", "estrelas", "lua", "caminhada", "calmo",
        "calma", "silêncio", "silencio", "montanha", "árvore", "arvore", "pássaro", "passaro", "nuvem",
        "praia", "chá", "cha",
    )
    private val agitating = setOf(
        "war", "fight", "crime", "death", "attack", "disaster", "politics", "argument", "horror",
        "violence", "murder", "terror", "crash", "panic", "emergency",
        "guerra", "pelea", "crimen", "muerte", "ataque", "desastre", "política", "politica", "violencia",
        "asesinato", "pánico", "panico", "emergencia", "accidente",
        // Portuguese.
        "briga", "morte", "ataque", "desastre", "política", "politica", "violência", "violencia",
        "assassinato", "pânico", "panico", "emergência", "emergencia", "acidente", "medo",
    )

    fun score(text: String): Double {
        val words = text.lowercase().split(Regex("[^\\p{L}]+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return 0.5
        var score = 0.5
        for (w in words) {
            if (w in calming) score += 0.2
            if (w in agitating) score -= 0.3
        }
        return score.coerceIn(0.0, 1.0)
    }
}
