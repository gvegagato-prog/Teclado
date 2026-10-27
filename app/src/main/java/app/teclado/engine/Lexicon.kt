package app.teclado.engine

import java.text.Normalizer
import kotlin.math.ln
import kotlin.math.min

enum class Lang(val code: String, val label: String) {
    EN("en", "English"),
    ES("es", "Español"),
}

/**
 * Diccionario completo de un idioma (~300.000 palabras) guardado de forma compacta:
 * todas las letras en un único CharArray y la palabra i entre offs[i] y offs[i+1], ordenadas
 * alfabéticamente para buscar por búsqueda binaria.
 *
 * [band] es la frecuencia en centibelios de wordfreq (0 = la más frecuente). Todas las palabras sirven
 * para saber si algo existe; solo las de band <= [candBand] se proponen como corrección.
 */
class Lexicon private constructor(
    val lang: Lang,
    private val chars: CharArray,
    private val offs: IntArray,
    private val bands: ShortArray,
    val candBand: Int,
    private val d2Band: Int,
) {
    val size: Int get() = bands.size

    fun word(i: Int): String = String(chars, offs[i], offs[i + 1] - offs[i])
    fun band(i: Int): Int = bands[i].toInt()
    fun isCandidate(i: Int): Boolean = bands[i] <= candBand

    /** log natural de la frecuencia relativa, escalada para que sea >= 0. */
    fun logFreq(i: Int): Double = (MAX_BAND - bands[i]) / 100.0 * LN10

    /** Frecuencia (escala arbitraria, comparable entre idiomas) o 0 si no existe. */
    fun freq(word: String): Double {
        val i = indexOf(word)
        return if (i < 0) 0.0 else Math.pow(10.0, (MAX_BAND - bands[i]) / 100.0)
    }

    operator fun contains(word: CharSequence): Boolean = indexOf(word) >= 0

    private fun cmp(i: Int, w: CharSequence): Int {
        val s = offs[i]
        val len = offs[i + 1] - s
        val n = min(len, w.length)
        for (k in 0 until n) {
            val d = chars[s + k] - w[k]
            if (d != 0) return d
        }
        return len - w.length
    }

    fun indexOf(w: CharSequence): Int {
        var lo = 0
        var hi = size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val c = cmp(mid, w)
            if (c < 0) lo = mid + 1 else if (c > 0) hi = mid - 1 else return mid
        }
        return -1
    }

    private fun lowerBound(prefix: String): Int {
        var lo = 0
        var hi = size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (cmp(mid, prefix) < 0) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun startsWith(i: Int, prefix: String): Boolean {
        val s = offs[i]
        if (offs[i + 1] - s < prefix.length) return false
        for (k in prefix.indices) if (chars[s + k] != prefix[k]) return false
        return true
    }

    // ---- variantes con tilde: "tambien" -> "también" (solo palabras candidatas) ----
    private val folded: HashMap<String, Int> by lazy {
        val m = HashMap<String, Int>()
        for (i in 0 until size) {
            if (!isCandidate(i)) continue
            val s = offs[i]
            var accented = false
            for (k in s until offs[i + 1]) if (chars[k].code > 127) { accented = true; break }
            if (!accented) continue
            val w = word(i)
            val f = fold(w)
            if (f == w) continue
            val cur = m[f]
            if (cur == null || bands[i] < bands[cur]) m[f] = i
        }
        m
    }

    fun withAccents(word: String): Int = folded[word] ?: -1

    /** Hasta [k] palabras frecuentes que empiezan por [prefix], de más a menos frecuentes. */
    fun completions(prefix: String, k: Int): List<String> {
        if (prefix.isEmpty()) return emptyList()
        val best = IntArray(k) { -1 }
        var i = lowerBound(prefix)
        var scanned = 0
        while (i < size && scanned < 40000 && startsWith(i, prefix)) {
            if (isCandidate(i)) {
                val b = bands[i]
                var pos = k
                while (pos > 0 && (best[pos - 1] < 0 || bands[best[pos - 1]] > b)) pos--
                if (pos < k) {
                    for (j in k - 1 downTo pos + 1) best[j] = best[j - 1]
                    best[pos] = i
                }
            }
            i++
            scanned++
        }
        return best.filter { it >= 0 }.map { word(it) }
    }

    // ---- índice fonético (solo castellano): "nesecito" suena como "necesito" ----
    private val phonetic: HashMap<String, Int> by lazy {
        val m = HashMap<String, Int>()
        if (lang == Lang.ES) for (i in 0 until size) {
            if (bands[i] > d2Band) continue
            val k = phoneticEs(word(i))
            val cur = m[k]
            if (cur == null || bands[i] < bands[cur]) m[k] = i
        }
        m
    }

    /** Palabra frecuente que suena igual que [w] en castellano, o -1. */
    fun soundsLike(w: String): Int = if (lang == Lang.ES) phonetic[phoneticEs(w)] ?: -1 else -1

    // ---- índice de distancia 2 (SymSpell: borrados del prefijo) ----
    @Volatile private var d2: LongArray? = null

    /** Construye los índices de errores dobles y fonético. Tarda un poco: llamar en segundo plano. */
    fun buildD2Index() {
        phonetic.size
        folded.size
        if (d2 != null) return
        var buf = LongArray(1 shl 20)
        var n = 0
        val seen = HashSet<String>()
        for (i in 0 until size) {
            if (bands[i] > d2Band) continue
            val len = offs[i + 1] - offs[i]
            if (len < 4) continue
            seen.clear()
            deletes(String(chars, offs[i], min(len, PREFIX)), 2, seen)
            for (v in seen) {
                if (n == buf.size) buf = buf.copyOf(n * 2)
                buf[n++] = (v.hashCode().toLong() shl 32) or i.toLong()
            }
        }
        val arr = buf.copyOf(n)
        arr.sort()
        d2 = arr
    }

    /** Índices de palabras que pueden estar a distancia <= 2 de [q] (hay que verificarlas después). */
    fun d2Candidates(q: String, out: MutableSet<Int>) {
        val idx = d2 ?: return
        val variants = HashSet<String>()
        deletes(q.substring(0, min(q.length, PREFIX)), 2, variants)
        for (v in variants) {
            val h = v.hashCode()
            var lo = 0
            var hi = idx.size
            val key = h.toLong() shl 32
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (idx[mid] < key) lo = mid + 1 else hi = mid
            }
            while (lo < idx.size && (idx[lo] shr 32).toInt() == h) {
                out.add((idx[lo] and 0xffffffffL).toInt())
                lo++
            }
        }
    }

    companion object {
        const val MAX_BAND = 900
        private val LN10 = ln(10.0)
        private const val PREFIX = 7

        private fun deletes(s: String, depth: Int, out: MutableSet<String>) {
            if (!out.add(s) || depth == 0 || s.length <= 1) return
            for (i in s.indices) deletes(s.removeRange(i, i + 1), depth - 1, out)
        }

        /** Formato: cabeceras `#cand N`, `#d2 N` y después `palabra<TAB>banda`, ordenado. */
        fun parse(lang: Lang, text: String): Lexicon {
            var cand = MAX_BAND
            var d2 = 0
            val chars = CharArray(text.length)
            var nChars = 0
            var offs = IntArray(400_000)
            var bands = ShortArray(400_000)
            var n = 0
            var pos = 0
            val len = text.length
            while (pos < len) {
                var end = text.indexOf('\n', pos)
                if (end < 0) end = len
                if (text[pos] == '#') {
                    val line = text.substring(pos, end)
                    val v = line.substringAfter(' ').trim().toIntOrNull()
                    if (v != null) {
                        if (line.startsWith("#cand")) cand = v
                        if (line.startsWith("#d2")) d2 = v
                    }
                } else {
                    val tab = text.indexOf('\t', pos)
                    if (tab in (pos + 1) until end) {
                        if (n + 2 >= offs.size) {
                            offs = offs.copyOf(offs.size * 2)
                            bands = bands.copyOf(bands.size * 2)
                        }
                        offs[n] = nChars
                        for (k in pos until tab) chars[nChars++] = text[k]
                        var b = 0
                        for (k in tab + 1 until end) {
                            val c = text[k]
                            if (c in '0'..'9') b = b * 10 + (c - '0')
                        }
                        bands[n] = b.coerceAtMost(MAX_BAND).toShort()
                        n++
                    }
                }
                pos = end + 1
            }
            offs[n] = nChars
            return Lexicon(lang, chars.copyOf(nChars), offs.copyOf(n + 1), bands.copyOf(n), cand, d2)
        }

        private val MARKS = Regex("\\p{M}+")
        fun fold(s: String): String = MARKS.replace(Normalizer.normalize(s, Normalizer.Form.NFD), "")

        /** Quita tildes y diéresis pero conserva la ñ. */
        fun unaccent(s: String): String {
            val sb = StringBuilder(s.length)
            for (c in s) sb.append(
                when (c) {
                    'á' -> 'a'; 'é' -> 'e'; 'í' -> 'i'; 'ó' -> 'o'; 'ú', 'ü' -> 'u'
                    else -> c
                }
            )
            return sb.toString()
        }

        /**
         * Clave fonética del castellano (seseo, b/v, h muda, ll/y, g/j, c/qu/k; letras dobles colapsadas).
         * Debe coincidir con phon_es() de tools/gen_dicts.py.
         */
        fun phoneticEs(word: String): String {
            val w = unaccent(word)
            val out = StringBuilder(w.length)
            var i = 0
            val n = w.length
            while (i < n) {
                val c = w[i]
                val nx = if (i + 1 < n) w[i + 1] else ' '
                when {
                    c == 'c' && nx == 'h' -> { out.append('C'); i += 2; continue }
                    c == 'q' && nx == 'u' -> { out.append('k'); i += 2; continue }
                    c == 'g' && nx == 'u' && i + 2 < n && (w[i + 2] == 'e' || w[i + 2] == 'i') -> { out.append('g'); i += 2; continue }
                    c == 'l' && nx == 'l' -> { out.append('y'); i += 2; continue }
                    c == 'c' -> out.append(if (nx == 'e' || nx == 'i') 's' else 'k')
                    c == 'g' -> out.append(if (nx == 'e' || nx == 'i') 'j' else 'g')
                    c == 'z' -> out.append('s')
                    c == 'v' || c == 'w' -> out.append('b')
                    c == 'h' -> {}
                    c == 'x' && i == 0 -> out.append('s')
                    else -> out.append(c)
                }
                i++
            }
            val r = StringBuilder(out.length)
            for (ch in out) if (r.isEmpty() || r[r.length - 1] != ch) r.append(ch)
            return r.toString()
        }
    }
}
