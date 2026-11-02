package app.pera

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.io.FileOutputStream

/** Capturas de la pantalla de Pera (para revisarla sin un dispositivo), en build/previews. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class RenderPreviewTest {
    private val out = File(System.getProperty("preview.dir") ?: "build/previews").also { it.mkdirs() }

    private fun save(v: View, name: String, width: Int) {
        v.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        FileOutputStream(File(out, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun screen(name: String, tablet: Boolean) {
        val ctx = RuntimeEnvironment.getApplication()
        if (tablet) Prefs.setSyncEnabled(ctx, true) else Prefs.setHostEnabled(ctx, true)
        val a: Activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        ShadowLooper.idleMainLooper()
        val root = a.window.decorView.findViewById<View>(android.R.id.content)
        save(root, name, a.resources.displayMetrics.widthPixels)
        // Ficha de la segunda tarjeta (Copiar y pegar), tal como se ve al tocarla.
        val grid = (root as android.view.ViewGroup).getChildAt(0).let { (it as android.view.ViewGroup).getChildAt(0) as android.view.ViewGroup }
            .let { it.getChildAt(3) as android.view.ViewGroup }
        grid.getChildAt(1).performClick()
        ShadowLooper.idleMainLooper()
        ShadowDialog.getLatestDialog()?.window?.decorView?.let { save(it, "${name}_ficha", (a.resources.displayMetrics.widthPixels * 0.92).toInt()) }
    }

    @Config(qualifiers = "w411dp-h891dp-port-notnight-xxhdpi")
    @Test fun phoneLight() = screen("movil_claro", tablet = false)

    @Config(qualifiers = "w411dp-h891dp-port-night-xxhdpi")
    @Test fun phoneDark() = screen("movil_oscuro", tablet = false)

    @Config(qualifiers = "w800dp-h1280dp-port-notnight-xhdpi")
    @Test fun tabletLight() = screen("tablet_claro", tablet = true)

    @Test fun icon() {
        val ctx = RuntimeEnvironment.getApplication()
        val d = ctx.getDrawable(R.drawable.ic_launcher)!!
        val bmp = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, 512, 512)
        d.draw(Canvas(bmp))
        FileOutputStream(File(out, "icono.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
