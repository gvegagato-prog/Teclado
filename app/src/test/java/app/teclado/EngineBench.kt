package app.teclado

import app.teclado.engine.Lang
import app.teclado.engine.Lexicon
import app.teclado.engine.SpellEngine
import org.junit.Test
import java.io.File
import java.util.Random

/** Banco de pruebas del corrector con erratas sintéticas pero realistas. Imprime estadísticas. */
class EngineBench {
    private val engine = EngineTest.engine
    private val rnd = Random(42)
    private val rows = arrayOf("qwertyuiop", "asdfghjklñ", "zxcvbnm")

    private fun neighbors(c: Char): List<Char> =
        rows.flatMap { it.toList() }.filter { SpellEngine.isNeighbor(c, it) && it != c }

    private fun subst(w: String, n: Int): String? {
        val chars = w.toCharArray()
        val idx = (0 until w.length).filter { neighbors(chars[it]).isNotEmpty() && chars[it] in "abcdefghijklmnñopqrstuvwxyz" }.shuffled(rnd)
        if (idx.size < n) return null
        for (k in 0 until n) { val nb = neighbors(chars[idx[k]]); chars[idx[k]] = nb[rnd.nextInt(nb.size)] }
        return String(chars)
    }
    private fun missing(w: String): String? { if (w.length < 4) return null; val i = rnd.nextInt(w.length); return w.removeRange(i, i + 1) }
    private fun extra(w: String): String { val i = rnd.nextInt(w.length + 1); val c = w[minOf(i, w.length - 1)]; val nb = neighbors(c); return w.substring(0, i) + (if (nb.isEmpty()) c else nb[rnd.nextInt(nb.size)]) + w.substring(i) }
    private fun transpose(w: String): String? { val i = (0 until w.length - 1).filter { w[it] != w[it + 1] }.shuffled(rnd).firstOrNull() ?: return null; val c = w.toCharArray(); val t = c[i]; c[i] = c[i + 1]; c[i + 1] = t; return String(c) }

    private fun realWords(l: Lang, n: Int, minLen: Int): List<String> {
        val lines = File("src/main/assets/dict/${l.code}.tsv").readLines().filter { !it.startsWith("#") }.map { it.split("\t") }.filter { it.size == 2 }
        val pool = lines.sortedBy { it[1].toInt() }.take(20000)
            .filter { it[0].length >= minLen && it[0].all { c -> c in "abcdefghijklmnñopqrstuvwxyzáéíóúü" } }
        // ponderado por frecuencia real (como escribe la gente)
        val weights = pool.map { Math.pow(10.0, -it[1].toInt() / 100.0) }
        val cum = DoubleArray(weights.size); var acc = 0.0
        for (i in weights.indices) { acc += weights[i]; cum[i] = acc }
        return List(n) { val x = rnd.nextDouble() * acc; var lo = 0; var hi = cum.size - 1
            while (lo < hi) { val m = (lo + hi) / 2; if (cum[m] < x) lo = m + 1 else hi = m }
            pool[lo][0] }
    }

    class Sample(val kind: String, val lang: Lang, val typed: String, val truth: String, val cands: List<app.teclado.engine.Candidate>)

    private fun collect(): List<Sample> {
        val out = ArrayList<Sample>()
        for (lang in Lang.values()) {
            val words = realWords(lang, 1200, 4)
            val kinds = linkedMapOf<String, (String) -> String?>(
                "1 vecina" to { w -> subst(w, 1) },
                "2 vecinas" to { w -> subst(w, 2) },
                "transposición" to { w -> transpose(w) },
                "letra de menos" to { w -> missing(w) },
                "letra de más" to { w -> extra(w) },
                "vecina+menos" to { w -> subst(w, 1)?.let { missing(it) } },
                "vecina+más" to { w -> subst(w, 1)?.let { extra(it) } },
            )
            for ((name, f) in kinds) for (w in words) {
                val t = f(w) ?: continue
                if (t == w || engine.isKnown(t)) continue
                out.add(Sample(name, lang, t, w, engine.candidates(t, lang)))
            }
        }
        return out
    }

    private fun eval(samples: List<Sample>, g: SpellEngine.Gate, byKind: Boolean = false): String {
        val kinds = if (byKind) samples.map { it.kind }.distinct() else listOf("TODO")
        val sb = StringBuilder()
        for (k in kinds) {
            var n = 0; var ok = 0; var wrong = 0
            for (s in samples) {
                if (byKind && s.kind != k) continue
                n++
                val c = engine.decide(s.typed, s.cands, g)
                if (c == s.truth) ok++ else if (c != null) wrong++
            }
            sb.append("%-15s acierta=%2d%% mal=%2d%% sin_tocar=%2d%%\n".format(k, 100 * ok / n, 100 * wrong / n, 100 * (n - ok - wrong) / n))
        }
        return sb.toString()
    }

    private fun rescored(s: Sample, k: SpellEngine.Costs): List<app.teclado.engine.Candidate> =
        s.cands.mapNotNull { c ->
            if (c.split) return@mapNotNull c
            val d = SpellEngine.distance(s.typed, c.word, k)
            var cost = d.cost + (if (c.crossLang) 2.0 else 0.0)
            var edits = d.edits
            if (c.cost <= 3.0 && c.edits == 1 && edits > 1) { cost = c.cost; edits = 1 } // aciertos "por sonido"
            if (edits == 0 || edits > 2) null else app.teclado.engine.Candidate(c.word, c.logFreq, cost, edits, c.crossLang, c.band)
        }

