package app.teclado

import app.teclado.engine.Lang
import app.teclado.engine.LanguageDetector
import app.teclado.engine.Lexicon
import app.teclado.engine.SpellEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EngineTest {
    companion object {
        private fun load(l: Lang) = Lexicon.parse(l, File("src/main/assets/dict/${l.code}.tsv").readText())
        val en by lazy { load(Lang.EN).also { it.buildD2Index() } }
        val es by lazy { load(Lang.ES).also { it.buildD2Index() } }
        val engine by lazy { SpellEngine(mapOf(Lang.EN to en, Lang.ES to es)) }

        /** Palabras reales que NO se pueden tocar nunca, en ningún idioma activo. */
        val MUST_KEEP = listOf(
            // casos reportados
            "retoca", "debug", "retocas", "retoqué",
            // conjugaciones y formas poco frecuentes
            "desmotivaría", "comeríamos", "habláramos", "retuiteó", "reenviaste", "apagaríais", "escanéalo",
            "dímelo", "cuéntamelo", "pásamelo", "arreglaste", "subrayó", "enfadarías", "pintarrajeó",
            // técnico / anglicismos habituales
            "commit", "merge", "deploy", "backend", "frontend", "debugging", "refactor", "login", "logout",
            "email", "wifi", "app", "apps", "online", "offline", "spam", "hashtag", "streaming", "podcast",
            "selfie", "smartphone", "tablet", "android", "iphone", "github", "kotlin", "javascript", "python",
            // coloquial
            "vale", "guay", "finde", "porfa", "jaja", "jajaja", "okey", "tío", "mola", "flipante", "currar",
            "curro", "pringado", "chulo", "molan", "quedada",
            // inglés poco común pero real
            // ("betted" no está: Hunspell en_US lo rechaza y se eliminó como errata de "better")
            "hose", "latte", "stoop", "bellow", "hover", "mould", "wince", "abort", "nay", "tow",
            "quaint", "whimsical", "serendipity", "ubiquitous", "ephemeral", "gonna", "wanna", "gotta",
            // pares con tilde que son las dos palabras válidas
            "esta", "está", "solo", "sólo", "aun", "aún", "papa", "papá", "mama", "mamá", "como", "cómo",
            "que", "qué", "el", "él", "tu", "tú", "mi", "mí", "si", "sí", "te", "té", "se", "sé",
            "realizo", "publico", "provoco", "determino", "practico", "critico", "termino", "revelo",
        )

        /** errata -> corrección esperada */
        val MUST_FIX = mapOf(
            "teh" to "the", "recieve" to "receive", "wich" to "which", "becuase" to "because",
            "dont" to "don't", "thier" to "their", "freind" to "friend", "beleive" to "believe",
            "seperate" to "separate", "occured" to "occurred", "untill" to "until", "realy" to "really",
            "goverment" to "government", "tommorow" to "tomorrow", "definately" to "definitely",
            "somthing" to "something", "wolrd" to "world", "gonig" to "going", "langauge" to "language",
            "tambien" to "también", "despues" to "después", "telefono" to "teléfono", "informacion" to "información",
            "espanol" to "español", "porque" to "porque", "garcias" to "gracias", "quierp" to "quiero",
            "necestio" to "necesito", "nesecito" to "necesito", "mañna" to "mañana", "manana" to "mañana",
            "beuno" to "bueno", "dificil" to "difícil", "ademas" to "además", "aqui" to "aquí",
            "hacia" to "hacia", "cuenya" to "cuenta", "rodos" to "todos", "todabia" to "todavía",
            "senor" to "señor", "decision" to "decisión", "ano" to "año",
        )
    }

    private fun langOf(w: String) = if (w in setOf("teh", "recieve", "wich", "becuase", "dont", "thier", "freind",
            "beleive", "seperate", "occured", "untill", "realy", "goverment", "tommorow", "definately",
            "somthing", "wolrd", "gonig", "langauge")) Lang.EN else Lang.ES

    @Test fun neverTouchesRealWords() {
        val wrong = ArrayList<String>()
        for (w in MUST_KEEP) for (l in Lang.values()) {
            val c = engine.correction(w, l)
            if (c != null) wrong.add("$w→$c ($l)")
        }
        assertTrue("Correcciones indebidas: $wrong", wrong.isEmpty())
    }

    @Test fun fixesTypos() {
        val untouched = ArrayList<String>()
        val wrongTarget = ArrayList<String>()
        for ((typo, fix) in MUST_FIX) {
            val got = engine.correction(typo, langOf(typo)) ?: typo
            if (got == fix) continue
            if (got == typo) untouched.add(typo) else wrongTarget.add("$typo→$got (esperado $fix)")
        }
        println("Erratas que se dejan sin corregir: $untouched")
        // precisión antes que cobertura: puede dejar alguna sin corregir, pero nunca cambiarla por otra cosa
        assertTrue("Corregidas a algo incorrecto: $wrongTarget", wrongTarget.isEmpty())
        assertTrue("Demasiadas erratas sin corregir: $untouched", untouched.size <= 3)
    }

    @Test fun debugCandidates() {
        for (w in listOf("rodos", "somthing", "mañna")) {
            println(w + ": " + engine.candidates(w, Lang.ES).take(4).joinToString { "${it.word} s=%.2f c=%.1f e=${it.edits} x=${it.crossLang}".format(it.score, it.cost) })
        }
    }

    @Test fun suggestionsStillOfferFixForUncertainCases() {
        val s = engine.suggest("xomo", Lang.ES, 3).map { it.word }
        assertTrue(s.toString(), "como" in s)
    }

    @Test fun completions() {
        val c = es.completions("herm", 3)
        assertTrue(c.toString(), c.isNotEmpty() && c.all { it.startsWith("herm") })
        assertTrue(en.completions("hel", 3).contains("help"))
    }

    @Test fun caseMatching() {
        assertEquals("The", SpellEngine.matchCase("Teh", "the"))
        assertEquals("THE", SpellEngine.matchCase("TEH", "the"))
        assertEquals("the", SpellEngine.matchCase("teh", "the"))
    }

    private fun detect(text: String, start: Lang): Lang {
        val d = LanguageDetector(en, es, start)
        for (w in text.split(" ")) d.observe(w)
        return d.current
    }

    @Test fun detectsLanguage() {
        assertEquals(Lang.ES, detect("hola qué tal estás hoy voy a ir al cine con mis amigos", Lang.EN))
        assertEquals(Lang.EN, detect("hello how are you today I am going to the movies with my friends", Lang.ES))
        assertEquals(Lang.ES, detect("necesito que me envíes el documento mañana por favor", Lang.EN))
    }

    @Test fun switchesBackAndForth() {
        val d = LanguageDetector(en, es, Lang.ES)
        "hello how are you doing today my friend".split(" ").forEach(d::observe)
        assertEquals(Lang.EN, d.current)
        "ahora vuelvo a escribir en español para ti".split(" ").forEach(d::observe)
        assertEquals(Lang.ES, d.current)
    }

    @Test fun seedFromContext() {
        val d = LanguageDetector(en, es, Lang.EN)
        d.seed("Buenos días, quería preguntarte una cosa sobre el pedido", Lang.EN)
        assertEquals(Lang.ES, d.current)
    }
}
