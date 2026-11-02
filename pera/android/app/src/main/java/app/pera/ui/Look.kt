package app.pera.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Estilo de Pera: sobrio y amable. Fondo blanco roto (modo claro) o gris oscuro (modo oscuro), tarjetas
 * redondeadas con un leve tinte verde si la función está activa y rojizo si no.
 */
class Look(private val context: Context) {
    val dark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    val background = if (dark) 0xFF262624.toInt() else 0xFFF3F0E8.toInt()
    val text = if (dark) 0xFFEFEBE3.toInt() else 0xFF2D2C2A.toInt()
    val soft = if (dark) 0xFFA8A49C.toInt() else 0xFF6E6A63.toInt()
    val card = if (dark) 0xFF31302D.toInt() else 0xFFFBF9F4.toInt()
    val line = if (dark) 0xFF45433F.toInt() else 0xFFE2DDD2.toInt()

    val onFill = if (dark) 0xFF2E3A2D.toInt() else 0xFFE5EEDF.toInt()
    val onStroke = if (dark) 0xFF61805A.toInt() else 0xFFA6C29C.toInt()
    val onInk = if (dark) 0xFFA9D39E.toInt() else 0xFF3F6B37.toInt()

    val offFill = if (dark) 0xFF3B2D2B.toInt() else 0xFFF4E3DF.toInt()
    val offStroke = if (dark) 0xFF8C5C55.toInt() else 0xFFD7A9A1.toInt()
    val offInk = if (dark) 0xFFE5A79D.toInt() else 0xFF9A4A40.toInt()

    fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    fun rounded(fill: Int, stroke: Int = Color.TRANSPARENT, radius: Int = 22) = GradientDrawable().apply {
        cornerRadius = dp(radius).toFloat()
        setColor(fill)
        if (stroke != Color.TRANSPARENT) setStroke(dp(1), stroke)
    }

    fun tint(on: Boolean) = rounded(if (on) onFill else offFill, if (on) onStroke else offStroke)

    fun label(s: CharSequence, size: Float = 15f, color: Int = text, bold: Boolean = false) = TextView(context).apply {
        this.text = s
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setLineSpacing(0f, 1.15f)
    }

    /** Botón en forma de pastilla. [strong]: relleno (acción principal). */
    fun pill(s: String, strong: Boolean = false, onClick: () -> Unit): TextView {
        val ink = if (strong) background else text
        val fill = rounded(if (strong) text else card, if (strong) Color.TRANSPARENT else line, 50)
        return pillView(s, ink, fill, onClick)
    }

    private fun pillView(s: String, ink: Int, fill: GradientDrawable, onClick: () -> Unit) = TextView(context).apply {
        text = s
        textSize = 14f
        gravity = Gravity.CENTER
        setTextColor(ink)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        background = fill
        setPadding(dp(16), dp(9), dp(16), dp(9))
        isClickable = true
        setOnClickListener { onClick() }
    }

    /** Interruptor «Sí/No» en pastilla: verde si está activo, rojizo si no. */
    fun toggle(title: String, on: Boolean, onChange: (Boolean) -> Unit): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(8), 0, dp(8))
        addView(label(title, 16f, bold = true), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(TextView(context).apply {
            text = if (on) "Activado" else "Desactivado"
            textSize = 13f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(if (on) onInk else offInk)
            background = tint(on).apply { cornerRadius = dp(50).toFloat() }
            setPadding(dp(14), dp(7), dp(14), dp(7))
            setOnClickListener { onChange(!on) }
        })
    }

    /** Requisito de una función: punto verde/rojo, texto y, si falta, la acción para resolverlo. */
    fun requirement(ok: Boolean, s: String, action: Pair<String, () -> Unit>? = null): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(6), 0, dp(6))
        addView(label("●", 14f, if (ok) onInk else offInk).apply { setPadding(0, 0, dp(10), 0) })
        addView(label(s, 14f, soft), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (!ok && action != null) {
            addView(pill(action.first, onClick = action.second).apply { textSize = 13f },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { marginStart = dp(8) })
        }
    }

    fun divider(): View = View(context).apply {
        setBackgroundColor(line)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
            topMargin = dp(10); bottomMargin = dp(10)
        }
    }
}
