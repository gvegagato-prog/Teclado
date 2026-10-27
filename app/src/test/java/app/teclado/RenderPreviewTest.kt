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

    private fun make(dark: Boolean, widthPx: Int = 1600): KeyboardView {
        val ctx = RuntimeEnvironment.getApplication()
        val v = KeyboardView(ctx)
        v.theme = KbTheme(dark)
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
        val light = make(false)
        light.setSuggestions(listOf(SuggestionItem("\"helo\""), SuggestionItem("hello", true), SuggestionItem("help")))
        save(light, "light_letters")

        val dark = make(true)
        dark.shift = Shift.ONCE
        dark.setSuggestions(listOf(SuggestionItem("the"), SuggestionItem("their", true), SuggestionItem("them")))
        save(dark, "dark_shift")

        val sym = make(false)
        sym.layer = Layer.SYM1
        save(sym, "light_symbols")

        val press = make(false)
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

        val hint = make(true)
        val (hx, hy) = center2(hint, "h")
        touch(hint, MotionEvent.ACTION_DOWN, hx, hy)
        touch(hint, MotionEvent.ACTION_MOVE, hx, hy + 80f)
        save(hint, "dark_swipe_down_hint")
        touch(hint, MotionEvent.ACTION_UP, hx, hy + 80f)

        val pop = make(false)
        val (ax, ay) = center2(pop, "a")
        touch(pop, MotionEvent.ACTION_DOWN, ax, ay)
        ShadowLooper.idleMainLooper(500, java.util.concurrent.TimeUnit.MILLISECONDS)
        save(pop, "light_longpress_a")
        touch(pop, MotionEvent.ACTION_UP, ax, ay)

        val phone = make(false, 1080)
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
