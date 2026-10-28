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
        svc.onClipboardPick(2) // la más antigua
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
        svc.onClipboardDelete(0) // no falla con la lista vacía
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
}
