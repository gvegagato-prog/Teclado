package app.teclado

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.SparseArray
import android.view.GestureDetector
import android.widget.OverScroller
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class Shift { OFF, ONCE, LOCK }

enum class ClipAction { PICK, DELETE, PIN, UNPIN, EDIT_TITLE }

class ClipEntry(val text: String, val title: String)

class SuggestionItem(val label: String, val emphasized: Boolean = false)

interface KeyboardListener {
    fun onChar(text: String)
    fun onSpecial(type: KeyType)
    fun onDeleteRepeat()
    fun onCursorMove(delta: Int)
    fun onSuggestion(slot: Int)
    fun onPasteLatest()
    fun onClipboardOpened()
    fun onClipboardAction(action: ClipAction, pinned: Boolean, index: Int)
    fun onClipboardClear()
    fun onTitleOk()
    fun onTitleCancel()
}

/**
 * Todo el teclado (tira de sugerencias + teclas + globos) en una sola vista dibujada a mano:
 * es lo más rápido y deja que el globo de las teclas de la primera fila suba sobre las sugerencias.
 */
class KeyboardView(context: Context) : View(context) {

    var listener: KeyboardListener? = null
    var theme: KbTheme = KbTheme.LIGHT
        set(v) { field = v; invalidate() }

    var shift: Shift = Shift.OFF
        set(v) { if (field != v) { field = v; invalidate() } }
    var layer: Layer = Layer.LETTERS
        set(v) { if (field != v) { field = v; rebuild(); requestLayout(); invalidate() } }

    /** Texto de la tecla intro si es una acción (ir, buscar, enviar…); null = icono de retorno. */
    var enterLabel: String? = null
        set(v) { field = v; invalidate() }
    var spaceLabel: String = ""
        set(v) { field = v; invalidate() }
    var spaceLabelAccent: Boolean = false
        set(v) { field = v; invalidate() }

    /** Margen inferior elegido por el usuario en dp; 0 = automático. */
    var bottomMarginDp: Int = 0
        set(v) { field = v; requestLayout() }

    var heightPercent: Int = 100
        set(v) { field = v; requestLayout() }
    var swipeDownEnabled: Boolean = true
    var hapticsEnabled: Boolean = true
    var soundEnabled: Boolean = false

    // ---- portapapeles ----
    private var pinnedClips: List<ClipEntry> = emptyList()
    private var recentClips: List<String> = emptyList()

    fun setClips(pinned: List<ClipEntry>, recent: List<String>) {
        pinnedClips = pinned
        recentClips = recent
        wrapCache.clear()
        clipScroll = clipScroll.coerceIn(0f, maxScroll())
        invalidate()
    }

    /** Texto del título que se está escribiendo con el teclado (null = no se edita ningún título). */
    var titleEdit: String? = null
        set(v) { field = v; invalidate() }
    var clipOpen = false
        private set
    private var clipScroll = 0f
    private var clearArmed = false
    private val wrapCache = HashMap<String, List<String>>()
    private val scroller = OverScroller(context)
    private var panelTouch = false

    fun toggleClipboard() {
        clipOpen = !clipOpen
        clearArmed = false
        clipScroll = 0f
        if (clipOpen) listener?.onClipboardOpened()
        invalidate()
    }

    fun openClipboard() {
        if (!clipOpen) toggleClipboard()
    }

    fun closeClipboard() {
        if (clipOpen || clearArmed) { clipOpen = false; clearArmed = false; invalidate() }
    }

    private val slots = arrayOfNulls<SuggestionItem>(3)

    fun setSuggestions(items: List<SuggestionItem>) {
        for (i in 0 until 3) slots[i] = items.getOrNull(i)
        invalidate()
    }

    // ---------- geometría ----------
    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private class KeyFrame(val def: KeyDef, val row: Int, val rect: RectF)

    private var rows: List<List<KeyDef>> = Layouts.rows(Layer.LETTERS)
    private val frames = ArrayList<KeyFrame>()
    private var keyH = 0f
    private var gapX = 0f
    private var gapY = 0f
    private var sidePad = 0f
    private var topPad = 0f
    private var stripH = 0f
    private var rowsTop = 0f
    private val btnSize get() = stripH - dp(8f)
    private val stripLeft get() = dp(5f) + 2 * btnSize + dp(6f) + dp(10f)
    private fun btnRect(i: Int, out: RectF) {
        val x = dp(5f) + i * (btnSize + dp(6f))
        out.set(x, topPad + (stripH - btnSize) / 2f, x + btnSize, topPad + (stripH + btnSize) / 2f)
    }
    private fun clearRect(out: RectF) = out.set(width - dp(150f), topPad, width - dp(4f), topPad + stripH)

    private var numpadRows: List<List<KeyDef>> = Layouts.numpad(PadKind.NUMBER, signed = false, decimal = false)

    /** Teclas del modo numérico (campos de número, teléfono, fecha). */
    fun setNumpad(r: List<List<KeyDef>>) {
        numpadRows = r
        if (layer == Layer.NUMPAD) { rebuild(); requestLayout(); invalidate() }
    }

    /** Hueco central del teclado dividido como fracción del ancho (0 = teclado normal). */
    var splitGap: Float = 0f
        set(v) { if (field != v) { field = v; rebuild(); requestLayout(); invalidate() } }

    private fun rebuild() {
        rows = if (layer == Layer.NUMPAD) numpadRows else Layouts.rows(layer)
        layoutKeys(width)
    }

    private fun computeKeyHeight(widthPx: Int): Float {
        val dm = resources.displayMetrics
        val wDp = widthPx / dm.density
        val hDp = dm.heightPixels / dm.density
        val base = (wDp * 0.085f).coerceIn(44f, 60f)
        val byScreen = hDp * 0.085f
        return dp(min(base, byScreen) * heightPercent / 100f)
    }

