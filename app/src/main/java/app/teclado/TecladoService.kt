package app.teclado

import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import app.teclado.engine.Lang
import app.teclado.engine.LanguageDetector
import app.teclado.engine.Lexicon
import app.teclado.engine.SpellEngine

open class TecladoService : InputMethodService(), KeyboardListener {

    private enum class Kind { LITERAL, CORRECTION, COMPLETION, SHORTCUT }
    private class Sugg(val text: String, val kind: Kind)
    private class Correction(val original: String, val corrected: String, val suffix: String)

    private lateinit var prefs: Prefs
    private var kb: KeyboardView? = null
    private val ui = Handler(Looper.getMainLooper())

    @Volatile private var engine: SpellEngine? = null
    internal val engineReady get() = engine != null
    internal val language get() = currentLang()
    internal val shiftState get() = shift
    @Volatile private var detector: LanguageDetector? = null
    private var lexicons: Map<Lang, Lexicon> = emptyMap()

    // estado de edición
    private var layer = Layer.LETTERS
    private var shift = Shift.OFF
    private var shiftTapTime = 0L
    private val token = StringBuilder()
    private var tokenStale = false
    private var cursor = -1
    private var lastAC: Correction? = null
    private var suppressWord: String? = null
    private var pendingAutoSpace = false
    private var doubleSpaceArmed = false
    private var lastSpaceTime = 0L
    private var shortcuts: List<Shortcut> = emptyList()
    private val current = arrayOfNulls<Sugg>(3)

    // configuración del campo actual
    private var noSuggest = false
    private var keepSymbols = false
    private var inputType = 0
    private var imeAction = EditorInfo.IME_ACTION_NONE
    private var noEnterAction = false

