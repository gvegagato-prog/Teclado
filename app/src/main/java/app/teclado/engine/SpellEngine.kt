package app.teclado.engine

import kotlin.math.abs
import kotlin.math.ln

class Candidate(val word: String, val score: Double, val cost: Double)

/**
 * Corrector ortográfico bilingüe. Genera candidatos a distancia de edición 1
 * (borrado, inserción, sustitución, transposición) más variantes con/sin tilde,
 * y los ordena por frecuencia penalizando cada edición (barata si es una tecla vecina).
 */
class SpellEngine(private val lexicons: Map<Lang, Lexicon>) {

    /** Palabras que el usuario ha aceptado explícitamente. */
    val userWords = HashSet<String>()

    fun isKnown(word: String, lang: Lang): Boolean {
        val w = word.lowercase()
        if (w in userWords) return true
        if (lexicons[lang]?.contains(w) == true) return true
        // Palabra de la otra lengua: solo si es lo bastante común, para no dar por buenas erratas raras.
        val other = lexicons[other(lang)] ?: return false
        return other.freq(w) >= OTHER_LANG_MIN_FREQ
    }

    fun suggest(word: String, lang: Lang, max: Int): List<Candidate> {
        val lex = lexicons[lang] ?: return emptyList()
        val w = word.lowercase()
        if (w.isEmpty()) return emptyList()
        val best = HashMap<String, Candidate>()
        fun offer(c: String, cost: Double) {
            if (c == w) return
            val f = lex.freq(c)
            if (f <= 0) return
            val firstPenalty = if (c[0] != w[0]) FIRST_LETTER_PENALTY else 0.0
            val total = cost + firstPenalty
            val score = FREQ_WEIGHT * ln(f.toDouble()) - total
            val cur = best[c]
            if (cur == null || score > cur.score) best[c] = Candidate(c, score, total)
        }

        lex.withAccents(w)?.let { offer(it, ACCENT_COST) }
        val alphabet = if (lang == Lang.ES) ALPHABET_ES else ALPHABET_EN
        val n = w.length

        // borrado de una letra del original (el usuario tecleó de más)
        for (i in 0 until n) {
            val c = w.removeRange(i, i + 1)
            if (c.isNotEmpty()) offer(c, if (i > 0 && w[i] == w[i - 1]) DOUBLE_COST else INDEL_COST)
        }
        // transposición
        for (i in 0 until n - 1) {
            if (w[i] == w[i + 1]) continue
            val chars = w.toCharArray()
            chars[i] = w[i + 1]; chars[i + 1] = w[i]
            offer(String(chars), TRANSPOSE_COST)
        }
        // sustitución
        for (i in 0 until n) {
            val orig = w[i]
            for (a in alphabet) {
                if (a == orig) continue
                val chars = w.toCharArray()
                chars[i] = a
                val cost = when {
                    Lexicon.fold(a.toString()) == Lexicon.fold(orig.toString()) -> ACCENT_COST
                    isNeighbor(orig, a) -> NEIGHBOR_COST
                    else -> SUBST_COST
                }
                offer(String(chars), cost)
            }
        }
        // inserción (el usuario se dejó una letra)
        for (i in 0..n) {
            for (a in alphabet) {
                val c = w.substring(0, i) + a + w.substring(i)
                offer(c, if (a == '\'') APOSTROPHE_COST else INDEL_COST)
            }
        }
        return best.values.sortedByDescending { it.score }.take(max)
    }

    /** Corrección automática de [word], o null si no hay que tocarla. */
    fun correction(word: String, lang: Lang): String? {
        if (word.length < MIN_AUTOCORRECT_LEN) return null
        if (isKnown(word, lang)) return null
        val top = suggest(word, lang, 1).firstOrNull() ?: return null
        if (top.cost > MAX_AUTOCORRECT_COST) return null
        val lex = lexicons[lang] ?: return null
        if (lex.freq(top.word) < MIN_AUTOCORRECT_FREQ) return null
        return top.word
    }

    private fun other(l: Lang) = if (l == Lang.EN) Lang.ES else Lang.EN

    companion object {
        const val OTHER_LANG_MIN_FREQ = 3000
        const val MIN_AUTOCORRECT_LEN = 3
        const val MIN_AUTOCORRECT_FREQ = 1500
        const val MAX_AUTOCORRECT_COST = 6.0

        private const val TRANSPOSE_COST = 2.0
        private const val NEIGHBOR_COST = 2.5
        private const val SUBST_COST = 5.5
        private const val FREQ_WEIGHT = 0.5
        private const val INDEL_COST = 3.5
        private const val DOUBLE_COST = 2.5
        private const val APOSTROPHE_COST = 1.5
        private const val ACCENT_COST = 1.0
        private const val FIRST_LETTER_PENALTY = 2.0

        private const val ALPHABET_EN = "abcdefghijklmnopqrstuvwxyz'"
        private const val ALPHABET_ES = "abcdefghijklmnopqrstuvwxyzñáéíóúü"

        private val ROWS = arrayOf("qwertyuiop", "asdfghjklñ", "zxcvbnm")
        private val ROW_OFFSET = doubleArrayOf(0.0, 0.5, 1.5)
        private val POS = HashMap<Char, Pair<Double, Int>>().also { m ->
            for (r in ROWS.indices) for (i in ROWS[r].indices) m[ROWS[r][i]] = Pair(i + ROW_OFFSET[r], r)
        }

        fun isNeighbor(a: Char, b: Char): Boolean {
            val pa = POS[Lexicon.fold(a.toString())[0]] ?: return false
            val pb = POS[Lexicon.fold(b.toString())[0]] ?: return false
            return abs(pa.first - pb.first) <= 1.0 && abs(pa.second - pb.second) <= 1
        }

        /** Aplica a [corrected] el patrón de mayúsculas de [original]. */
        fun matchCase(original: String, corrected: String): String = when {
            original.length > 1 && original.all { !it.isLetter() || it.isUpperCase() } -> corrected.uppercase()
            original.isNotEmpty() && original[0].isUpperCase() ->
                corrected.replaceFirstChar { it.uppercase() }
            else -> corrected
        }
    }
}
