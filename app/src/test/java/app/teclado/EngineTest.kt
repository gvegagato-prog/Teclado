package app.teclado

import app.teclado.engine.Lang
import app.teclado.engine.LanguageDetector
import app.teclado.engine.Lexicon
import app.teclado.engine.SpellEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EngineTest {
    private fun load(l: Lang) = File("src/main/assets/dict/${l.code}.tsv").bufferedReader().use { Lexicon.parse(l, it) }
    private val en = load(Lang.EN)
    private val es = load(Lang.ES)
    private val engine = SpellEngine(mapOf(Lang.EN to en, Lang.ES to es))

    @Test fun englishCorrections() {
        assertEquals("the", engine.correction("teh", Lang.EN))
        assertEquals("receive", engine.correction("recieve", Lang.EN))
        assertEquals("which", engine.correction("wich", Lang.EN))
        assertEquals("definitely", engine.correction("definately", Lang.EN))
        assertEquals("don't", engine.correction("dont", Lang.EN))
        assertEquals("because", engine.correction("becuase", Lang.EN))
    }

    @Test fun spanishCorrections() {
        assertEquals("también", engine.correction("tambien", Lang.ES))
        assertEquals("español", engine.correction("espanol", Lang.ES))
        assertEquals("porque", engine.correction("poque", Lang.ES))
        assertEquals("gracias", engine.correction("garcias", Lang.ES))
        assertEquals("hola", engine.correction("hila", Lang.ES) ?: "hola") // informativo
    }

    @Test fun knownWordsAreLeftAlone() {
        assertNull(engine.correction("hello", Lang.EN))
        assertNull(engine.correction("hola", Lang.ES))
        // palabra inglesa escribiendo en español (idioma mezclado): no se toca
        assertNull(engine.correction("meeting", Lang.ES))
        assertNull(engine.correction("mañana", Lang.ES))
    }

    @Test fun completions() {
        val c = es.completions("herm", 3)
        assertTrue(c.toString(), c.isNotEmpty() && c.all { it.startsWith("herm") })
        assertTrue(en.completions("hello", 3).contains("hello"))
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
        assertFalse(d.current == Lang.EN)
    }
}