    private fun utility(samples: List<Sample>, k: SpellEngine.Costs, g: SpellEngine.Gate): Triple<Double, Double, Double> {
        var n = 0; var ok = 0; var wrong = 0
        for (s in samples) {
            n++
            val c = engine.decide(s.typed, rescored(s, k).sortedByDescending { it.score(g.freqWeight) }, g)
            if (c == s.truth) ok++ else if (c != null) wrong++
        }
        val a = 100.0 * ok / n; val m = 100.0 * wrong / n
        return Triple(a, m, a - 2.5 * m)
    }

    @Test fun sweep() {
        org.junit.Assume.assumeTrue(System.getenv("TECLADO_BENCH") != null) // manual: tarda minutos
        val samples = collect()
        val sb = StringBuilder("\nmuestras=${samples.size}\n")
        val base = SpellEngine.Costs(); val g0 = SpellEngine.Gate(marginSingle = 1.0, marginDouble = 0.5, doubleBand = 600, double4 = 5.8)
        val b0 = utility(samples, base, g0)
        sb.append("BASE   acierta=%.1f%% mal=%.1f%% utilidad=%.1f\n".format(b0.first, b0.second, b0.third))
        class R(val name: String, val u: Triple<Double, Double, Double>, val k: SpellEngine.Costs, val g: SpellEngine.Gate)
        val res = ArrayList<R>()
        for (nb in listOf(2.0, 2.5, 3.0)) for (ind in listOf(3.0, 3.5, 4.0)) for (ch in listOf(2.0, 2.5)) for (tr in listOf(1.5, 2.0, 2.5)) for (fw in listOf(0.4, 0.5, 0.65, 0.8)) {
            val k = SpellEngine.Costs(transpose = tr, neighbor = nb, indel = ind, cheapIndel = ch)
            val g = SpellEngine.Gate(marginSingle = 1.0, marginDouble = 0.5, doubleBand = 600, double4 = 5.8, freqWeight = fw)
            res.add(R("nb=$nb indel=$ind cheap=$ch transp=$tr fw=$fw", utility(samples, k, g), k, g))
        }
        val top = res.sortedByDescending { it.u.third }.take(6)
        for (r in top) sb.append("%-44s acierta=%.1f%% mal=%.1f%% utilidad=%.1f\n".format(r.name, r.u.first, r.u.second, r.u.third))
        sb.append("-- afinando márgenes sobre las 3 mejores --\n")
        for (r in top.take(3)) for (ms in listOf(0.6, 1.0, 1.4)) for (md in listOf(0.25, 0.5, 0.8)) {
            val g = SpellEngine.Gate(marginSingle = ms, marginDouble = md, doubleBand = 600, double4 = 5.8, freqWeight = r.g.freqWeight)
            val u = utility(samples, r.k, g)
            sb.append("%-44s mS=%.1f mD=%.2f acierta=%.1f%% mal=%.1f%% utilidad=%.1f\n".format(r.name, ms, md, u.first, u.second, u.third))
        }
        println(sb)
    }

    @Test fun bench() {
        org.junit.Assume.assumeTrue(System.getenv("TECLADO_BENCH") != null)
        val sb = StringBuilder("\n")
        var totN = 0; var totOk = 0; var totWrong = 0; var totGlued = 0
        for (lang in Lang.values()) {
            val words = realWords(lang, 1500, 4)
            val kinds = linkedMapOf<String, (String) -> String?>(
                "1 vecina" to { w -> subst(w, 1) },
                "2 vecinas" to { w -> subst(w, 2) },
                "transposición" to { w -> transpose(w) },
                "letra de menos" to { w -> missing(w) },
                "letra de más" to { w -> extra(w) },
                "vecina+menos" to { w -> subst(w, 1)?.let { missing(it) } },
                "vecina+más" to { w -> subst(w, 1)?.let { extra(it) } },
            )
            for ((name, f) in kinds) {
                var n = 0; var ok = 0; var none = 0; var wrong = 0; var glued = 0; var real = 0
                var notFound = 0; var rejected = 0
                val wrongEx = ArrayList<String>(); val noneEx = ArrayList<String>()
                for (w in words) {
                    val t = f(w) ?: continue
                    if (t == w) continue
                    if (engine.isKnown(t)) { real++; continue } // la errata es otra palabra real: no se puede corregir
                    n++
                    val c = engine.correction(t, lang)
                    when {
                        c == null -> {
                            none++
                            val cs = engine.candidates(t, lang)
                            val pos = cs.indexOfFirst { it.word == w }
                            if (pos < 0) notFound++ else rejected++
                            if (noneEx.size < 6) noneEx.add("$t→$w" + if (pos >= 0) "(pos$pos s=%.1f c=%.1f b=${cs[pos].band})".format(cs[pos].score, cs[pos].cost) else "(no hallada)")
                        }
                        c == w -> ok++
                        else -> { wrong++; if (wrongEx.size < 5) wrongEx.add("$t→$c(era $w)") }
                    }
                    if (c != null && c.contains(' ')) glued++
                }
                totN += n; totOk += ok; totWrong += wrong; totGlued += glued
                sb.append("%s %-16s n=%4d  acierta=%3d%%  sin corregir=%3d%%  corrige mal=%3d%%  pegadas=%d  [sin corregir: no hallada=%d descartada=%d]\n".format(lang.code, name, n, 100 * ok / maxOf(n, 1), 100 * none / maxOf(n, 1), 100 * wrong / maxOf(n, 1), glued, notFound, rejected))
                if (name.startsWith("2") && noneEx.isNotEmpty()) sb.append("      $noneEx\n")
            }
        }
        sb.append("TOTAL n=%d acierta=%d%% corrige_mal=%d%% partidas=%d\n".format(totN, 100 * totOk / totN, 100 * totWrong / totN, totGlued))
        println(sb)
    }
}
