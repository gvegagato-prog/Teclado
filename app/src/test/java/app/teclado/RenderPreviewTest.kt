package app.teclado

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import org.robolectric.RuntimeEnvironment
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w800dp-h1200dp-xhdpi")
class RenderPreviewTest {
    private val out = File(System.getProperty("preview.dir") ?: "build/previews").also { it.mkdirs() }

    private fun make(theme: KbTheme, widthPx: Int = 1600): KeyboardView {
        val ctx = RuntimeEnvironment.getApplication()
        val v = KeyboardView(ctx)
        v.theme = theme
        v.spaceLabel = "Español"
        v.hapticsEnabled = false
        v.measure(View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
        return v
    }

    private fun save(v: View, name: String) {
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        FileOutputStream(File(out, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun touch(v: View, action: Int, x: Float, y: Float) {
        val t = SystemClock.uptimeMillis()
        v.dispatchTouchEvent(MotionEvent.obtain(t, t, action, x, y, 0))
    }

    @Test fun render() {
        val light = make(KbTheme.LIGHT)
        light.setSuggestions(listOf(SuggestionItem("\"helo\""), SuggestionItem("hello", true), SuggestionItem("help")))
        save(light, "light_letters")

        val dark = make(KbTheme.DARK)
        dark.shift = Shift.ONCE
        dark.setSuggestions(listOf(SuggestionItem("the"), SuggestionItem("their", true), SuggestionItem("them")))
        save(dark, "dark_shift")

        val sym = make(KbTheme.LIGHT)
        sym.layer = Layer.SYM1
        save(sym, "light_symbols")

        val press = make(KbTheme.LIGHT)
        // pulsar la 'g' (fila 2) y la 'q' (fila 1)
        val gKey = press.javaClass.getDeclaredField("frames").also { it.isAccessible = true }.get(press) as List<*>
        fun center(label: String): Pair<Float, Float> {
            val f = gKey.first { k ->
                val def = k!!.javaClass.getDeclaredField("def").also { it.isAccessible = true }.get(k) as KeyDef
                def.label == label
            }!!
            val r = f.javaClass.getDeclaredField("rect").also { it.isAccessible = true }.get(f) as android.graphics.RectF
            return r.centerX() to r.centerY()
        }
        val (gx, gy) = center("g")
        touch(press, MotionEvent.ACTION_DOWN, gx, gy)
        save(press, "pressed_g")
        touch(press, MotionEvent.ACTION_UP, gx, gy)

        val hint = make(KbTheme.DARK)
        val (hx, hy) = center2(hint, "h")
        touch(hint, MotionEvent.ACTION_DOWN, hx, hy)
        touch(hint, MotionEvent.ACTION_MOVE, hx, hy + 80f)
        save(hint, "dark_swipe_down_hint")
        touch(hint, MotionEvent.ACTION_UP, hx, hy + 80f)

        val pop = make(KbTheme.LIGHT)
        val (ax, ay) = center2(pop, "a")
        touch(pop, MotionEvent.ACTION_DOWN, ax, ay)
        ShadowLooper.idleMainLooper(500, java.util.concurrent.TimeUnit.MILLISECONDS)
        save(pop, "light_longpress_a")
        touch(pop, MotionEvent.ACTION_UP, ax, ay)

        val split = make(KbTheme.LIGHT, 2000)
        split.splitGap = 0.28f
        split.setSuggestions(listOf(SuggestionItem("\"helo\""), SuggestionItem("hello", true), SuggestionItem("help")))
        save(split, "split_letters")
        val splitSym = make(KbTheme.DARK, 2000)
        splitSym.splitGap = 0.28f
        splitSym.layer = Layer.SYM1
        save(splitSym, "split_symbols_dark")

        for ((name, kind, signed, dec) in listOf(
            Quad("numpad_number", PadKind.NUMBER, true, true), Quad("numpad_phone", PadKind.PHONE, false, false),
            Quad("numpad_int", PadKind.NUMBER, false, false))) {
            val v = make(KbTheme.LIGHT, 1600)
            v.setNumpad(Layouts.numpad(kind, signed, dec))
            v.layer = Layer.NUMPAD
            v.enterLabel = "Listo"
            save(v, name)
        }

        for (name in listOf("sand", "rose", "midnight", "black")) {
            val v = make(KbTheme.byName(name, false))
            v.setSuggestions(listOf(SuggestionItem("\"helo\""), SuggestionItem("hello", true), SuggestionItem("help")))
            save(v, "theme_$name")
        }

        val phone = make(KbTheme.LIGHT, 1080)
        save(phone, "light_narrow")
    }

    private fun center2(v: KeyboardView, label: String): Pair<Float, Float> {
        val frames = v.javaClass.getDeclaredField("frames").also { it.isAccessible = true }.get(v) as List<*>
        val f = frames.first { k ->
            val def = k!!.javaClass.getDeclaredField("def").also { it.isAccessible = true }.get(k) as KeyDef
            def.label == label
        }!!
        val r = f.javaClass.getDeclaredField("rect").also { it.isAccessible = true }.get(f) as android.graphics.RectF
        return r.centerX() to r.centerY()
    }
}

data class Quad(val name: String, val kind: PadKind, val signed: Boolean, val dec: Boolean)