    private fun layoutKeys(w: Int) {
        frames.clear()
        if (w <= 0) return
        keyH = computeKeyHeight(w)
        gapX = dp(6f)
        gapY = dp(9f)
        sidePad = dp(5f)
        topPad = dp(2f)
        stripH = keyH * 0.95f
        rowsTop = topPad + stripH
        val full = w - 2 * sidePad
        val numpad = layer == Layer.NUMPAD
        val avail = if (numpad) minOf(full, dp(440f)) else full
        val x0 = (w - avail) / 2f
        val halves = if (splitGap > 0f && !numpad) Layouts.splitRows(layer) else null
        for (r in rows.indices) {
            val y = rowsTop + r * (keyH + gapY)
            if (halves != null && r < halves.size) {
                val gapW = avail * splitGap
                val halfW = (avail - gapW) / 2f
                placeRow(halves[r].first, x0, halfW, y, r)
                placeRow(halves[r].second, x0 + halfW + gapW, halfW, y, r)
            } else placeRow(rows[r], x0, avail, y, r)
        }
    }

    private fun placeRow(row: List<KeyDef>, left: Float, width: Float, y: Float, r: Int) {
        val sum = row.sumOf { it.weight.toDouble() }.toFloat()
        val unit = (width - gapX * (row.size - 1)) / sum
        var x = left
        for (k in row) {
            val kw = unit * k.weight
            if (k.type != KeyType.SPACER) frames.add(KeyFrame(k, r, RectF(x, y, x + kw, y + keyH)))
            x += kw + gapX
        }
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = MeasureSpec.getSize(widthSpec)
        if (w != width || frames.isEmpty()) layoutKeys(w)
        val kh = computeKeyHeight(w)
        val h = dp(2f) + kh * 0.95f + rows.size * kh + (rows.size - 1) * dp(9f) + dp(8f) + bottomInset
        setMeasuredDimension(w, h.toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) = layoutKeys(w)

    private var systemBottom = 0f

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        var b = 0
        if (Build.VERSION.SDK_INT >= 30) {
            b = maxOf(
                insets.getInsets(WindowInsets.Type.navigationBars()).bottom,
                insets.getInsets(WindowInsets.Type.systemBars()).bottom,
                insets.getInsets(WindowInsets.Type.tappableElement()).bottom,
            )
        }
        if (b.toFloat() != systemBottom) {
            systemBottom = b.toFloat()
            requestLayout()
        }
        return super.onApplyWindowInsets(insets)
    }

    /**
     * Margen inferior: el ajuste del usuario si lo hay; si no, la barra de navegación que informe el sistema
     * y, si no informa nada en Android 15+, un margen fijo para no quedar bajo los botones del teclado.
     */
    private val bottomInset: Float
        get() = when {
            bottomMarginDp > 0 -> dp(bottomMarginDp.toFloat())
            systemBottom > 0f -> systemBottom
            Build.VERSION.SDK_INT >= 35 -> dp(28f)
            else -> 0f
        }

    // ---------- dibujo ----------
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private val tmp = RectF()
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val regular = Typeface.create("sans-serif", Typeface.NORMAL)

    private fun isLetterKey(t: KeyType) = t == KeyType.CHAR || t == KeyType.SPACE

    private fun cased(def: KeyDef): String =
        if (def.type == KeyType.CHAR && shift != Shift.OFF && def.label.firstOrNull()?.isLetter() == true)
            def.label.uppercase() else def.label

    override fun onDraw(canvas: Canvas) {
        val t = theme
        canvas.drawColor(t.bg)
        drawStrip(canvas, t)
        if (clipOpen) { drawPanel(canvas, t); return }
        val radius = max(dp(5f), keyH * 0.12f)
        for (f in frames) {
            val pressed = isPressed(f)
            val enterAction = f.def.type == KeyType.ENTER && enterLabel != null
            val color = when {
                enterAction -> if (pressed) t.accent and 0xCCFFFFFF.toInt() else t.accent
                isLetterKey(f.def.type) -> if (pressed && f.def.type == KeyType.CHAR) t.letterKeyPressed else t.letterKey
                else -> if (pressed) t.funcKeyPressed else t.funcKey
            }
            shadowPaint.color = t.shadow
            canvas.drawRoundRect(f.rect.left, f.rect.top + dp(1f), f.rect.right, f.rect.bottom + dp(1f), radius, radius, shadowPaint)
            fill.color = color
            canvas.drawRoundRect(f.rect, radius, radius, fill)
            drawKeyContent(canvas, f, t, enterAction, pressed)
        }
        drawOverlays(canvas, t)
    }

