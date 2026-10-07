package com.copiosa.domain.vocab.freq

/**
 * The grammar words of a language: articles, prepositions, pronouns, conjunctions, and the handful of
 * adverbs and quantifiers that hold a sentence together.
 *
 * This exists because the bundled [FrequencyList]s are **content words only** — the Spanish list is
 * 1027 nouns, 510 verbs, 369 adjectives and 86 adverbs, with not one determiner or preposition in it,
 * and the Portuguese list is built the same way. Anything asking "does the learner know this token"
 * by looking the token up in the list therefore gets *no* for `la`, `de`, `el`, `en` and `que`, which
 * are roughly half of any real sentence.
 *
 * These are also the words a learner knows first and never has a card for, so no amount of deck
 * growth fixes it either. Counting them as known is what makes a comprehensibility ratio mean
 * something; see [com.copiosa.domain.cloze.ClozeGate].
 *
 * Deliberately hand-written rather than generated: the set is small, closed, and changes about once a
 * century, so a list in source is easier to check than a pipeline.
 */
object FunctionWords {

    private val SPANISH: Set<String> = setOf(
        // Articles and contractions.
        "el", "la", "los", "las", "un", "una", "unos", "unas", "lo", "al", "del",
        // Prepositions.
        "a", "ante", "bajo", "con", "contra", "de", "desde", "durante", "en", "entre", "hacia",
        "hasta", "mediante", "para", "por", "según", "segun", "sin", "sobre", "tras",
        // Personal, possessive and demonstrative pronouns.
        "yo", "tú", "tu", "vos", "él", "ella", "ello", "nosotros", "nosotras", "vosotros",
        "vosotras", "ellos", "ellas", "usted", "ustedes", "me", "te", "se", "nos", "os", "le",
        "les", "mí", "mi", "ti", "sí", "si", "conmigo", "contigo", "consigo",
        "este", "esta", "esto", "estos", "estas", "ese", "esa", "eso", "esos", "esas",
        "aquel", "aquella", "aquello", "aquellos", "aquellas",
        "mis", "tus", "su", "sus", "nuestro", "nuestra", "nuestros", "nuestras",
        "vuestro", "vuestra", "vuestros", "vuestras",
        "mío", "mia", "mía", "míos", "mias", "mías", "tuyo", "tuya", "tuyos", "tuyas",
        "suyo", "suya", "suyos", "suyas", "quien", "quién", "quienes", "quiénes",
        "cuyo", "cuya", "cuyos", "cuyas",
        // Conjunctions and subordinators.
        "y", "e", "o", "u", "ni", "pero", "sino", "aunque", "porque", "pues", "que", "qué",
        "como", "cómo", "cuando", "cuándo", "mientras", "donde", "dónde",
        "cual", "cuál", "cuales", "cuáles",
        "cuanto", "cuánto", "cuanta", "cuánta", "cuantos", "cuántos", "cuantas", "cuántas",
        // Negation, quantifiers and the commonest adverbs.
        "no", "tampoco", "también", "tambien", "muy", "mas", "más", "menos", "tan",
        "tanto", "tanta", "tantos", "tantas", "todo", "toda", "todos", "todas",
        "algo", "alguien", "alguno", "alguna", "algunos", "algunas", "algún", "algun",
        "nada", "nadie", "ninguno", "ninguna", "ningún", "ningun",
        "otro", "otra", "otros", "otras", "mucho", "mucha", "muchos", "muchas",
        "poco", "poca", "pocos", "pocas", "cada", "varios", "varias",
        "mismo", "misma", "mismos", "mismas",
        "ahí", "ahi", "allí", "alli", "allá", "alla", "aquí", "aqui", "acá", "aca",
        "ya", "aún", "aun", "todavía", "todavia", "siempre", "nunca", "jamás", "jamas",
        "entonces", "luego", "ahora", "así", "asi", "solo", "sólo", "solamente",
        "incluso", "además", "ademas", "casi", "tal", "bien", "hay",
    )

    private val PORTUGUESE: Set<String> = setOf(
        // Articles and the contractions Portuguese leans on constantly.
        "o", "a", "os", "as", "um", "uma", "uns", "umas",
        "do", "da", "dos", "das", "no", "na", "nos", "nas", "ao", "aos", "à", "às", "as",
        "pelo", "pela", "pelos", "pelas", "num", "numa", "nuns", "numas",
        "dum", "duma", "deste", "desta", "destes", "destas", "desse", "dessa", "desses", "dessas",
        "neste", "nesta", "nestes", "nestas", "nesse", "nessa", "nesses", "nessas", "nisso", "isso",
        // Prepositions.
        "de", "em", "para", "por", "com", "sem", "sobre", "sob", "entre", "até", "ate",
        "desde", "durante", "contra", "perante", "após", "apos", "ante", "trás", "tras",
        // Pronouns.
        "eu", "tu", "você", "voce", "ele", "ela", "nós", "nos", "vós", "vocês", "voces",
        "eles", "elas", "me", "te", "se", "lhe", "lhes", "mim", "ti", "si", "comigo", "contigo",
        "meu", "minha", "meus", "minhas", "teu", "tua", "teus", "tuas",
        "seu", "sua", "seus", "suas", "nosso", "nossa", "nossos", "nossas",
        "este", "esta", "estes", "estas", "esse", "essa", "esses", "essas",
        "aquele", "aquela", "aqueles", "aquelas", "aquilo", "isto",
        "quem", "cujo", "cuja", "cujos", "cujas",
        // Conjunctions and subordinators.
        "e", "ou", "mas", "porém", "porem", "porque", "pois", "que", "se", "como",
        "quando", "enquanto", "onde", "qual", "quais", "quanto", "quanta", "quantos", "quantas",
        "embora", "contudo", "todavia", "portanto", "assim",
        // Negation, quantifiers and the commonest adverbs.
        "não", "nao", "nem", "também", "tambem", "muito", "muita", "muitos", "muitas",
        "mais", "menos", "tão", "tao", "tanto", "tanta", "tantos", "tantas",
        "todo", "toda", "todos", "todas", "algo", "alguém", "alguem",
        "algum", "alguma", "alguns", "algumas", "nada", "ninguém", "ninguem",
        "nenhum", "nenhuma", "nenhuns", "nenhumas", "outro", "outra", "outros", "outras",
        "pouco", "pouca", "poucos", "poucas", "cada", "vários", "varios", "várias", "varias",
        "mesmo", "mesma", "mesmos", "mesmas",
        "aqui", "aí", "ai", "ali", "lá", "la", "cá", "ca",
        "já", "ja", "ainda", "sempre", "nunca", "jamais", "então", "entao", "depois", "agora",
        "só", "so", "somente", "apenas", "inclusive", "além", "alem", "quase", "bem", "há", "ha",
    )

    private val byTag = mapOf("es" to SPANISH, "pt" to PORTUGUESE)

    /**
     * The grammar words for a BCP-47 tag, or an empty set when none are bundled. The region subtag is
     * ignored, matching [FrequencyLists.forTag]. An empty set is safe everywhere: it only means a
     * caller learns nothing extra, which is the state every language was in before this existed.
     */
    fun forTag(tag: String): Set<String> =
        byTag[tag.substringBefore('-').lowercase()].orEmpty()
}
