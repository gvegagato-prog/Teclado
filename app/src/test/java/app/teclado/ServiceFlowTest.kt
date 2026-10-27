package app.teclado

import android.text.InputType
import android.text.TextUtils
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import app.teclado.engine.Lang
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/** Campo de texto en memoria que se comporta como el editor de una app. */
class FakeEditor : InputConnectionWrapper(null, true) {
    val text = StringBuilder()
    var cursor = 0
    var lastAction = -1

    override fun beginBatchEdit() = true
    override fun endBatchEdit() = true
    override fun commitText(t: CharSequence, newCursorPosition: Int): Boolean {
        text.insert(cursor, t); cursor += t.length; return true
    }
    override fun deleteSurroundingText(before: Int, after: Int): Boolean {
        val b = minOf(before, cursor)
        text.delete(cursor - b, cursor); cursor -= b
        return true
    }
    override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence = text.substring(maxOf(0, cursor - n), cursor)
    override fun getCursorCapsMode(reqModes: Int): Int = TextUtils.getCapsMode(text, cursor, reqModes)
    override fun performEditorAction(a: Int): Boolean { lastAction = a; return true }
    override fun sendKeyEvent(e: KeyEvent): Boolean {
        if (e.action != KeyEvent.ACTION_DOWN) return true
        when (e.keyCode) {
            KeyEvent.KEYCODE_DEL -> deleteSurroundingText(1, 0)
            KeyEvent.KEYCODE_ENTER -> commitText("\n", 1)
            KeyEvent.KEYCODE_DPAD_LEFT -> cursor = maxOf(0, cursor - 1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> cursor = minOf(text.length, cursor + 1)
        }
        return true
    }
}

class TestService : TecladoService() {
    val editor = FakeEditor()
    override fun getCurrentInputConnection(): InputConnection = editor
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ServiceFlowTest {
    private lateinit var svc: TestService
    private val ed get() = svc.editor

    @Before fun setUp() {
        Prefs(RuntimeEnvironment.getApplication()).apply { shortcuts = Prefs.DEFAULT_SHORTCUTS }
        svc = Robolectric.buildService(TestService::class.java).create().get()
        svc.onCreateInputView()
        val t0 = System.currentTimeMillis()
        while (!svc.engineReady && System.currentTimeMillis() - t0 < 15000) {
            Thread.sleep(20)
            ShadowLooper.idleMainLooper()
        }
        check(svc.engineReady) { "diccionarios sin cargar" }
        start()
    }

    private fun start(inputType: Int = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES) {
        ed.text.setLength(0); ed.cursor = 0
        val info = EditorInfo().apply { this.inputType = inputType }
        svc.onStartInputView(info, false)
    }

    private fun type(s: String) {
        for (c in s) {
            val before = ed.cursor
            when (c) {
                ' ' -> svc.onSpecial(KeyType.SPACE)
                '\n' -> svc.onSpecial(KeyType.ENTER)
                else -> svc.onChar(if (svc.shiftState != Shift.OFF && c.isLetter()) c.uppercase() else c.toString())
            }
            svc.onUpdateSelection(before, before, ed.cursor, ed.cursor, -1, -1)
        }
        ShadowLooper.idleMainLooper()
    }

    private fun backspace() {
        val before = ed.cursor
        svc.onSpecial(KeyType.DELETE)
        svc.onUpdateSelection(before, before, ed.cursor, ed.cursor, -1, -1)
        ShadowLooper.idleMainLooper()
    }

    @Test fun autocorrectsEnglishOnSpace() {
        type("i think teh ")
        assertEquals("I think the ", ed.text.toString())
    }

    @Test fun backspaceRevertsAutocorrect() {
        type("teh ")
        backspace()
        assertEquals("Teh", ed.text.toString()) // mayúscula inicial del campo
        type(" ")
        assertEquals("Teh ", ed.text.toString()) // no se vuelve a corregir
    }

    @Test fun textShortcutExpandsOnSpace() {
        type("mi correo es @@@ ")
        assertEquals("Mi correo es gonvegato@gmail.com ", ed.text.toString())
    }

    @Test fun doubleSpaceInsertsPeriod() {
        type("hola  ")
        assertEquals("Hola. ", ed.text.toString())
    }

    @Test fun spanishAccentsAreRestored() {
        type("voy a ir al cine tambien ")
        assertEquals("Voy a ir al cine también ", ed.text.toString())
    }

    @Test fun detectsLanguageWhileTyping() {
        type("necesito que me envíes el documento mañana por favor ")
        assertEquals(Lang.ES, svc.language)
        type("and then I told him that we should leave because it was late ")
        assertEquals(Lang.EN, svc.language)
    }

    @Test fun englishApostropheAfterDetectingEnglish() {
        type("i really think that we should go there and i dont know why ")
        assertEquals("I really think that we should go there and I don't know why ", ed.text.toString())
    }

    @Test fun mixedLanguageWordsAreNotTouched() {
        type("hoy tengo una meeting con el equipo ")
        assertEquals("Hoy tengo una meeting con el equipo ", ed.text.toString())
    }

    @Test fun punctuationTriggersCorrection() {
        type("this is teh.")
        assertEquals("This is the.", ed.text.toString())
    }

    @Test fun urlsAndEmailsAreNotCorrected() {
        type("escribe a foo@bar.cmo ahora ")
        assertEquals("Escribe a foo@bar.cmo ahora ", ed.text.toString())
    }

    @Test fun passwordFieldsAreLeftAlone() {
        start(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        type("teh ")
        assertEquals("teh ", ed.text.toString())
    }

    @Test fun capitalizesAfterSentenceEnd() {
        type("hola. como estas")
        assertEquals("Hola. Como estas", ed.text.toString())
    }

    @Test fun deleteWorksAndTokenTracksCursor() {
        type("teh")
        backspace(); backspace(); backspace()
        type("teh ")
        assertEquals("The ", ed.text.toString())
    }

    @Test fun typedLowercaseKeepsCase() {
        start(InputType.TYPE_CLASS_TEXT)
        type("teh ")
        assertEquals("the ", ed.text.toString())
    }
}
