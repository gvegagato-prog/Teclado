package app.teclado.engine

import kotlin.math.abs

/** Una corrección posible. [cost] mide lo "improbable" del error; [score] combina frecuencia y coste. */
class Candidate(
    val word: String, val logFreq: Double, val cost: Double, val edits: Int, val crossLang: Boolean,
    val band: Int = 0, val split: Boolean = false,
) {
    /** Puntuación: la frecuencia suma y cada error resta. [fw] = cuánto pesa la frecuencia. */
    fun score(fw: Double = SpellEngine.FREQ_WEIGHT) = fw * logFreq - cost
    val score: Double get() = score()
}

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

    private val cache = object : LinkedHashMap<String, List<Candidate>>(64, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, List<Candidate>>) = size > 64
    }

    /** Todas las correcciones plausibles, de mejor a peor. Se guardan las últimas para que el espacio no recalcule. */
    fun candidates(w: String, lang: Lang): List<Candidate> {
        val key = lang.code + "|" + w
        synchronized(cache) { cache[key]?.let { return it } }
        val r = computeCandidates(w, lang)
        synchronized(cache) { cache[key] = r }
        return r
    }

    private fun computeCandidates(w: String, lang: Lang): List<Candidate> {
        if (w.isEmpty() || w.length > 24) return emptyList()
        val best = HashMap<String, Candidate>()
        fun consider(lex: Lexicon, ids: Set<Int>, cross: Boolean, phon: Int) {
            for (i in ids) {
                if (!lex.isCandidate(i)) continue
                val c = lex.word(i)
                if (c == w) continue
                var d = distance(w, c)
                // error de ortografía "por sonido" (nesecito -> necesito); solo si de verdad es una palabra parecida
                if (i == phon && d.cost > PHONETIC && w.length >= 5 && abs(w.length - c.length) <= 1 && unitDistance(w, c) <= 2) d = Dist(PHONETIC, 1)
                if (d.edits == 0 || d.edits > 2) continue
                val cost = d.cost + if (cross) CROSS_LANG_COST else 0.0
                val cand = Candidate(c, lex.logFreq(i), cost, d.edits, cross, lex.band(i))
                val cur = best[c]
                if (cur == null || cand.score > cur.score) best[c] = cand
            }
        }
        for ((l, lex) in lexicons) {
            val cross = l != lang
            val alphabet = if (l == Lang.ES) ALPHABET_ES else ALPHABET_EN
            val ids = HashSet<Int>()
            collectEdits1(w, lex, alphabet, ids)
            lex.withAccents(Lexicon.fold(w)).let { if (it >= 0) ids.add(it) }
            val phon = lex.soundsLike(w)
            if (phon >= 0) ids.add(phon)
            consider(lex, ids, cross, phon)
            // Dos fallos: si ya hay una corrección barata (tecla vecina, intercambio) no hace falta buscar más
            val cheap = best.values.any { it.cost <= CHEAP_ENOUGH && it.crossLang == cross && it.band <= DOUBLE_MAX_BAND }
            if (!cheap && w.length >= 4) {
                val ids2 = HashSet<Int>()
                collectKeyboardEdits2(w, lex, alphabet, ids2)
                if (!cross && w.length >= 5) lex.d2Candidates(w, ids2)
                consider(lex, ids2, cross, -1)
            }
        }
        splitCandidates(w, lang)?.let { best[it.word] = it }
        return best.values.sortedByDescending { it.score }
    }

    /** "holaque" -> "hola que": dos palabras comunes pegadas por no pulsar el espacio. */
    private fun splitCandidates(w: String, lang: Lang): Candidate? {
        if (w.length < 5) return null
        val lex = lexicons[lang] ?: return null
        var bestC: Candidate? = null
        for (i in 2..w.length - 2) {
            val a = lex.indexOf(w.substring(0, i))
            val b = lex.indexOf(w.substring(i))
            if (a < 0 || b < 0 || !lex.isCandidate(a) || !lex.isCandidate(b)) continue
            // partes de 1-2 letras solo si son palabras muy comunes (de, la, en, of, to...): evita "fl used"
            if ((i < 3 && lex.band(a) > SHORT_PART_BAND) || (w.length - i < 3 && lex.band(b) > SHORT_PART_BAND)) continue
            val band = maxOf(lex.band(a), lex.band(b))
            if (band > SPLIT_MAX_BAND) continue
            val cand = Candidate(w.substring(0, i) + " " + w.substring(i), minOf(lex.logFreq(a), lex.logFreq(b)), SPLIT_COST, 1, false, band, split = true)
            if (bestC == null || cand.score > bestC.score) bestC = cand
        }
        return bestC
    }

    /**
     * Segundo nivel de búsqueda, guiado por el teclado: de la errata se generan variantes con UN error típico
     * (tecla vecina, intercambio, letra de más o de menos) y de cada una se busca otra palabra a otro error típico.
     */
    private fun collectKeyboardEdits2(w: String, lex: Lexicon, alphabet: String, out: MutableSet<Int>) {
        val first = HashSet<String>()
        typicalEdits(w, alphabet, withInsert = true) { first.add(it) }
        for (v in first) {
            if (v.isEmpty()) continue
            // las operaciones se pueden combinar en cualquier orden: insertar letras solo en una de las dos fases
            typicalEdits(v, alphabet, withInsert = false) { s ->
                val i = lex.indexOf(s)
                if (i >= 0) out.add(i)
            }
        }
    }

    /** Variantes de [w] a un error típico: vecina, intercambio, borrado, inserción de cualquier letra. */
    private inline fun typicalEdits(w: String, alphabet: String, withInsert: Boolean, emit: (String) -> Unit) {
        val n = w.length
        val sb = StringBuilder(n + 1)
        for (i in 0 until n) {
            sb.setLength(0); sb.append(w, 0, i).append(w, i + 1, n)
            if (sb.isNotEmpty()) emit(sb.toString())
        }
        for (i in 0 until n - 1) {
            if (w[i] == w[i + 1]) continue
            sb.setLength(0); sb.append(w); sb.setCharAt(i, w[i + 1]); sb.setCharAt(i + 1, w[i]); emit(sb.toString())
        }
        for (i in 0 until n) for (a in NEIGHBORS[w[i]] ?: "") {
            sb.setLength(0); sb.append(w); sb.setCharAt(i, a); emit(sb.toString())
        }
        if (withInsert) for (i in 0..n) for (a in alphabet) {
            sb.setLength(0); sb.append(w, 0, i).append(a).append(w, i, n); emit(sb.toString())
        }
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
            // idioma aún incierto: vale la corrección de cualquiera de los dos; si discrepan, la de mayor puntuación
            val a = correctionCandidate(word, lang)
            val b = correctionCandidate(word, if (lang == Lang.EN) Lang.ES else Lang.EN)
            return when {
                a == null -> b?.word
                b == null -> a.word
                else -> (if (b.score > a.score) b else a).word
            }
        }
        return correctionCandidate(word, lang)?.word
    }

    private fun correctionCandidate(word: String, lang: Lang): Candidate? {
        val w = word.lowercase()
        if (w.length < MIN_AUTOCORRECT_LEN) return null
        if (w in userWords) return null
        if (lang == Lang.ES) accentFix(w)?.let { return Candidate(it, 100.0, 0.0, 1, false) }
        if (isKnown(w)) return null
        return decideCandidate(w, candidates(w, lang))
    }

    /** Umbrales de seguridad de la corrección automática (ajustados con el banco de pruebas). */
    class Gate(
        val shortMax: Double = 2.5,          // palabras de hasta 3 letras
        val singleCostShort: Double = 4.0,   // un error, palabra de hasta 6 letras
        val singleCostLong: Double = 5.6,    // un error, palabra de 7+ letras
        val marginSingle: Double = 1.0,
        val double4: Double = 5.8,           // dos errores: coste máximo según longitud
        val double6: Double = 6.2,
        val double7: Double = 7.0,
        val doubleBand: Int = 600,
        val marginDouble: Double = 0.3,
        val splitBand: Int = 500,
        val freqWeight: Double = FREQ_WEIGHT,
    )

    /** Decide si la mejor candidata se aplica sola. Muy conservadora: ante la duda, no se toca. */
    internal fun decide(w: String, candidates: List<Candidate>, g: Gate = gate): String? = decideCandidate(w, candidates, g)?.word

    internal fun decideCandidate(w: String, candidates: List<Candidate>, g: Gate = gate): Candidate? {
        val cands = if (g.freqWeight == FREQ_WEIGHT) candidates else candidates.sortedByDescending { it.score(g.freqWeight) }
        val top = cands.firstOrNull() ?: return null
        if (top.crossLang) return null
        val ok = when {
            top.split -> w.length >= 6 && top.band <= g.splitBand
            w.length <= 3 -> top.cost <= g.shortMax
            top.edits >= 2 -> w.length >= 4 && top.band <= g.doubleBand && top.cost <= when {
                w.length == 4 -> g.double4
                w.length <= 6 -> g.double6
                else -> g.double7
            }
            else -> top.cost <= if (w.length >= 7) g.singleCostLong else g.singleCostShort
        }
        if (!ok) return null
        // otra opción casi igual de buena -> mejor no tocar (ya aparece en la barra); en palabras largas hay menos ambigüedad
        val second = cands.getOrNull(1)
        val lenFactor = if (w.length <= 4) 1.0 else if (w.length <= 6) 0.8 else 0.6
        val margin = (if (top.edits >= 2) g.marginDouble else g.marginSingle) * lenFactor
        if (second != null && top.score(g.freqWeight) - second.score(g.freqWeight) < margin) return null
        return top
    }

    internal var gate = Gate()

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

    /** Costes de cada tipo de error de dedo (menor = error más probable). */
    class Costs(
        val transpose: Double = 1.75,
        val neighbor: Double = 2.0,
        val subst: Double = 5.5,
        val indel: Double = 3.0,
        val cheapIndel: Double = 2.5,
        val apostrophe: Double = 1.5,
        val accent: Double = 1.0,
        val firstLetter: Double = 1.5,
        val firstNeighbor: Double = 0.5,
    )

    companion object {
        const val MIN_AUTOCORRECT_LEN = 3
        private const val CHEAP_ENOUGH = 2.6
        private const val DOUBLE_MAX_BAND = 600 // para cortar la búsqueda barata (stage 2)
        private const val SPLIT_COST = 3.2
        private const val SHORT_PART_BAND = 300
        private const val SPLIT_MAX_BAND = 560
        const val FREQ_WEIGHT = 0.5
        private const val CROSS_LANG_COST = 2.0

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

        /** Teclas vecinas de cada tecla (para generar errores de dedo). */
        private val NEIGHBORS: Map<Char, String> = POS.keys.associateWith { c -> POS.keys.filter { it != c && isNeighborRaw(c, it) }.joinToString("") }

        private fun isNeighborRaw(a: Char, b: Char): Boolean {
            val pa = POS[a] ?: return false
            val pb = POS[b] ?: return false
            return abs(pa.first - pb.first) <= 1.0 && abs(pa.second - pb.second) <= 1
        }

        fun isNeighbor(a: Char, b: Char): Boolean {
            val pa = POS[base(a)] ?: return false
            val pb = POS[base(b)] ?: return false
            return abs(pa.first - pb.first) <= 1.0 && abs(pa.second - pb.second) <= 1
        }

        private fun subCost(a: Char, b: Char, c: Costs): Double = when {
            a == b -> 0.0
            base(a) == base(b) || (a == 'n' && b == 'ñ') || (a == 'ñ' && b == 'n') -> c.accent
            isNeighbor(a, b) -> c.neighbor
            else -> c.subst
        }

        /**
         * Distancia de Damerau (OSA) ponderada entre lo tecleado [a] y la candidata [b].
         * Borrar a[i] = el usuario pulsó una tecla de más; insertar b[j] = se dejó una letra.
         */
        internal var costs = Costs()

        fun distance(a: String, b: String, c: Costs = costs): Dist {
            val la = a.length
            val lb = b.length
            val w = lb + 1
            val d = DoubleArray((la + 1) * w)
            val e = IntArray((la + 1) * w)
            for (i in 1..la) { d[i * w] = d[(i - 1) * w] + extraCost(a, i - 1, c); e[i * w] = i }
            for (j in 1..lb) { d[j] = d[j - 1] + missingCost(b, j - 1, c); e[j] = j }
            for (i in 1..la) for (j in 1..lb) {
                val ca = a[i - 1]
                val cb = b[j - 1]
                var best = d[(i - 1) * w + j] + extraCost(a, i - 1, c)
                var be = e[(i - 1) * w + j] + 1
                val ins = d[i * w + j - 1] + missingCost(b, j - 1, c)
                if (ins < best) { best = ins; be = e[i * w + j - 1] + 1 }
                val sc = subCost(ca, cb, c)
                val sub = d[(i - 1) * w + j - 1] + sc
                if (sub < best) { best = sub; be = e[(i - 1) * w + j - 1] + if (ca == cb) 0 else 1 }
                if (i > 1 && j > 1 && ca == b[j - 2] && a[i - 2] == cb && ca != cb) {
                    val t = d[(i - 2) * w + j - 2] + c.transpose
                    if (t < best) { best = t; be = e[(i - 2) * w + j - 2] + 1 }
                }
                d[i * w + j] = best
                e[i * w + j] = be
            }
            var cost = d[la * w + lb]
            if (la > 0 && lb > 0 && base(a[0]) != base(b[0])) cost += if (isNeighbor(a[0], b[0])) c.firstNeighbor else c.firstLetter
            return Dist(cost, e[la * w + lb])
        }

        /** Coste de que a[i] sobre (tecla de más). Barato si es letra repetida o vecina de la de al lado. */
        private fun extraCost(a: String, i: Int, k: Costs): Double {
            val c = a[i]
            if (c == '\'') return k.apostrophe
            if (i > 0 && (a[i - 1] == c || isNeighbor(a[i - 1], c))) return k.cheapIndel
            if (i + 1 < a.length && (a[i + 1] == c || isNeighbor(a[i + 1], c))) return k.cheapIndel
            return k.indel
        }

        /** Coste de que falte b[j]. Barato si es apóstrofo o parte de una letra doble. */
        private fun missingCost(b: String, j: Int, k: Costs): Double {
            val c = b[j]
            if (c == '\'') return k.apostrophe
            if ((j > 0 && b[j - 1] == c) || (j + 1 < b.length && b[j + 1] == c)) return k.cheapIndel
            return k.indel
        }

        /** Distancia de edición simple (cada cambio cuenta 1; el intercambio de dos letras seguidas, 1). */
        internal fun unitDistance(a: String, b: String): Int {
            val la = a.length
            val lb = b.length
            val w = lb + 1
            val d = IntArray((la + 1) * w)
            for (i in 0..la) d[i * w] = i
            for (j in 0..lb) d[j] = j
            for (i in 1..la) for (j in 1..lb) {
                var v = minOf(d[(i - 1) * w + j] + 1, d[i * w + j - 1] + 1, d[(i - 1) * w + j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) v = minOf(v, d[(i - 2) * w + j - 2] + 1)
                d[i * w + j] = v
            }
            return d[la * w + lb]
        }

        /** Aplica a [corrected] el patrón de mayúsculas de [original]. */
        fun matchCase(original: String, corrected: String): String = when {
            original.length > 1 && original.all { !it.isLetter() || it.isUpperCase() } -> corrected.uppercase()
            original.isNotEmpty() && original[0].isUpperCase() -> corrected.replaceFirstChar { it.uppercase() }
            else -> corrected
        }
    }
}
