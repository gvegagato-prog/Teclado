package app.teclado

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ClipboardTest {
    private val ctx get() = RuntimeEnvironment.getApplication()
    private lateinit var svc: TestService
    private val cm get() = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    @Before fun setUp() {
        File(ctx.filesDir, "clipboard.json").delete()
        Prefs(ctx).clipboardHistory = true
        svc = Robolectric.buildService(TestService::class.java).create().get()
        svc.onCreateInputView()
        svc.onStartInputView(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }, false)
    }

    private fun copy(text: String, sensitive: Boolean = false) {
        val clip = ClipData.newPlainText("x", text)
        if (sensitive) clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        cm.setPrimaryClip(clip)
        ShadowLooper.idleMainLooper()
    }

    @Test fun storeKeepsLast50NewestFirstWithoutDuplicates() {
        val f = File(ctx.cacheDir, "c1.json").also { it.delete() }
        val s = ClipStore(f)
        for (i in 1..60) s.add("copia $i")
        assertEquals(50, s.items.size)
        assertEquals("copia 60", s.items.first())
        assertEquals("copia 11", s.items.last())
        s.add("copia 30") // ya existía: sube arriba, no se duplica
        assertEquals("copia 30", s.items.first())
        assertEquals(50, s.items.size)
        assertEquals(1, s.items.count { it == "copia 30" })
        s.flush()
        val reloaded = ClipStore(f).also { it.load() }
        assertEquals(s.items, reloaded.items) // persiste sin límite de tiempo
    }

    @Test fun storeIgnoresBlankAndHugeAndDeletes() {
        val s = ClipStore(null)
        assertTrue(!s.add("   "))
        assertTrue(!s.add("x".repeat(ClipStore.MAX_CHARS + 1)))
        s.add("a"); s.add("b")
        s.remove(0)
        assertEquals(listOf("a"), s.items)
        s.clear()
        assertTrue(s.items.isEmpty())
    }

    @Test fun capturesCopiesAndPastesLatest() {
        copy("primero")
        copy("segundo\nlínea dos")
        svc.onPasteLatest()
        assertEquals("segundo\nlínea dos", svc.editor.text.toString())
    }

    @Test fun pickFromHistoryPastesThatEntry() {
        copy("uno"); copy("dos"); copy("tres")
        svc.onClipboardAction(ClipAction.PICK, false, 2) // la más antigua
        assertEquals("uno", svc.editor.text.toString())
    }

    @Test fun sensitiveCopiesAreNotSaved() {
        copy("hola")
        copy("contraseña-secreta", sensitive = true)
        val saved = ClipStore(File(ctx.filesDir, "clipboard.json")).also { it.flush(); it.load() }
        assertTrue(saved.items.toString(), "contraseña-secreta" !in saved.items)
    }

    @Test fun disabledHistoryDoesNotRecord() {
        Prefs(ctx).clipboardHistory = false
        copy("no guardar")
        svc.onClipboardOpened()
        svc.onClipboardAction(ClipAction.DELETE, false, 0) // no falla con la lista vacía
        val s = ClipStore(File(ctx.filesDir, "clipboard.json")).also { it.flush(); it.load() }
        assertTrue(s.items.isEmpty())
    }

    @Test fun clearingFromSettingsEmptiesTheKeyboardHistory() {
        copy("algo")
        assertEquals(listOf("algo"), svc.clipHistory)
        val p = Prefs(ctx)
        p.clipEpoch = p.clipEpoch + 1
        svc.onStartInputView(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }, false)
        assertTrue(svc.clipHistory.isEmpty())
    }

    private fun typeTitle(t: String) {
        for (c in t) if (c == ' ') svc.onSpecial(KeyType.SPACE) else svc.onChar(if (svc.shiftState != Shift.OFF && c.isLetter()) c.uppercase() else c.toString())
    }

    @Test fun pinWithTitleTypedOnTheKeyboard() {
        copy("dsrsfsxd")
        svc.onClipboardAction(ClipAction.PIN, false, 0)
        typeTitle("contraseña wifi casa")
        assertEquals("", svc.editor.text.toString()) // lo escrito NO llega a la app
        svc.onTitleOk()
        assertEquals(1, svc.clipPinned.size)
        assertEquals("dsrsfsxd", svc.clipPinned[0].text)
        assertEquals("Contraseña wifi casa", svc.clipPinned[0].title) // primera letra en mayúscula
        assertTrue(svc.clipHistory.isEmpty()) // pasó de recientes a fijadas
        svc.onClipboardAction(ClipAction.PICK, true, 0)
        assertEquals("dsrsfsxd", svc.editor.text.toString())
    }

    @Test fun editingTitleSupportsBackspaceCancelAndEnter() {
        copy("secreto")
        svc.onClipboardAction(ClipAction.PIN, false, 0)
        typeTitle("abcx")
        svc.onSpecial(KeyType.DELETE)
        svc.onSpecial(KeyType.ENTER) // intro confirma
        assertEquals("Abc", svc.clipPinned[0].title)
        svc.onClipboardAction(ClipAction.EDIT_TITLE, true, 0)
        typeTitle("zzz")
        svc.onTitleCancel()
        assertEquals("Abc", svc.clipPinned[0].title) // cancelar no cambia nada
    }

    @Test fun pinnedSurviveCopiesAndClearAndNeverDuplicate() {
        copy("fijada")
        svc.onClipboardAction(ClipAction.PIN, false, 0)
        svc.onTitleOk()
        copy("fijada") // copiar de nuevo algo ya fijado no lo mete en el historial
        for (i in 1..60) copy("c$i")
        assertEquals(50, svc.clipHistory.size)
        assertTrue("fijada" !in svc.clipHistory)
        svc.onClipboardClear() // "Borrar todo" no toca las fijadas
        assertTrue(svc.clipHistory.isEmpty())
        assertEquals(1, svc.clipPinned.size)
    }

    @Test fun unpinReturnsToHistory() {
        copy("volver")
        svc.onClipboardAction(ClipAction.PIN, false, 0)
        svc.onTitleOk()
        svc.onClipboardAction(ClipAction.UNPIN, true, 0)
        assertEquals(listOf("volver"), svc.clipHistory)
        assertTrue(svc.clipPinned.isEmpty())
    }

    @Test fun pinnedPersistAndOldFormatStillLoads() {
        val f = File(ctx.cacheDir, "c2.json").also { it.delete() }
        val st = ClipStore(f)
        st.add("a"); st.add("b")
        st.pin(0)
        st.setTitle(0, "  título  ")
        st.flush()
        val again = ClipStore(f).also { it.load() }
        assertEquals("título", again.pinned[0].title)
        assertEquals(listOf("a"), again.items)
        f.writeText("[\"viejo1\",\"viejo2\"]") // formato de la versión 1.3
        val old = ClipStore(f).also { it.load() }
        assertEquals(listOf("viejo1", "viejo2"), old.items)
    }
}
