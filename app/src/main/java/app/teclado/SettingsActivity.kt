package app.teclado

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

/** Pantalla de ajustes y configuración inicial. Se construye por código: sin dependencias extra. */
class SettingsActivity : Activity() {

    private lateinit var prefs: Prefs
    private val dark get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private val cBg get() = if (dark) 0xFF000000.toInt() else 0xFFF2F2F7.toInt()
    private val cCard get() = if (dark) 0xFF1C1C1E.toInt() else 0xFFFFFFFF.toInt()
    private val cText get() = if (dark) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
    private val cSub get() = if (dark) 0xFF8E8E93.toInt() else 0xFF6C6C70.toInt()
    private val cLine get() = if (dark) 0xFF38383A.toInt() else 0xFFE5E5EA.toInt()
    private val cAccent get() = if (dark) 0xFF0A84FF.toInt() else 0xFF007AFF.toInt()

    private lateinit var content: LinearLayout
    private var enableBtn: Button? = null
    private var selectBtn: Button? = null
    private var shortcutsBox: LinearLayout? = null
    private var learnedLabel: TextView? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val scroll = ScrollView(this)
        scroll.setBackgroundColor(cBg)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(scroll)
        scroll.setOnApplyWindowInsetsListener { _, insets ->
            val side = dp(16)
            val (l, t, r, b) = barInsets(insets)
            content.setPadding(maxOf(side, l), t + dp(8), maxOf(side, r), b + dp(24))
            insets
        }
        scroll.requestApplyInsets()
        build()
    }

    /** (izquierda, arriba, derecha, abajo) de barras del sistema y teclado, en cualquier versión de Android. */
    @Suppress("DEPRECATION")
    private fun barInsets(i: WindowInsets): List<Int> {
        if (Build.VERSION.SDK_INT >= 30) {
            val bars = i.getInsets(WindowInsets.Type.systemBars())
            val ime = i.getInsets(WindowInsets.Type.ime())
            return listOf(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
        }
        return listOf(i.systemWindowInsetLeft, i.systemWindowInsetTop, i.systemWindowInsetRight, i.systemWindowInsetBottom)
    }

    override fun onResume() {
        super.onResume()
        refreshSetup()
        refreshShortcuts()
        refreshLearned()
    }

    private fun build() {
        content.removeAllViews()
        content.addView(text(getString(R.string.app_name), 34f, cText, bold = true).apply { setPadding(dp(4), dp(24), 0, 0) })
        content.addView(text(getString(R.string.subtitle), 15f, cSub).apply { setPadding(dp(4), dp(2), 0, dp(8)) })

        section(R.string.sec_setup)
        card {
            addView(setupRow(R.string.step_enable, R.string.step_enable_sub, R.string.btn_open, { enableBtn = it }) {
                startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            })
            addView(divider())
            addView(setupRow(R.string.step_select, R.string.step_select_sub, R.string.btn_choose, { selectBtn = it }) {
                getSystemService(InputMethodManager::class.java).showInputMethodPicker()
            })
            addView(divider())
            addView(EditText(this@SettingsActivity).apply {
                hint = getString(R.string.try_hint)
                setTextColor(cText)
                setHintTextColor(cSub)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                background = null
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                minLines = 3
                gravity = Gravity.TOP
                setPadding(dp(16), dp(14), dp(16), dp(14))
            })
        }

        section(R.string.sec_writing)
        card {
            switchRow(R.string.opt_autocorrect, R.string.opt_autocorrect_sub, prefs.autoCorrect) { prefs.autoCorrect = it }
            addView(divider())
            switchRow(R.string.opt_suggestions, null, prefs.suggestions) { prefs.suggestions = it }
            addView(divider())
            switchRow(R.string.opt_autolang, R.string.opt_autolang_sub, prefs.autoLanguage) { prefs.autoLanguage = it }
            addView(divider())
            switchRow(R.string.opt_autocap, null, prefs.autoCapitalize) { prefs.autoCapitalize = it }
            addView(divider())
            switchRow(R.string.opt_doublespace, null, prefs.doubleSpacePeriod) { prefs.doubleSpacePeriod = it }
        }

        section(R.string.sec_keys)
        card {
            switchRow(R.string.opt_swipedown, R.string.opt_swipedown_sub, prefs.swipeDownSymbols) { prefs.swipeDownSymbols = it }
            addView(divider())
            switchRow(R.string.opt_haptics, null, prefs.haptics) { prefs.haptics = it }
            addView(divider())
            switchRow(R.string.opt_sound, null, prefs.sound) { prefs.sound = it }
            addView(divider())
            addView(themeRow())
            addView(divider())
            addView(heightRow())
        }

        section(R.string.sec_shortcuts)
        content.addView(text(getString(R.string.shortcuts_hint), 13f, cSub).apply { setPadding(dp(4), 0, dp(4), dp(8)) })
        card {
            val box = LinearLayout(this@SettingsActivity).apply { orientation = LinearLayout.VERTICAL }
            shortcutsBox = box
            addView(box)
            addView(divider())
            addView(rowButton(getString(R.string.shortcut_add)) { editShortcut(null) })
        }

        section(R.string.sec_learned)
        card {
            val l = text("", 17f, cText).apply { setPadding(dp(16), dp(14), dp(16), dp(14)) }
            learnedLabel = l
            addView(l)
            addView(divider())
            addView(rowButton(getString(R.string.learned_clear), destructive = true) {
                prefs.learnedWords = emptySet()
                refreshLearned()
            })
        }
        content.addView(text(getString(R.string.credits), 12f, cSub).apply { setPadding(dp(4), dp(16), dp(4), 0) })
    }

    // ---------- bloques ----------

    private fun text(s: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        if (bold) typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }

    private fun section(res: Int) {
        content.addView(text(getString(res).uppercase(), 13f, cSub).apply { setPadding(dp(16), dp(22), 0, dp(6)) })
    }

    private fun card(body: LinearLayout.() -> Unit) {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(cCard); cornerRadius = dp(12).toFloat() }
            clipToOutline = true
        }
        c.body()
        content.addView(c, LinearLayout.LayoutParams(-1, -2))
    }

    private fun divider() = View(this).apply {
        setBackgroundColor(cLine)
        layoutParams = LinearLayout.LayoutParams(-1, 1).also { it.marginStart = dp(16) }
    }

    private fun LinearLayout.switchRow(title: Int, sub: Int?, checked: Boolean, onChange: (Boolean) -> Unit) {
        val row = LinearLayout(this@SettingsActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val col = LinearLayout(this@SettingsActivity).apply { orientation = LinearLayout.VERTICAL }
        col.addView(text(getString(title), 17f, cText))
        if (sub != null) col.addView(text(getString(sub), 13f, cSub).apply { setPadding(0, dp(2), 0, 0) })
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        val sw = Switch(this@SettingsActivity).apply {
            isChecked = checked
            setOnCheckedChangeListener { _: CompoundButton, v: Boolean -> onChange(v) }
        }
        row.addView(sw)
        row.setOnClickListener { sw.toggle() }
        addView(row)
    }

    private fun rowButton(label: String, destructive: Boolean = false, onClick: () -> Unit) =
        text(label, 17f, if (destructive) 0xFFFF3B30.toInt() else cAccent).apply {
            setPadding(dp(16), dp(14), dp(16), dp(14))
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun setupRow(title: Int, sub: Int, btn: Int, bind: (Button) -> Unit, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(text(getString(title), 17f, cText))
        col.addView(text(getString(sub), 13f, cSub).apply { setPadding(0, dp(2), 0, 0) })
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        val b = Button(this).apply {
            text = getString(btn)
            isAllCaps = false
            setOnClickListener { onClick() }
        }
        bind(b)
        row.addView(b)
        return row
    }

    private fun themeRow(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        row.addView(text(getString(R.string.opt_theme), 17f, cText))
        val seg = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
        val options = listOf("system" to R.string.theme_system, "light" to R.string.theme_light, "dark" to R.string.theme_dark)
        val buttons = ArrayList<Button>()
        fun paint() {
            options.forEachIndexed { i, (key, _) ->
                val sel = prefs.theme == key
                buttons[i].setTextColor(if (sel) Color.WHITE else cAccent)
                buttons[i].background = GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat()
                    setColor(if (sel) cAccent else if (dark) 0xFF2C2C2E.toInt() else 0xFFEFEFF4.toInt())
                }
            }
        }
        options.forEach { (key, label) ->
            val b = Button(this).apply {
                text = getString(label)
                isAllCaps = false
                stateListAnimator = null
                setOnClickListener { prefs.theme = key; paint() }
            }
            buttons.add(b)
            seg.addView(b, LinearLayout.LayoutParams(0, dp(40), 1f).also { it.marginEnd = dp(6) })
        }
        paint()
        row.addView(seg)
        return row
    }

    private fun heightRow(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val label = text(getString(R.string.opt_height, prefs.heightPercent), 17f, cText)
        row.addView(label)
        row.addView(SeekBar(this).apply {
            max = 50
            progress = prefs.heightPercent - 80
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    prefs.heightPercent = 80 + p
                    label.text = getString(R.string.opt_height, 80 + p)
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        })
        return row
    }

    // ---------- estado ----------

    private fun refreshSetup() {
        val imm = getSystemService(InputMethodManager::class.java)
        val enabled = imm.enabledInputMethodList.any { it.packageName == packageName }
        val selected = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.startsWith("$packageName/") == true
        enableBtn?.apply {
            text = getString(if (enabled) R.string.done_check else R.string.btn_open)
            isEnabled = !enabled
        }
        selectBtn?.apply {
            text = getString(if (selected) R.string.done_check else R.string.btn_choose)
            isEnabled = enabled && !selected
        }
    }

    private fun refreshLearned() {
        val n = prefs.learnedWords.size
        learnedLabel?.text = resources.getQuantityString(R.plurals.learned_count, n, n)
    }

    private fun refreshShortcuts() {
        val box = shortcutsBox ?: return
        box.removeAllViews()
        val list = prefs.shortcuts
        list.forEachIndexed { i, sc ->
            if (i > 0) box.addView(divider())
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(12))
                setOnClickListener { editShortcut(sc) }
            }
            row.addView(text(sc.trigger, 17f, cAccent, bold = true).apply { typeface = Typeface.MONOSPACE })
            row.addView(text(sc.expansion.replace("\n", " ⏎ "), 16f, cText).apply {
                setPadding(dp(16), 0, 0, 0)
                maxLines = 2
            }, LinearLayout.LayoutParams(0, -2, 1f))
            box.addView(row)
        }
    }

    private fun editShortcut(existing: Shortcut?) {
        val pad = dp(20)
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(8), pad, 0)
        }
        val trigger = EditText(this).apply {
            hint = getString(R.string.shortcut_trigger)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setText(existing?.trigger ?: "")
        }
        val expansion = EditText(this).apply {
            hint = getString(R.string.shortcut_text)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setText(existing?.expansion ?: "")
        }
        form.addView(trigger)
        form.addView(expansion)
        val b = AlertDialog.Builder(this)
            .setTitle(if (existing == null) R.string.shortcut_add else R.string.shortcut_edit)
            .setView(form)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
        if (existing != null) b.setNeutralButton(R.string.delete) { _, _ ->
            prefs.shortcuts = prefs.shortcuts.filter { it.trigger != existing.trigger }
            refreshShortcuts()
        }
        val dialog = b.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val t = trigger.text.toString()
                val e = expansion.text.toString()
                if (t.isBlank() || t.any { it.isWhitespace() } || e.isEmpty()) {
                    trigger.error = getString(R.string.shortcut_invalid)
                    return@setOnClickListener
                }
                val rest = prefs.shortcuts.filter { it.trigger != existing?.trigger && !it.trigger.equals(t, true) }
                val idx = prefs.shortcuts.indexOfFirst { it.trigger == existing?.trigger }
                prefs.shortcuts = if (existing != null && idx >= 0) {
                    rest.toMutableList().also { it.add(minOf(idx, it.size), Shortcut(t, e)) }
                } else rest + Shortcut(t, e)
                refreshShortcuts()
                dialog.dismiss()
            }
        }
        dialog.show()
    }
}
