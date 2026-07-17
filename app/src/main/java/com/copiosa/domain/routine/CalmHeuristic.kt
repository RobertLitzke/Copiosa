package com.copiosa.domain.routine

/**
 * A cheap heuristic for how calming a topic seems (0 = agitating, 1 = soothing, 0.5 = neutral), so a
 * bedtime routine can prefer soothing seeds. Not a classifier — just keyword nudges around neutral,
 * in English and Spanish. The routine generator is also told "calming", so this only needs to bias
 * the suggestion ordering, not be exact.
 */
object CalmHeuristic {
    private val calming = setOf(
        "nature", "forest", "sea", "ocean", "garden", "rain", "sleep", "dream", "star", "moon", "tea",
        "walk", "quiet", "calm", "river", "lake", "mountain", "tree", "bird", "cloud",
        "naturaleza", "bosque", "mar", "oceano", "océano", "jardin", "jardín", "lluvia", "sueño", "sueno",
        "estrella", "luna", "paseo", "calma", "tranquilo", "tranquila", "silencio", "montaña", "montana",
        "río", "rio", "lago", "árbol", "arbol", "pájaro", "pajaro", "nube",
    )
    private val agitating = setOf(
        "war", "fight", "crime", "death", "attack", "disaster", "politics", "argument", "horror",
        "violence", "murder", "terror", "crash", "panic", "emergency",
        "guerra", "pelea", "crimen", "muerte", "ataque", "desastre", "política", "politica", "violencia",
        "asesinato", "pánico", "panico", "emergencia", "accidente",
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
