package app.teclado.engine

import kotlin.math.abs

/** Una corrección posible. [cost] mide lo "improbable" del error; [score] combina frecuencia y coste. */
class Candidate(val word: String, val score: Double, val cost: Double, val edits: Int, val crossLang: Boolean)

/**
 * Corrector bilingüe. Principio rector: **no tocar nunca una palabra real**. Una palabra se da por
 * buena si existe en cualquiera de los dos diccionarios completos (~300.000 palabras cada uno, incluidas
 * conjugaciones raras y términos técnicos) o en las palabras aprendidas. Solo se corrige lo que no existe,
 * y solo si hay una corrección claramente mejor que las demás.
 *
 * El coste de cada error sigue la geometría del teclado: pulsar una tecla vecina, intercambiar dos letras,
 * olvidar una tilde o una letra doble es barato; cambiar por una tecla lejana es caro.
 */
class SpellEngine(private val lexicons: Map<Lang, Lexicon>) {

    /** Palabras que el usuario ha aceptado explícitamente. */
    val userWords: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())

    fun isKnown(word: String): Boolean {
        val w = word.lowercase()
        if (w in userWords) return true
        for (lex in lexicons.values) if (w in lex) return true
        // "Pepe's", "MacBook's"... : la raíz basta
        if (w.endsWith("'s") && w.length > 3) return isKnown(w.dropLast(2))
        return knownWithClitics(w)
    }

    /**
     * Castellano: verbo con pronombres pegados (escanéalo, dímelo, cuéntamelo, vámonos, sentaos).
     * Es válido si el verbo sin los pronombres (y sin la tilde que añaden) existe.
     */
    private fun knownWithClitics(w: String): Boolean {
        val es = lexicons[Lang.ES] ?: return false
        for (c in CLITICS) {
            if (!w.endsWith(c) || w.length - c.length < 2) continue
            val stem = w.substring(0, w.length - c.length)
            val plain = Lexicon.unaccent(stem)
            if (stem in es || plain in es) return true
            if (c.startsWith("nos") && (plain + "s") in es) return true // vámonos -> vamos
            if (c.startsWith("os") && (plain + "d") in es) return true  // sentaos -> sentad
        }
        return false
    }

    fun suggest(word: String, lang: Lang, max: Int): List<Candidate> =
        candidates(word.lowercase(), lang).take(max)

    /** Todas las correcciones plausibles, de mejor a peor. */
    fun candidates(w: String, lang: Lang): List<Candidate> {
        if (w.isEmpty() || w.length > 24) return emptyList()
        val best = HashMap<String, Candidate>()
        for ((l, lex) in lexicons) {
            val cross = l != lang
            val ids = HashSet<Int>()
            collectEdits1(w, lex, if (l == Lang.ES) ALPHABET_ES else ALPHABET_EN, ids)
            lex.withAccents(Lexicon.fold(w)).let { if (it >= 0) ids.add(it) }
            val phon = lex.soundsLike(w)
            if (phon >= 0) ids.add(phon)
            if (!cross && w.length >= 5) lex.d2Candidates(w, ids)
            for (i in ids) {
                if (!lex.isCandidate(i)) continue
                val c = lex.word(i)
                if (c == w) continue
                var d = distance(w, c)
                if (i == phon && d.cost > PHONETIC) d = Dist(PHONETIC, 1) // error de ortografía "por sonido"
                if (d.edits == 0 || d.edits > 2) continue
                val cost = d.cost + if (cross) CROSS_LANG_COST else 0.0
                val score = FREQ_WEIGHT * lex.logFreq(i) - cost
                val cur = best[c]
                if (cur == null || score > cur.score) best[c] = Candidate(c, score, cost, d.edits, cross)
            }
        }
        return best.values.sortedByDescending { it.score }
    }

    private fun collectEdits1(w: String, lex: Lexicon, alphabet: String, out: MutableSet<Int>) {
        val sb = StringBuilder(w.length + 1)
        fun look(s: CharSequence) {
            val i = lex.indexOf(s)
            if (i >= 0) out.add(i)
        }
        val n = w.length
        for (i in 0 until n) { // borrado
            sb.setLength(0); sb.append(w, 0, i).append(w, i + 1, n); if (sb.isNotEmpty()) look(sb)
        }
        for (i in 0 until n - 1) { // transposición
            if (w[i] == w[i + 1]) continue
            sb.setLength(0); sb.append(w); sb.setCharAt(i, w[i + 1]); sb.setCharAt(i + 1, w[i]); look(sb)
        }
        for (i in 0 until n) for (a in alphabet) { // sustitución
            if (a == w[i]) continue
            sb.setLength(0); sb.append(w); sb.setCharAt(i, a); look(sb)
        }
        for (i in 0..n) for (a in alphabet) { // inserción
            sb.setLength(0); sb.append(w, 0, i).append(a).append(w, i, n); look(sb)
        }
    }

    /**
     * Corrección automática de [word], o null si hay que dejarla tal cual.
     * Muy conservadora: ante la duda, no se corrige (la sugerencia sigue en la barra).
     */
    fun correction(word: String, lang: Lang, langConfident: Boolean = true): String? {
        if (!langConfident) {
            // idioma aún incierto: vale la corrección de cualquiera de los dos si no se contradicen
            val a = correctionIn(word, lang)
            val b = correctionIn(word, if (lang == Lang.EN) Lang.ES else Lang.EN)
            return when {
                a == null -> b
                b == null || a == b -> a
                else -> null
            }
        }
        return correctionIn(word, lang)
    }

    private fun correctionIn(word: String, lang: Lang): String? {
        val w = word.lowercase()
        if (w.length < MIN_AUTOCORRECT_LEN) return null
        if (w in userWords) return null
        if (lang == Lang.ES) accentFix(w)?.let { return it }
        if (isKnown(w)) return null
        val cands = candidates(w, lang)
        val top = cands.firstOrNull() ?: return null
        if (top.crossLang) return null
        val maxCost = when {
            w.length <= 3 -> 2.5
            top.edits == 2 -> if (w.length >= 7) 6.5 else return null
            w.length >= 7 -> 5.6
            else -> 4.0
        }
        if (top.cost > maxCost) return null
        val second = cands.getOrNull(1)
        if (second != null && top.score - second.score < MARGIN) return null
        return top.word
    }

    /**
     * Castellano: palabras que existen pero casi siempre son la versión sin ñ o sin tilde de otra:
     * manana -> mañana, senor -> señor, ano -> año, decision -> decisión. Solo estos dos patrones:
     * otros (realizo/realizó, publico/público) son ambos correctos y no se tocan.
     */
    private fun accentFix(w: String): String? {
        val es = lexicons[Lang.ES] ?: return null
        val ai = es.withAccents(Lexicon.fold(w))
        if (ai < 0) return null
        val a = es.word(ai)
        if (a == w || a.length != w.length) return null
        val onlyEnye = w.indices.all { w[it] == a[it] || (w[it] == 'n' && a[it] == 'ñ') }
        val ion = w.endsWith("ion") && a.endsWith("ión") && w.dropLast(3) == a.dropLast(3)
        if (!onlyEnye && !ion) return null
        val wi = es.indexOf(w)
        val ratioOk = wi < 0 || es.band(wi) - es.band(ai) >= 130 // >= 20 veces más frecuente
        return if (ratioOk) a else null
    }

    class Dist(val cost: Double, val edits: Int)

    companion object {
        const val MIN_AUTOCORRECT_LEN = 3
        private const val MARGIN = 1.0
        private const val FREQ_WEIGHT = 0.5
        private const val CROSS_LANG_COST = 2.0

        private const val TRANSPOSE = 2.0
        private const val NEIGHBOR = 2.5
        private const val SUBST = 5.5
        private const val INDEL = 3.5
        private const val CHEAP_INDEL = 2.5
        private const val APOSTROPHE = 1.5
        private const val ACCENT = 1.0
        private const val FIRST_LETTER = 1.5
        private const val PHONETIC = 3.0

        /** Pronombres enclíticos, de más largo a más corto. */
        private val CLITICS = listOf(
            "noslos", "noslas", "melos", "melas", "telos", "telas", "selos", "selas", "noslo", "nosla",
            "oslos", "oslas", "melo", "mela", "telo", "tela", "selo", "sela", "oslo", "osla", "nosle",
            "los", "las", "les", "nos", "me", "te", "se", "lo", "la", "le", "os",
        )

        private const val ALPHABET_EN = "abcdefghijklmnopqrstuvwxyz'"
        private const val ALPHABET_ES = "abcdefghijklmnopqrstuvwxyzñáéíóúü"

        private val ROWS = arrayOf("qwertyuiop", "asdfghjklñ", "zxcvbnm")
        private val ROW_OFFSET = doubleArrayOf(0.0, 0.5, 1.5)
        private val POS = HashMap<Char, Pair<Double, Int>>().also { m ->
            for (r in ROWS.indices) for (i in ROWS[r].indices) m[ROWS[r][i]] = Pair(i + ROW_OFFSET[r], r)
        }

        private fun base(c: Char): Char = when (c) {
            'á' -> 'a'; 'é' -> 'e'; 'í' -> 'i'; 'ó' -> 'o'; 'ú', 'ü' -> 'u'
            else -> c
        }

        fun isNeighbor(a: Char, b: Char): Boolean {
            val pa = POS[base(a)] ?: return false
            val pb = POS[base(b)] ?: return false
            return abs(pa.first - pb.first) <= 1.0 && abs(pa.second - pb.second) <= 1
        }

        private fun subCost(a: Char, b: Char): Double = when {
            a == b -> 0.0
            base(a) == base(b) || (a == 'n' && b == 'ñ') || (a == 'ñ' && b == 'n') -> ACCENT
            isNeighbor(a, b) -> NEIGHBOR
            else -> SUBST
        }

        /**
         * Distancia de Damerau (OSA) ponderada entre lo tecleado [a] y la candidata [b].
         * Borrar a[i] = el usuario pulsó una tecla de más; insertar b[j] = se dejó una letra.
         */
        fun distance(a: String, b: String): Dist {
            val la = a.length
            val lb = b.length
            val w = lb + 1
            val d = DoubleArray((la + 1) * w)
            val e = IntArray((la + 1) * w)
            for (i in 1..la) { d[i * w] = d[(i - 1) * w] + extraCost(a, i - 1); e[i * w] = i }
            for (j in 1..lb) { d[j] = d[j - 1] + missingCost(b, j - 1); e[j] = j }
            for (i in 1..la) for (j in 1..lb) {
                val ca = a[i - 1]
                val cb = b[j - 1]
                var best = d[(i - 1) * w + j] + extraCost(a, i - 1)
                var be = e[(i - 1) * w + j] + 1
                val ins = d[i * w + j - 1] + missingCost(b, j - 1)
                if (ins < best) { best = ins; be = e[i * w + j - 1] + 1 }
                val sc = subCost(ca, cb)
                val sub = d[(i - 1) * w + j - 1] + sc
                if (sub < best) { best = sub; be = e[(i - 1) * w + j - 1] + if (ca == cb) 0 else 1 }
                if (i > 1 && j > 1 && ca == b[j - 2] && a[i - 2] == cb && ca != cb) {
                    val t = d[(i - 2) * w + j - 2] + TRANSPOSE
                    if (t < best) { best = t; be = e[(i - 2) * w + j - 2] + 1 }
                }
                d[i * w + j] = best
                e[i * w + j] = be
            }
            var cost = d[la * w + lb]
            if (la > 0 && lb > 0 && base(a[0]) != base(b[0])) cost += FIRST_LETTER
            return Dist(cost, e[la * w + lb])
        }

        /** Coste de que a[i] sobre (tecla de más). Barato si es letra repetida o vecina de la de al lado. */
        private fun extraCost(a: String, i: Int): Double {
            val c = a[i]
            if (c == '\'') return APOSTROPHE
            if (i > 0 && (a[i - 1] == c || isNeighbor(a[i - 1], c))) return CHEAP_INDEL
            if (i + 1 < a.length && (a[i + 1] == c || isNeighbor(a[i + 1], c))) return CHEAP_INDEL
            return INDEL
        }

        /** Coste de que falte b[j]. Barato si es apóstrofo o parte de una letra doble. */
        private fun missingCost(b: String, j: Int): Double {
            val c = b[j]
            if (c == '\'') return APOSTROPHE
            if ((j > 0 && b[j - 1] == c) || (j + 1 < b.length && b[j + 1] == c)) return CHEAP_INDEL
            return INDEL
        }

        /** Aplica a [corrected] el patrón de mayúsculas de [original]. */
        fun matchCase(original: String, corrected: String): String = when {
            original.length > 1 && original.all { !it.isLetter() || it.isUpperCase() } -> corrected.uppercase()
            original.isNotEmpty() && original[0].isUpperCase() -> corrected.replaceFirstChar { it.uppercase() }
            else -> corrected
        }
    }
}