    private fun drawStrip(canvas: Canvas, t: KbTheme) {
        val w = width.toFloat()
        val left = stripLeft
        val slotW = (w - left) / 3f
        val cy = topPad + stripH / 2f
        // botones: pegar y portapapeles
        val editing = titleEdit != null
        for (b in 0..1) {
            btnRect(b, tmp)
            val pressed = pointers.valueAtOrNull { it.mode == Mode.BUTTON && it.slot == b } != null
            val active = (b == 1 && (clipOpen || editing))
            fill.color = if (active) t.accent else if (pressed) t.letterKeyPressed else t.letterKey
            shadowPaint.color = t.shadow
            canvas.drawRoundRect(tmp.left, tmp.top + dp(1f), tmp.right, tmp.bottom + dp(1f), dp(8f), dp(8f), shadowPaint)
            canvas.drawRoundRect(tmp, dp(8f), dp(8f), fill)
            val ic = if (active) t.onAccent else t.text
            if (editing) {
                if (b == 0) drawCross(canvas, tmp.centerX(), tmp.centerY(), btnSize * 0.2f, ic)
                else drawCheck(canvas, tmp.centerX(), tmp.centerY(), btnSize * 0.22f, ic)
            } else drawClipIcon(canvas, tmp.centerX(), tmp.centerY(), btnSize * 0.27f, ic, paste = b == 0)
        }
        if (editing) {
            textPaint.textSize = stripH * 0.36f
            textPaint.typeface = regular
            textPaint.textAlign = Paint.Align.LEFT
            val label = context.getString(R.string.clip_title_label)
            textPaint.color = t.subtle
            canvas.drawText(label, left, centerBaseline(cy), textPaint)
            val lw = textPaint.measureText(label) + dp(6f)
            textPaint.color = t.text
            textPaint.typeface = medium
            var shown = titleEdit ?: ""
            val avail = w - left - lw - dp(14f)
            while (shown.length > 1 && textPaint.measureText("…$shown|") > avail) shown = shown.drop(1).let { it }
            val prefix = if (shown.length < (titleEdit ?: "").length) "…" else ""
            canvas.drawText("$prefix$shown|", left + lw, centerBaseline(cy), textPaint)
            textPaint.textAlign = Paint.Align.CENTER
            return
        }
        if (clipOpen) {
            textPaint.textSize = stripH * 0.36f
            textPaint.typeface = medium
            textPaint.color = t.text
            textPaint.textAlign = Paint.Align.LEFT
            canvas.drawText(context.getString(R.string.clip_title, pinnedClips.size + recentClips.size), left, centerBaseline(cy), textPaint)
            textPaint.textAlign = Paint.Align.RIGHT
            textPaint.color = if (clearArmed) 0xFFFF3B30.toInt() else t.accent
            val clearLabel = context.getString(if (clearArmed) R.string.clip_clear_confirm else R.string.clip_clear)
            if (recentClips.isNotEmpty()) canvas.drawText(clearLabel, w - dp(14f), centerBaseline(cy), textPaint)
            textPaint.textAlign = Paint.Align.CENTER
            return
        }
        textPaint.textSize = stripH * 0.38f
        for (i in 0 until 3) {
            val item = slots[i] ?: continue
            val pressedSlot = pointers.valueAtOrNull { it.mode == Mode.STRIP && it.slot == i }
            if (pressedSlot != null) {
                tmp.set(left + i * slotW + dp(4f), topPad + dp(3f), left + (i + 1) * slotW - dp(4f), topPad + stripH - dp(3f))
                fill.color = t.divider
                canvas.drawRoundRect(tmp, dp(8f), dp(8f), fill)
            }
            textPaint.typeface = if (item.emphasized) medium else regular
            textPaint.color = t.text
            val maxW = slotW - dp(20f)
            val s = ellipsize(item.label, maxW)
            canvas.drawText(s, left + i * slotW + slotW / 2f, centerBaseline(cy), textPaint)
            if (i > 0 && slots[i - 1] != null) {
                fill.color = t.divider
                canvas.drawRect(left + i * slotW - dp(0.5f), topPad + stripH * 0.22f, left + i * slotW + dp(0.5f), topPad + stripH * 0.78f, fill)
            }
        }
    }

    /** Icono de portapapeles; con [paste] lleva una flecha hacia abajo, si no, líneas de lista (historial). */
    private fun drawClipIcon(c: Canvas, cx: Float, cy: Float, s: Float, color: Int, paste: Boolean) {
        strokeStyle(color, s)
        tmp2.set(cx - s * 0.85f, cy - s * 0.95f, cx + s * 0.85f, cy + s * 1.1f)
        c.drawRoundRect(tmp2, s * 0.25f, s * 0.25f, stroke)
        fill.color = color
        tmp2.set(cx - s * 0.38f, cy - s * 1.2f, cx + s * 0.38f, cy - s * 0.7f)
        c.drawRoundRect(tmp2, s * 0.15f, s * 0.15f, fill)
        if (paste) {
            c.drawLine(cx, cy - s * 0.3f, cx, cy + s * 0.65f, stroke)
            c.drawLine(cx - s * 0.38f, cy + s * 0.27f, cx, cy + s * 0.65f, stroke)
            c.drawLine(cx + s * 0.38f, cy + s * 0.27f, cx, cy + s * 0.65f, stroke)
        } else {
            c.drawLine(cx - s * 0.45f, cy - s * 0.2f, cx + s * 0.45f, cy - s * 0.2f, stroke)
            c.drawLine(cx - s * 0.45f, cy + s * 0.2f, cx + s * 0.45f, cy + s * 0.2f, stroke)
            c.drawLine(cx - s * 0.45f, cy + s * 0.6f, cx + s * 0.1f, cy + s * 0.6f, stroke)
        }
    }

    private val tmp2 = RectF()

    private fun drawCross(c: Canvas, cx: Float, cy: Float, k: Float, color: Int) {
        strokeStyle(color, k * 2f)
        c.drawLine(cx - k, cy - k, cx + k, cy + k, stroke)
        c.drawLine(cx - k, cy + k, cx + k, cy - k, stroke)
    }

    private fun drawCheck(c: Canvas, cx: Float, cy: Float, k: Float, color: Int) {
        strokeStyle(color, k * 2f)
        c.drawLine(cx - k, cy, cx - k * 0.3f, cy + k * 0.75f, stroke)
        c.drawLine(cx - k * 0.3f, cy + k * 0.75f, cx + k, cy - k * 0.7f, stroke)
    }

