package app.teclado

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TabletPortraitTest {
    private fun splitFor(): Float {
        Prefs(RuntimeEnvironment.getApplication()).splitMode = "tablet"
        val svc = Robolectric.buildService(TestService::class.java).create().get()
        svc.onCreateInputView()
        svc.onStartInputView(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }, false)
        return svc.splitGapState
    }

    // OPPO Pad Mini en vertical: 1680 px de ancho; a 480 dpi son 560 dp
    @Test @Config(qualifiers = "w560dp-h840dp-port-xxhdpi")
    fun splitsOnTabletPortrait() = assertTrue(splitFor() > 0f)

    @Test @Config(qualifiers = "w840dp-h560dp-land-xxhdpi")
    fun splitsOnTabletLandscape() = assertTrue(splitFor() > 0f)

    @Test @Config(qualifiers = "w360dp-h800dp-port-xxhdpi")
    fun doesNotSplitOnPhone() = assertEquals(0f, splitFor(), 0f)

    @Test @Config(qualifiers = "w800dp-h360dp-land-xxhdpi")
    fun doesNotSplitOnPhoneLandscape() = assertEquals(0f, splitFor(), 0f)
}
