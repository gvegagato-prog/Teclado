package app.teclado.engine

import java.io.BufferedReader
import java.text.Normalizer

enum class Lang(val code: String, val label: String) {
    EN("en", "English"),
    ES("es", "Español"),
}

/** Lista de palabras de un idioma con su frecuencia (mayor = más común). Todo en minúsculas. */
class Lexicon(val lang: Lang, words: List<String>, freqs: IntArray) {
    private val map = HashMap<String, Int>(words.size * 2)
    private val folded = HashMap<String, String>()

    /** Palabras ordenadas alfabéticamente, para buscar por prefijo con búsqueda binaria. */
    val sorted: Array<String>
    private val sortedFreq: IntArray

    init {
        for (i in words.indices) {
            val prev = map[words[i]]
            if (prev == null || freqs[i] > prev) map[words[i]] = freqs[i]
        }
        val keys = map.keys.sorted()
        sorted = keys.toTypedArray()
        sortedFreq = IntArray(keys.size) { map.getValue(keys[it]) }
        for (i in keys.indices) {
            val f = fold(keys[i])
            if (f == keys[i]) continue
            val cur = folded[f]
            if (cur == null || sortedFreq[i] > (map[cur] ?: 0)) folded[f] = keys[i]
        }
    }

    fun freq(word: String): Int = map[word] ?: 0
    operator fun contains(word: String): Boolean = map.containsKey(word)

    /** Palabra con tildes/ñ cuya versión sin acentos coincide con [word], si existe. */
    fun withAccents(word: String): String? = folded[word]

    /** Hasta [k] palabras que empiezan por [prefix], de más a menos frecuentes. */
    fun completions(prefix: String, k: Int): List<String> {
        if (prefix.isEmpty()) return emptyList()
        var lo = 0
        var hi = sorted.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (sorted[mid] < prefix) lo = mid + 1 else hi = mid
        }
        val bestIdx = IntArray(k) { -1 }
        var scanned = 0
        var i = lo
        while (i < sorted.size && sorted[i].startsWith(prefix) && scanned < 8000) {
            val f = sortedFreq[i]
            var pos = k
            while (pos > 0 && (bestIdx[pos - 1] < 0 || sortedFreq[bestIdx[pos - 1]] < f)) pos--
            if (pos < k) {
                for (j in k - 1 downTo pos + 1) bestIdx[j] = bestIdx[j - 1]
                bestIdx[pos] = i
            }
            i++
            scanned++
        }
        return bestIdx.filter { it >= 0 }.map { sorted[it] }
    }

    companion object {
        /** Formato: una palabra por línea, `palabra<TAB>frecuencia`. */
        fun parse(lang: Lang, reader: BufferedReader): Lexicon {
            val words = ArrayList<String>(70000)
            val freqs = ArrayList<Int>(70000)
            reader.forEachLine { line ->
                val tab = line.indexOf('\t')
                if (tab > 0) {
                    val f = line.substring(tab + 1).toIntOrNull()
                    if (f != null) {
                        words.add(line.substring(0, tab))
                        freqs.add(f)
                    }
                }
            }
            return Lexicon(lang, words, freqs.toIntArray())
        }

        private val MARKS = Regex("\\p{M}+")
        fun fold(s: String): String = MARKS.replace(Normalizer.normalize(s, Normalizer.Form.NFD), "")
    }
}