    /** Chincheta: cabeza redonda y aguja; rellena cuando está fijada. */
    private fun drawPin(c: Canvas, cx: Float, cy: Float, k: Float, color: Int, filled: Boolean) {
        strokeStyle(color, k * 1.6f)
        c.save()
        c.rotate(35f, cx, cy)
        tmp2.set(cx - k * 0.55f, cy - k * 1.1f, cx + k * 0.55f, cy + k * 0.1f)
        if (filled) { fill.color = color; c.drawRoundRect(tmp2, k * 0.2f, k * 0.2f, fill) } else c.drawRoundRect(tmp2, k * 0.2f, k * 0.2f, stroke)
        c.drawLine(cx - k * 0.9f, cy + k * 0.1f, cx + k * 0.9f, cy + k * 0.1f, stroke)
        c.drawLine(cx, cy + k * 0.1f, cx, cy + k * 1.2f, stroke)
        c.restore()
    }

    private fun drawPencil(c: Canvas, cx: Float, cy: Float, k: Float, color: Int) {
        strokeStyle(color, k * 1.3f)
        c.save()
        c.rotate(-45f, cx, cy)
        // cuerpo del lápiz + punta
        path.reset()
        path.moveTo(cx - k * 1.35f, cy)
        path.lineTo(cx - k * 0.7f, cy - k * 0.6f)
        path.lineTo(cx + k * 1.15f, cy - k * 0.6f)
        path.lineTo(cx + k * 1.15f, cy + k * 0.6f)
        path.lineTo(cx - k * 0.7f, cy + k * 0.6f)
        path.close()
        c.drawPath(path, stroke)
        c.drawLine(cx - k * 0.7f, cy - k * 0.6f, cx - k * 0.7f, cy + k * 0.6f, stroke)
        c.restore()
    }

    // ---- panel del historial ----
    private val panelTop get() = rowsTop
    private val panelBottom get() = rowsTop + rows.size * keyH + (rows.size - 1) * gapY
    private val itemH get() = keyH * 1.05f
    private val itemGap get() = dp(6f)
    /** Fila del panel: cabecera ("Fijadas"/"Recientes") o tarjeta (fijada o reciente). */
    private class PRow(val kind: Int, val idx: Int, val y: Float, val h: Float)

    private fun panelRows(): List<PRow> {
        val out = ArrayList<PRow>()
        var y = 0f
        val head = keyH * 0.55f
        if (pinnedClips.isNotEmpty()) {
            out.add(PRow(0, 0, y, head)); y += head
            for (i in pinnedClips.indices) { out.add(PRow(2, i, y, itemH)); y += itemH + itemGap }
        }
        if (recentClips.isNotEmpty()) {
            if (pinnedClips.isNotEmpty()) { out.add(PRow(1, 0, y, head)); y += head }
            for (i in recentClips.indices) { out.add(PRow(3, i, y, itemH)); y += itemH + itemGap }
        }
        return out
    }

    private fun maxScroll(): Float {
        val last = panelRows().lastOrNull() ?: return 0f
        val content = last.y + last.h
        return maxOf(0f, content - (panelBottom - panelTop))
    }

    private fun wrap(text: String, maxW: Float): List<String> {
        val key = "$maxW|$text"
        wrapCache[key]?.let { return it }
        val flat = text.replace(Regex("\\s+"), " ").trim()
        val lines = ArrayList<String>(2)
        var rest = flat
        while (rest.isNotEmpty() && lines.size < 2) {
            val n = textPaint.breakText(rest, true, maxW, null)
            if (n >= rest.length) { lines.add(rest); rest = ""; break }
            var cut = rest.lastIndexOf(' ', n - 1).let { if (it < n / 2) n else it }
            if (cut <= 0) cut = n
            lines.add(rest.substring(0, cut).trim())
            rest = rest.substring(cut).trim()
        }
        if (rest.isNotEmpty() && lines.isNotEmpty()) {
            var last = lines.removeAt(lines.size - 1) + " " + rest
            last = ellipsize(last, maxW)
            lines.add(last)
        }
        if (wrapCache.size > 200) wrapCache.clear()
        wrapCache[key] = lines
        return lines
    }