    // idioma
    private var manualLang: Lang? = null
    private var lastLangShown: Lang? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        Thread({
            try {
                val loaded = HashMap<Lang, Lexicon>()
                for (l in Lang.values()) {
                    assets.open("dict/${l.code}.tsv").bufferedReader().use { loaded[l] = Lexicon.parse(l, it) }
                }
                lexicons = loaded
                val eng = SpellEngine(loaded)
                eng.userWords.addAll(prefs.learnedWords)
                detector = LanguageDetector(loaded[Lang.EN], loaded[Lang.ES], prefs.defaultLang)
                engine = eng
                ui.post { refreshSuggestions(); updateSpaceLabel() }
            } catch (_: Exception) {
                // sin diccionario el teclado sigue funcionando, solo sin corrección
            }
        }, "teclado-dict").start()
    }

    override fun onCreateInputView(): View {
        val v = KeyboardView(this)
        v.listener = this
        kb = v
        applyPrefs()
        return v
    }

    override fun onEvaluateFullscreenMode() = false

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyPrefs()
    }

    private fun isDark(): Boolean = when (prefs.theme) {
        "dark" -> true
        "light" -> false
        else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    private fun applyPrefs() {
        val v = kb ?: return
        v.theme = KbTheme(isDark())
        v.heightPercent = prefs.heightPercent
        v.swipeDownEnabled = prefs.swipeDownSymbols
        v.hapticsEnabled = prefs.haptics
        v.soundEnabled = prefs.sound
        shortcuts = prefs.shortcuts
        engine?.let { e ->
            e.userWords.clear()
            e.userWords.addAll(prefs.learnedWords)
        }
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        applyPrefs()
        inputType = info.inputType
        val cls = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        keepSymbols = cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE ||
            cls == InputType.TYPE_CLASS_DATETIME
        noSuggest = cls != InputType.TYPE_CLASS_TEXT ||
            variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS ||
            variation == InputType.TYPE_TEXT_VARIATION_URI ||
            (inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0
        imeAction = info.imeOptions and EditorInfo.IME_MASK_ACTION
        noEnterAction = (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0

        layer = if (keepSymbols) Layer.SYM1 else Layer.LETTERS
        kb?.layer = layer
        kb?.enterLabel = enterLabelFor()
        lastAC = null
        suppressWord = null
        pendingAutoSpace = false
        doubleSpaceArmed = false
        cursor = info.initialSelStart
        resync()
        if (!noSuggest) {
            val before = currentInputConnection?.getTextBeforeCursor(140, 0)
            val det = detector
            if (det != null && !before.isNullOrBlank()) det.seed(before, det.current)
        }
        updateShiftFromContext()
        updateSpaceLabel()
        refreshSuggestions()
    }

    override fun onFinishInput() {
        super.onFinishInput()
        ui.removeCallbacks(resyncRunnable)
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        if (newSelStart != newSelEnd || newSelStart != cursor) {
            cursor = newSelStart
            lastAC = null
            tokenStale = true
            scheduleResync()
        }
    }

    // ---------- helpers de edición ----------

    private val resyncRunnable = Runnable {
        resync()
        updateShiftFromContext()
        refreshSuggestions()
    }

    private fun scheduleResync() {
        ui.removeCallbacks(resyncRunnable)
        ui.postDelayed(resyncRunnable, 70L)
    }

    private fun resync() {
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(60, 0)?.toString() ?: ""
        token.setLength(0)
        token.append(before.takeLastWhile { !it.isWhitespace() })
        tokenStale = false
    }

    private fun ensureToken() {
        if (tokenStale) {
            ui.removeCallbacks(resyncRunnable)
            resync()
        }
    }

    private fun updateShiftFromContext() {
        if (!prefs.autoCapitalize) {
            if (shift == Shift.ONCE) shift = Shift.OFF
            kb?.shift = shift
            return
        }
        val caps = currentInputConnection?.getCursorCapsMode(inputType) ?: 0
        shift = when {
            caps and android.text.TextUtils.CAP_MODE_CHARACTERS != 0 -> Shift.LOCK
            caps != 0 -> Shift.ONCE
            shift == Shift.LOCK -> Shift.LOCK
            else -> Shift.OFF
        }
        kb?.shift = shift
    }

    private fun currentLang(): Lang = manualLang
        ?: if (prefs.autoLanguage) detector?.current ?: prefs.defaultLang else prefs.defaultLang

    private fun updateSpaceLabel() {
        val l = currentLang()
        kb?.spaceLabel = l.label
        kb?.spaceLabelAccent = manualLang != null
        lastLangShown = l
    }

    private fun observeWord(word: String) {
        if (manualLang == null && prefs.autoLanguage) {
            detector?.observe(word)
            if (detector?.current != lastLangShown) updateSpaceLabel()
        }
    }

    private fun enterLabelFor(): String? {
        if (noEnterAction) return null
        return when (imeAction) {
            EditorInfo.IME_ACTION_GO -> getString(R.string.enter_go)
            EditorInfo.IME_ACTION_SEARCH -> getString(R.string.enter_search)
            EditorInfo.IME_ACTION_SEND -> getString(R.string.enter_send)
            EditorInfo.IME_ACTION_NEXT -> getString(R.string.enter_next)
            EditorInfo.IME_ACTION_DONE -> getString(R.string.enter_done)
            else -> null
        }
    }

    /** Palabra que termina en el cursor y es candidata a corregirse (vacía si es una URL, un correo, etc.). */
    private fun wordRun(): String {
        val t = token
        if (t.isEmpty() || !t[t.length - 1].isLetter()) return ""
        var i = t.length
        while (i > 0 && (t[i - 1].isLetter() || (t[i - 1] == '\'' && i > 1 && t[i - 2].isLetter()))) i--
        if (i > 0 && t[i - 1] !in BOUNDARY) return ""
        return t.substring(i)
    }

    private fun shortcutFor(): Shortcut? = Prefs.match(shortcuts, token.toString())

    // ---------- teclas ----------

    override fun onChar(text: String) {
        val ic = currentInputConnection ?: return
        ensureToken()
        doubleSpaceArmed = false
        val c = text[0]
        val isWordChar = text.length == 1 && (c.isLetter() || (c == '\'' && token.isNotEmpty() && token.last().isLetter()))
        if (isWordChar) {
            lastAC = null
            pendingAutoSpace = false
            ic.commitText(text, 1)
            cursor += text.length
            token.append(text)
        } else {
            ic.beginBatchEdit()
            if (pendingAutoSpace && text.length == 1 && c in ".,;:!?)") {
                ic.deleteSurroundingText(1, 0)
                cursor--
            }
            pendingAutoSpace = false
            var ac: Correction? = null
            if (text.length == 1 && c in ".,;:!?") {
                val run = wordRun()
                ac = applyAutocorrect(ic, text)
                if (run.isNotEmpty()) observeWord(ac?.corrected ?: run)
            }
            ic.commitText(text, 1)
            cursor += text.length
            token.append(text)
            ic.endBatchEdit()
            lastAC = ac
            if (layer == Layer.LETTERS && c in ".!?") updateShiftFromContext()
        }
        if (shift == Shift.ONCE) {
            shift = Shift.OFF
            kb?.shift = shift
        }
        refreshSuggestions()
    }

    override fun onSpecial(type: KeyType) {
        when (type) {
            KeyType.SHIFT -> {
                val now = SystemClock.uptimeMillis()
                shift = when {
                    shift == Shift.LOCK -> Shift.OFF
                    shift == Shift.ONCE && now - shiftTapTime < 350 -> Shift.LOCK
                    shift == Shift.ONCE -> Shift.OFF
                    else -> Shift.ONCE
                }
                shiftTapTime = now
                kb?.shift = shift
            }
            KeyType.DELETE -> handleDelete(true)
            KeyType.SYM1 -> setLayer(Layer.SYM1)
            KeyType.SYM2 -> setLayer(Layer.SYM2)
            KeyType.ABC -> { setLayer(Layer.LETTERS); updateShiftFromContext() }
            KeyType.LANG -> cycleLanguage()
            KeyType.SPACE -> onSpace()
            KeyType.ENTER -> onEnter()
            else -> {}
        }
    }

    private fun setLayer(l: Layer) {
        layer = l
        kb?.layer = l
    }

    private fun cycleLanguage() {
        manualLang = when (manualLang) {
            null -> Lang.ES
            Lang.ES -> Lang.EN
            Lang.EN -> null
        }
        updateSpaceLabel()
        val v = kb ?: return
        v.spaceLabel = when (manualLang) {
            null -> getString(R.string.lang_auto, currentLang().label)
            else -> getString(R.string.lang_fixed, manualLang!!.label)
        }
        v.spaceLabelAccent = true
        ui.removeCallbacks(restoreLabel)
        ui.postDelayed(restoreLabel, 1300L)
        refreshSuggestions()
    }

    private val restoreLabel = Runnable { updateSpaceLabel() }

    override fun onLangLongPress() {
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)?.showInputMethodPicker()
    }

    private fun onSpace() {
        val ic = currentInputConnection ?: return
        ensureToken()
        val now = SystemClock.uptimeMillis()
        val sc = shortcutFor()
        var ac: Correction? = null
        ic.beginBatchEdit()
        if (sc != null) {
            ic.deleteSurroundingText(token.length, 0)
            ic.commitText(sc.expansion + " ", 1)
            cursor += sc.expansion.length + 1 - token.length
            doubleSpaceArmed = false
        } else if (prefs.doubleSpacePeriod && doubleSpaceArmed && token.isEmpty() && now - lastSpaceTime < 450) {
            ic.deleteSurroundingText(1, 0)
            ic.commitText(". ", 1)
            cursor += 1
            doubleSpaceArmed = false
        } else {
            val run = wordRun()
            ac = applyAutocorrect(ic, " ")
            if (run.isNotEmpty()) observeWord(ac?.corrected ?: run)
            doubleSpaceArmed = token.isNotEmpty() && token.last().let { it.isLetterOrDigit() || it == ')' }
            ic.commitText(" ", 1)
            cursor += 1
        }
        ic.endBatchEdit()
        token.setLength(0)
        lastAC = ac
        pendingAutoSpace = false
        lastSpaceTime = now
        if (layer != Layer.LETTERS && !keepSymbols) setLayer(Layer.LETTERS)
        updateShiftFromContext()
        refreshSuggestions()
    }

    private fun onEnter() {
        val ic = currentInputConnection ?: return
        ensureToken()
        val sc = shortcutFor()
        ic.beginBatchEdit()
        if (sc != null) {
            ic.deleteSurroundingText(token.length, 0)
            ic.commitText(sc.expansion, 1)
        } else {
            val run = wordRun()
            val ac = applyAutocorrect(ic, "")
            if (run.isNotEmpty()) observeWord(ac?.corrected ?: run)
        }
        ic.endBatchEdit()
        token.setLength(0)
        lastAC = null
        pendingAutoSpace = false
        doubleSpaceArmed = false
        if (!noEnterAction && imeAction != EditorInfo.IME_ACTION_NONE && imeAction != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(imeAction)
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
            cursor++
        }
        updateShiftFromContext()
        refreshSuggestions()
    }

    override fun onDeleteRepeat() = handleDelete(false)

    private fun handleDelete(first: Boolean) {
        val ic = currentInputConnection ?: return
        doubleSpaceArmed = false
        pendingAutoSpace = false
        val ac = lastAC
        if (ac != null && first) {
            ic.beginBatchEdit()
            ic.deleteSurroundingText(ac.corrected.length + ac.suffix.length, 0)
            ic.commitText(ac.original, 1)
            ic.endBatchEdit()
            cursor += ac.original.length - ac.corrected.length - ac.suffix.length
            token.setLength(0)
            token.append(ac.original)
            suppressWord = ac.original.lowercase()
            lastAC = null
            refreshSuggestions()
            return
        }
        lastAC = null
        sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        if (cursor > 0) cursor--
        if (!tokenStale && token.isNotEmpty()) token.setLength(token.length - 1) else tokenStale = true
        if (first) {
            ensureToken()
            if (token.isEmpty()) updateShiftFromContext()
            refreshSuggestions()
        } else scheduleResync()
    }

    override fun onCursorMove(delta: Int) {
        sendDownUpKeyEvents(if (delta > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT)
        lastAC = null
        tokenStale = true
        scheduleResync()
    }

    // ---------- corrección y sugerencias ----------

    /** Corrige la palabra anterior al cursor si procede. [suffix] es lo que se va a escribir justo después. */
    private fun applyAutocorrect(ic: InputConnection, suffix: String): Correction? {
        val eng = engine ?: return null
        if (!prefs.autoCorrect || noSuggest) return null
        val run = wordRun()
        if (run.isEmpty()) return null
        val lower = run.lowercase()
        if (suppressWord != null) {
            val sup = suppressWord
            suppressWord = null
            if (sup == lower) {
                // el usuario insistió en esta palabra tras revertir la corrección: se aprende
                eng.userWords.add(lower)
                prefs.learn(lower)
                return null
            }
        }
        val lang = currentLang()
        val fixed: String = if (lang == Lang.EN && run == "i") "I" else {
            // CamelCase o siglas mezcladas: no tocar
            if (run.drop(1).any { it.isUpperCase() } && run.any { it.isLowerCase() }) return null
            val c = eng.correction(lower, lang) ?: return null
            SpellEngine.matchCase(run, c)
        }
        if (fixed == run) return null
        ic.deleteSurroundingText(run.length, 0)
        ic.commitText(fixed, 1)
        cursor += fixed.length - run.length
        token.setLength(token.length - run.length)
        token.append(fixed)
        return Correction(run, fixed, suffix)
    }

    private fun refreshSuggestions() {
        val v = kb ?: return
        java.util.Arrays.fill(current, null)
        if (!prefs.suggestions) { v.setSuggestions(emptyList()); return }
        val tok = token.toString()
        val sc = Prefs.match(shortcuts, tok)
        val eng = engine
        val run = if (noSuggest) "" else wordRun()
        val primary = ArrayList<Sugg>()
        var literal: Sugg? = null
        if (sc != null) primary.add(Sugg(sc.expansion, Kind.SHORTCUT))
        if (run.isNotEmpty() && eng != null) {
            val lang = currentLang()
            val lower = run.lowercase()
            val lex = lexicons[lang]
            val known = eng.isKnown(lower, lang)
            if (!known) {
                literal = Sugg(run, Kind.LITERAL)
                eng.suggest(lower, lang, 3).forEach {
                    primary.add(Sugg(SpellEngine.matchCase(run, it.word), Kind.CORRECTION))
                }
            } else primary.add(Sugg(run, Kind.COMPLETION))
            lex?.completions(lower, 4)?.forEach { w ->
                val s = SpellEngine.matchCase(run, w)
                if (primary.none { it.text.equals(s, true) } && !w.equals(lower, true)) primary.add(Sugg(s, Kind.COMPLETION))
            }
        }
        if (literal != null) {
            current[0] = literal
            current[1] = primary.getOrNull(0)
            current[2] = primary.getOrNull(1)
        } else {
            current[1] = primary.getOrNull(0)
            current[0] = primary.getOrNull(1)
            current[2] = primary.getOrNull(2)
        }
        v.setSuggestions(current.mapIndexed { i, s ->
            when {
                s == null -> SuggestionItem("")
                s.kind == Kind.LITERAL -> SuggestionItem("\"${s.text}\"")
                else -> SuggestionItem(s.text, emphasized = i == 1 && literal != null || s.kind == Kind.SHORTCUT)
            }
        }.let { items -> if (items.all { it.label.isEmpty() }) emptyList() else items })
    }

    override fun onSuggestion(slot: Int) {
        val s = current.getOrNull(slot) ?: return
        val ic = currentInputConnection ?: return
        ensureToken()
        val eng = engine
        ic.beginBatchEdit()
        when (s.kind) {
            Kind.SHORTCUT -> {
                ic.deleteSurroundingText(token.length, 0)
                ic.commitText(s.text + " ", 1)
                cursor += s.text.length + 1 - token.length
            }
            Kind.LITERAL -> {
                val lower = s.text.lowercase()
                eng?.userWords?.add(lower)
                prefs.learn(lower)
                observeWord(s.text)
                ic.commitText(" ", 1)
                cursor++
            }
            else -> {
                val run = wordRun()
                ic.deleteSurroundingText(run.length, 0)
                ic.commitText(s.text + " ", 1)
                cursor += s.text.length + 1 - run.length
                observeWord(s.text)
            }
        }
        ic.endBatchEdit()
        token.setLength(0)
        lastAC = null
        suppressWord = null
        pendingAutoSpace = true
        doubleSpaceArmed = false
        updateShiftFromContext()
        refreshSuggestions()
    }

    companion object {
        private const val BOUNDARY = "([{\"“«¿¡-"
    }
}
