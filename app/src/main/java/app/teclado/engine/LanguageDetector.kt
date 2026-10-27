package app.teclado.engine

import kotlin.math.ln

/**
 * Decide en qué idioma escribe el usuario a partir de las últimas palabras.
 * Cada palabra aporta un voto (>0 español, <0 inglés) según su frecuencia relativa en cada
 * diccionario; los votos antiguos se desvanecen y hay histéresis para no oscilar.
 */
class LanguageDetector(private val en: Lexicon?, private val es: Lexicon?, initial: Lang) {
    var current: Lang = initial
        private set
    private var score = 0.0

    /** false mientras no hay pruebas suficientes de un idioma (p. ej. al empezar a escribir). */
    val confident: Boolean get() = kotlin.math.abs(score) >= SWITCH_THRESHOLD

    fun reset(lang: Lang) {
        score = 0.0
        current = lang
    }

    /** Siembra el estado con el texto previo al cursor (al entrar en un campo). */
    fun seed(text: CharSequence?, fallback: Lang) {
        reset(fallback)
        if (text.isNullOrBlank()) return
        for (w in WORD.findAll(text).map { it.value }.toList().takeLast(SEED_WORDS)) observe(w)
    }

    fun observe(word: String) {
        val w = word.lowercase()
        if (w.length < 2 && w != "y" && w != "a" && w != "o") return
        var e = 0.0
        val fEs = es?.freq(w) ?: 0.0
        val fEn = en?.freq(w) ?: 0.0
        if (fEs > 0 || fEn > 0) {
            e = ln((fEs + SMOOTH) / (fEn + SMOOTH)).coerceIn(-MAX_VOTE, MAX_VOTE)
        }
        if (w.any { it in SPANISH_ONLY }) e += SPANISH_CHAR_BONUS
        score = score * DECAY + e
        if (score > SWITCH_THRESHOLD) current = Lang.ES
        else if (score < -SWITCH_THRESHOLD) current = Lang.EN
    }

    companion object {
        private val WORD = Regex("[\\p{L}']+")
        private const val SMOOTH = 500.0
        private const val MAX_VOTE = 3.0
        private const val DECAY = 0.85
        private const val SWITCH_THRESHOLD = 1.5
        private const val SPANISH_CHAR_BONUS = 2.0
        private const val SEED_WORDS = 14
        private const val SPANISH_ONLY = "ñáéíóúü¿¡"
    }
}