    private fun drawPanel(canvas: Canvas, t: KbTheme) {
        val top = panelTop
        val bottom = panelBottom
        canvas.save()
        canvas.clipRect(0f, top - dp(2f), width.toFloat(), bottom + dp(4f))
        if (pinnedClips.isEmpty() && recentClips.isEmpty()) {
            textPaint.typeface = regular
            textPaint.textSize = keyH * 0.3f
            textPaint.color = t.subtle
            canvas.drawText(context.getString(R.string.clip_empty), width / 2f, centerBaseline((top + bottom) / 2f), textPaint)
            canvas.restore()
            return
        }
        val side = dp(8f)
        val zone = dp(52f)
        val textW = width - 2 * side - dp(16f) - 2 * zone + dp(8f)
        textPaint.textAlign = Paint.Align.LEFT
        for (r in panelRows()) {
            val y = top - clipScroll + r.y
            if (y + r.h < top || y > bottom) continue
            if (r.kind < 2) {
                textPaint.typeface = medium
                textPaint.textSize = keyH * 0.27f
                textPaint.color = t.subtle
                val label = context.getString(if (r.kind == 0) R.string.clip_pinned else R.string.clip_recent).uppercase()
                canvas.drawText(label, side + dp(8f), centerBaseline(y + r.h / 2f), textPaint)
                continue
            }
            val pinned = r.kind == 2
            tmp.set(side, y, width - side, y + r.h)
            shadowPaint.color = t.shadow
            canvas.drawRoundRect(tmp.left, tmp.top + dp(1f), tmp.right, tmp.bottom + dp(1f), dp(8f), dp(8f), shadowPaint)
            fill.color = t.letterKey
            canvas.drawRoundRect(tmp, dp(8f), dp(8f), fill)
            if (pinned) {
                fill.color = t.accent
                canvas.save()
                canvas.clipRect(tmp.left, tmp.top, tmp.left + dp(4f), tmp.bottom)
                canvas.drawRoundRect(tmp, dp(8f), dp(8f), fill)
                canvas.restore()
            }
            val lh = keyH * 0.36f
            val cx0 = side + dp(16f)
            textPaint.textSize = keyH * 0.3f
            if (pinned) {
                val e = pinnedClips[r.idx]
                if (e.title.isNotEmpty()) {
                    textPaint.typeface = medium
                    textPaint.color = t.text
                    canvas.drawText(ellipsize(e.title, textW), cx0, centerBaseline(y + r.h / 2f - lh * 0.45f), textPaint)
                    textPaint.typeface = regular
                    textPaint.textSize = keyH * 0.26f
                    textPaint.color = t.subtle
                    canvas.drawText(wrap(e.text, textW).firstOrNull() ?: "", cx0, centerBaseline(y + r.h / 2f + lh * 0.55f), textPaint)
                } else drawCardText(canvas, wrap(e.text, textW), cx0, y, r.h, lh, t)
            } else drawCardText(canvas, wrap(recentClips[r.idx], textW), cx0, y, r.h, lh, t)
            // acciones: [fijar / editar título] [borrar / desfijar]
            val cy = y + r.h / 2f
            val k = keyH * 0.11f
            val x2 = width - side - zone / 2f
            val x1 = x2 - zone
            if (pinned) {
                drawPencil(canvas, x1, cy, k, t.hint)
                drawPin(canvas, x2, cy, k, t.accent, filled = true)
            } else {
                drawPin(canvas, x1, cy, k, t.hint, filled = false)
                drawCross(canvas, x2, cy, k * 0.85f, t.hint)
            }
        }
        textPaint.textAlign = Paint.Align.CENTER
        canvas.restore()
    }

    private fun drawCardText(c: Canvas, lines: List<String>, x: Float, y: Float, h: Float, lh: Float, t: KbTheme) {
        textPaint.typeface = regular
        textPaint.color = t.text
        val startY = y + h / 2f - (lines.size - 1) * lh / 2f
        for ((k, ln) in lines.withIndex()) c.drawText(ln, x, centerBaseline(startY + k * lh), textPaint)
    }

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            scroller.forceFinished(true)
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            clipScroll = (clipScroll + dy).coerceIn(0f, maxScroll())
            invalidate()
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            val max = maxScroll().toInt()
            if (max > 0) {
                scroller.fling(0, clipScroll.toInt(), 0, -vy.toInt(), 0, 0, 0, max)
                postInvalidateOnAnimation()
            }
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (e.y < panelTop) return true
            val rel = e.y - panelTop + clipScroll
            val r = panelRows().firstOrNull { rel >= it.y && rel <= it.y + it.h && it.kind >= 2 } ?: return true
            feedback()
            val pinned = r.kind == 2
            val zone = dp(52f) + dp(8f)
            val action = when {
                e.x > width - zone -> if (pinned) ClipAction.UNPIN else ClipAction.DELETE
                e.x > width - 2 * zone + dp(8f) -> if (pinned) ClipAction.EDIT_TITLE else ClipAction.PIN
                else -> ClipAction.PICK
            }
            listener?.onClipboardAction(action, pinned, r.idx)
            return true
        }
    })

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            clipScroll = scroller.currY.toFloat().coerceIn(0f, maxScroll())
            postInvalidateOnAnimation()
        }
    }

    private fun ellipsize(s: String, maxW: Float): String {
        if (textPaint.measureText(s) <= maxW) return s
        var n = s.length
        while (n > 1 && textPaint.measureText(s, 0, n) + textPaint.measureText("…") > maxW) n--
        return s.substring(0, n) + "…"
    }

    private fun centerBaseline(cy: Float): Float {
        val fm = textPaint.fontMetrics
        return cy - (fm.ascent + fm.descent) / 2f
    }

    private val bounds = android.graphics.Rect()

    /** Como [centerBaseline] pero centrando el dibujo real del glifo: queda bien en . , ' " - etc. */
    private fun centerBaselineGlyph(text: String, cy: Float): Float {
        textPaint.getTextBounds(text, 0, text.length, bounds)
        return cy - (bounds.top + bounds.bottom) / 2f
    }

    private fun drawKeyContent(canvas: Canvas, f: KeyFrame, t: KbTheme, enterAction: Boolean, pressed: Boolean) {
        val r = f.rect
        val cx = r.centerX()
        val cy = r.centerY()
        val def = f.def
        when (def.type) {
            KeyType.CHAR -> {
                val hint = def.hint
                val hasHint = hint != null && layer == Layer.LETTERS
                textPaint.typeface = regular
                textPaint.color = t.text
                textPaint.textSize = keyH * (if (layer == Layer.NUMPAD) 0.52f else 0.43f)
                val label = cased(def)
                val ly = cy + if (hasHint) keyH * 0.08f else 0f
                val glyphCentered = label.length == 1 && !label[0].isLetterOrDigit()
                canvas.drawText(label, cx, if (glyphCentered) centerBaselineGlyph(label, ly) else centerBaseline(ly), textPaint)
                if (hasHint) {
                    textPaint.textSize = keyH * 0.2f
                    textPaint.color = t.hint
                    canvas.drawText(hint!!, cx, centerBaseline(r.top + keyH * 0.2f), textPaint)
                }
            }
            KeyType.SYM1, KeyType.SYM2, KeyType.ABC -> {
                textPaint.typeface = medium
                textPaint.color = t.text
                textPaint.textSize = keyH * 0.3f
                canvas.drawText(def.label, cx, centerBaseline(cy), textPaint)
            }
            KeyType.SPACE -> {
                if (spaceLabel.isNotEmpty()) {
                    textPaint.typeface = regular
                    textPaint.color = if (spaceLabelAccent) t.accent else t.subtle
                    textPaint.textSize = keyH * 0.28f
                    canvas.drawText(spaceLabel, cx, centerBaseline(cy), textPaint)
                }
            }
            KeyType.ENTER -> {
                val label = enterLabel
                if (label != null) {
                    textPaint.typeface = medium
                    textPaint.color = t.onAccent
                    textPaint.textSize = keyH * 0.3f
                    canvas.drawText(ellipsize(label, r.width() - dp(8f)), cx, centerBaseline(cy), textPaint)
                } else drawReturn(canvas, cx, cy, keyH * 0.2f, t.text)
            }
            KeyType.SHIFT -> drawShift(canvas, cx, cy, keyH * 0.2f, t.text)
            KeyType.DELETE -> drawDelete(canvas, cx, cy, keyH * 0.2f, t.text)
            KeyType.SPACER -> {}
        }
    }

    private fun strokeStyle(color: Int, size: Float) {
        stroke.color = color
        stroke.strokeWidth = max(dp(1.6f), size * 0.12f)
    }

    private fun drawShift(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        val on = shift != Shift.OFF
        path.reset()
        path.moveTo(cx, cy - s)
        path.lineTo(cx + s, cy)
        path.lineTo(cx + s * 0.45f, cy)
        path.lineTo(cx + s * 0.45f, cy + s * 0.75f)
        path.lineTo(cx - s * 0.45f, cy + s * 0.75f)
        path.lineTo(cx - s * 0.45f, cy)
        path.lineTo(cx - s, cy)
        path.close()
        if (on) { fill.color = color; c.drawPath(path, fill) }
        strokeStyle(color, s)
        c.drawPath(path, stroke)
        if (shift == Shift.LOCK) {
            val y = cy + s * 1.05f
            c.drawLine(cx - s * 0.45f, y, cx + s * 0.45f, y, stroke)
        }
    }

    private fun drawDelete(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        val w = s * 1.25f
        val h = s * 0.85f
        path.reset()
        path.moveTo(cx - w, cy)
        path.lineTo(cx - w * 0.4f, cy - h)
        path.lineTo(cx + w, cy - h)
        path.lineTo(cx + w, cy + h)
        path.lineTo(cx - w * 0.4f, cy + h)
        path.close()
        strokeStyle(color, s)
        c.drawPath(path, stroke)
        val k = s * 0.38f
        val mx = cx + w * 0.22f
        c.drawLine(mx - k, cy - k, mx + k, cy + k, stroke)
        c.drawLine(mx - k, cy + k, mx + k, cy - k, stroke)
    }

    private fun drawReturn(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        strokeStyle(color, s)
        path.reset()
        path.moveTo(cx + s * 1.1f, cy - s * 0.8f)
        path.lineTo(cx + s * 1.1f, cy + s * 0.3f)
        path.lineTo(cx - s * 1.1f, cy + s * 0.3f)
        c.drawPath(path, stroke)
        path.reset()
        path.moveTo(cx - s * 0.55f, cy - s * 0.25f)
        path.lineTo(cx - s * 1.1f, cy + s * 0.3f)
        path.lineTo(cx - s * 0.55f, cy + s * 0.85f)
        c.drawPath(path, stroke)
    }

    // ---------- globos (vista previa, variantes, símbolo deslizado) ----------
    private fun drawOverlays(canvas: Canvas, t: KbTheme) {
        for (i in 0 until pointers.size()) {
            val p = pointers.valueAt(i)
            val f = p.key ?: continue
            if (f.def.type != KeyType.CHAR) continue
            when (p.mode) {
                Mode.NORMAL -> drawBubble(canvas, t, f, cased(f.def))
                Mode.HINT -> drawBubble(canvas, t, f, f.def.hint ?: "")
                Mode.POPUP -> drawAlternates(canvas, t, f, p)
                else -> {}
            }
        }
    }

    private fun drawBubble(canvas: Canvas, t: KbTheme, f: KeyFrame, text: String) {
        val r = f.rect
        val bw = r.width() + dp(18f)
        val bh = keyH * 1.0f
        var left = r.centerX() - bw / 2f
        left = left.coerceIn(dp(2f), width - dp(2f) - bw)
        val top = max(dp(1f), r.top - bh - dp(2f))
        val bottom = top + bh
        val rad = dp(9f)
        for (pass in 0..1) {
            val paint = if (pass == 0) shadowPaint else fill
            if (pass == 0) { shadowPaint.color = t.shadow; canvas.save(); canvas.translate(0f, dp(1.5f)) } else fill.color = t.popup
            tmp.set(left, top, left + bw, bottom)
            canvas.drawRoundRect(tmp, rad, rad, paint)
            canvas.drawRect(r.left, bottom - rad, r.right, r.top + rad, paint)
            canvas.drawRoundRect(r, dp(6f), dp(6f), paint)
            if (pass == 0) canvas.restore()
        }
        textPaint.typeface = regular
        textPaint.color = t.text
        textPaint.textSize = keyH * 0.62f
        canvas.drawText(text, left + bw / 2f, centerBaseline((top + min(bottom, r.top + rad)) / 2f + dp(2f)), textPaint)
    }

    private class PopupGeom(var left: Float, var top: Float, var cell: Float, var h: Float)

    private val popupGeom = PopupGeom(0f, 0f, 0f, 0f)

    private fun computePopup(f: KeyFrame): PopupGeom {
        val n = f.def.alts.size
        val cell = max(f.rect.width() * 0.9f, dp(42f))
        val pw = cell * n
        val h = keyH * 1.05f
        val left = (f.rect.centerX() - pw / 2f).coerceIn(dp(3f), max(dp(3f), width - dp(3f) - pw))
        val top = max(dp(1f), f.rect.top - h - dp(6f))
        popupGeom.left = left; popupGeom.top = top; popupGeom.cell = cell; popupGeom.h = h
        return popupGeom
    }

    private fun drawAlternates(canvas: Canvas, t: KbTheme, f: KeyFrame, p: Ptr) {
        val g = computePopup(f)
        val n = f.def.alts.size
        val pw = g.cell * n
        val rad = dp(10f)
        shadowPaint.color = t.shadow
        tmp.set(g.left, g.top + dp(1.5f), g.left + pw, g.top + g.h + dp(1.5f))
        canvas.drawRoundRect(tmp, rad, rad, shadowPaint)
        fill.color = t.popup
        tmp.set(g.left, g.top, g.left + pw, g.top + g.h)
        canvas.drawRoundRect(tmp, rad, rad, fill)
        textPaint.typeface = regular
        textPaint.textSize = keyH * 0.5f
        for (i in 0 until n) {
            val sel = i == p.altIndex
            if (sel) {
                fill.color = t.accent
                tmp.set(g.left + i * g.cell + dp(3f), g.top + dp(3f), g.left + (i + 1) * g.cell - dp(3f), g.top + g.h - dp(3f))
                canvas.drawRoundRect(tmp, dp(8f), dp(8f), fill)
            }
            textPaint.color = if (sel) t.onAccent else t.text
            val alt = f.def.alts[i]
            val shown = if (shift != Shift.OFF && alt.firstOrNull()?.isLetter() == true) alt.uppercase() else alt
            canvas.drawText(shown, g.left + i * g.cell + g.cell / 2f, centerBaseline(g.top + g.h / 2f), textPaint)
        }
    }

    // ---------- táctil ----------
    private enum class Mode { NORMAL, HINT, POPUP, SPACE_DRAG, STRIP, BUTTON, IGNORED }

    private class Ptr(val id: Int) {
        var key: KeyFrame? = null
        var mode = Mode.NORMAL
        var downX = 0f
        var downY = 0f
        var lastX = 0f
        var slot = -1
        var altIndex = 0
        var spaceAcc = 0f
        var longFired = false
        var longPress: Runnable? = null
        var repeat: Runnable? = null
    }

    private val pointers = SparseArray<Ptr>()
    private val handler = Handler(Looper.getMainLooper())

    private inline fun SparseArray<Ptr>.valueAtOrNull(pred: (Ptr) -> Boolean): Ptr? {
        for (i in 0 until size()) if (pred(valueAt(i))) return valueAt(i)
        return null
    }

    private fun isPressed(f: KeyFrame): Boolean {
        for (i in 0 until pointers.size()) {
            val p = pointers.valueAt(i)
            if (p.key === f && p.mode != Mode.IGNORED && p.mode != Mode.STRIP && p.mode != Mode.BUTTON) return true
        }
        return false
    }

    private fun hit(x: Float, y: Float): KeyFrame? {
        if (frames.isEmpty()) return null
        val row = ((y - rowsTop) / (keyH + gapY)).toInt().coerceIn(0, rows.size - 1)
        var best: KeyFrame? = null
        var bestD = Float.MAX_VALUE
        for (f in frames) {
            if (f.row != row) continue
            val d = if (x < f.rect.left) f.rect.left - x else if (x > f.rect.right) x - f.rect.right else 0f
            if (d < bestD) { bestD = d; best = f }
        }
        return best
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (clipOpen) {
            if (e.actionMasked == MotionEvent.ACTION_DOWN) panelTouch = e.y >= rowsTop - gapY / 2f
            if (panelTouch) {
                gestures.onTouchEvent(e)
                if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) panelTouch = false
                return true
            }
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                onDown(e.getPointerId(i), e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) {
                val p = pointers.get(e.getPointerId(i)) ?: continue
                onMove(p, e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (e.actionMasked == MotionEvent.ACTION_UP) performClick()
                val i = e.actionIndex
                onUp(e.getPointerId(i), e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_CANCEL -> cancelAll()
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private fun feedback() {
        if (hapticsEnabled) {
            @Suppress("DEPRECATION")
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING)
        }
        if (soundEnabled) {
            (context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)
                ?.playSoundEffect(AudioManager.FX_KEYPRESS_STANDARD, -1f)
        }
    }

    /** 0 = pegar, 1 = historial, 2 = borrar todo (solo con el panel abierto), -1 = ninguno. */
    private fun stripButtonAt(x: Float, y: Float): Int {
        val pad = dp(3f)
        for (b in 0..1) {
            btnRect(b, tmp)
            tmp.inset(-pad, -pad)
            if (tmp.contains(x, y)) return b
        }
        if (clipOpen && recentClips.isNotEmpty() && titleEdit == null) {
            clearRect(tmp)
            if (tmp.contains(x, y)) return 2
        }
        return -1
    }

    private fun onDown(id: Int, x: Float, y: Float) {
        val p = Ptr(id)
        p.downX = x; p.downY = y; p.lastX = x
        pointers.put(id, p)
        if (y < rowsTop - gapY / 2f) {
            val b = stripButtonAt(x, y)
            if (b >= 0) {
                p.mode = Mode.BUTTON
                p.slot = b
                feedback()
                invalidate()
                return
            }
            p.mode = Mode.STRIP
            val left = stripLeft
            p.slot = ((x - left) / ((width - left) / 3f)).toInt().coerceIn(0, 2)
            if (clipOpen || titleEdit != null || x < left || slots[p.slot] == null) p.mode = Mode.IGNORED
            invalidate()
            return
        }
        val f = hit(x, y)
        if (f == null) { p.mode = Mode.IGNORED; return }
        p.key = f
        feedback()
        armKey(p, f)
        invalidate()
    }

    private fun armKey(p: Ptr, f: KeyFrame) {
        cancelTimers(p)
        p.longFired = false
        when (f.def.type) {
            KeyType.DELETE -> {
                if (p.mode == Mode.NORMAL && p.repeat == null) {
                    listener?.onSpecial(KeyType.DELETE)
                    var count = 0
                    val rep = object : Runnable {
                        override fun run() {
                            listener?.onDeleteRepeat()
                            count++
                            handler.postDelayed(this, if (count < 8) 70L else 38L)
                        }
                    }
                    p.repeat = rep
                    handler.postDelayed(rep, 380L)
                }
            }
            KeyType.CHAR -> if (f.def.alts.isNotEmpty()) {
                val lp = Runnable {
                    if (p.mode == Mode.NORMAL) {
                        p.mode = Mode.POPUP
                        p.longFired = true
                        p.altIndex = 0
                        updateAlt(p, p.lastX)
                        feedback()
                        invalidate()
                    }
                }
                p.longPress = lp
                handler.postDelayed(lp, 360L)
            }
            else -> {}
        }
    }

    private fun cancelTimers(p: Ptr) {
        p.longPress?.let { handler.removeCallbacks(it) }
        p.repeat?.let { handler.removeCallbacks(it) }
        p.longPress = null
        p.repeat = null
    }

    private fun updateAlt(p: Ptr, x: Float) {
        val f = p.key ?: return
        val g = computePopup(f)
        p.altIndex = ((x - g.left) / g.cell).toInt().coerceIn(0, f.def.alts.size - 1)
    }

    private fun onMove(p: Ptr, x: Float, y: Float) {
        val f = p.key
        val dx = x - p.lastX
        p.lastX = x
        when (p.mode) {
            Mode.STRIP -> {
                val left = stripLeft
                val s = ((x - left) / ((width - left) / 3f)).toInt().coerceIn(0, 2)
                if (s != p.slot || y > rowsTop) { p.slot = -1 }
                invalidate()
                return
            }
            Mode.BUTTON -> {
                if (stripButtonAt(x, y) != p.slot) p.slot = -1
                invalidate()
                return
            }
            Mode.IGNORED -> return
            Mode.POPUP -> { updateAlt(p, x); invalidate(); return }
            Mode.SPACE_DRAG -> {
                p.spaceAcc += dx
                val step = dp(11f)
                while (abs(p.spaceAcc) >= step) {
                    val dir = if (p.spaceAcc > 0) 1 else -1
                    listener?.onCursorMove(dir)
                    p.spaceAcc -= dir * step
                }
                return
            }
            else -> {}
        }
        if (f == null) return
        when (f.def.type) {
            KeyType.SPACE -> if (abs(x - p.downX) > dp(16f)) {
                cancelTimers(p)
                p.mode = Mode.SPACE_DRAG
                p.spaceAcc = 0f
                invalidate()
            }
            KeyType.CHAR -> {
                val dy = y - p.downY
                val canHint = swipeDownEnabled && f.def.hint != null && layer == Layer.LETTERS
                if (canHint && p.mode == Mode.NORMAL && dy > keyH * 0.4f) {
                    cancelTimers(p)
                    p.mode = Mode.HINT
                    feedback()
                    invalidate()
                } else if (p.mode == Mode.HINT && dy < keyH * 0.22f) {
                    p.mode = Mode.NORMAL
                    armKey(p, f)
                    invalidate()
                } else if (p.mode == Mode.NORMAL) {
                    val nf = hit(x, y)
                    if (nf != null && nf !== f && nf.def.type == KeyType.CHAR) {
                        p.key = nf
                        p.downY = y
                        armKey(p, nf)
                        invalidate()
                    }
                }
            }
            else -> {}
        }
    }

    private fun onUp(id: Int, x: Float, y: Float) {
        val p = pointers.get(id) ?: return
        pointers.remove(id)
        cancelTimers(p)
        val f = p.key
        when (p.mode) {
            Mode.STRIP -> if (p.slot >= 0) listener?.onSuggestion(p.slot)
            Mode.BUTTON -> when (p.slot) {
                0 -> if (titleEdit != null) listener?.onTitleCancel() else listener?.onPasteLatest()
                1 -> if (titleEdit != null) listener?.onTitleOk() else toggleClipboard()
                2 -> if (clearArmed) {
                    clearArmed = false
                    listener?.onClipboardClear()
                } else {
                    clearArmed = true
                    handler.postDelayed({ if (clearArmed) { clearArmed = false; invalidate() } }, 3000L)
                }
            }
            Mode.POPUP -> if (f != null) {
                updateAlt(p, x)
                val alt = f.def.alts[p.altIndex]
                listener?.onChar(if (shift != Shift.OFF && alt.firstOrNull()?.isLetter() == true) alt.uppercase() else alt)
            }
            Mode.HINT -> f?.def?.hint?.let { listener?.onChar(it) }
            Mode.NORMAL -> if (f != null && !p.longFired) {
                when (f.def.type) {
                    KeyType.CHAR -> listener?.onChar(cased(f.def))
                    KeyType.DELETE -> {}
                    else -> listener?.onSpecial(f.def.type)
                }
            }
            else -> {}
        }
        invalidate()
    }

    private fun cancelAll() {
        for (i in 0 until pointers.size()) cancelTimers(pointers.valueAt(i))
        pointers.clear()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        cancelAll()
        super.onDetachedFromWindow()
    }
}
