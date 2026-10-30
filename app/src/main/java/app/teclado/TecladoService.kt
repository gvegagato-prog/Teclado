package app.teclado

import android.content.ClipboardManager
import android.content.Context
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

    private enum class Kind { LITERAL, CORRECTION, COMPLETION, SHORTCUT, REVERT }
    private class Sugg(val text: String, val kind: Kind)
    private class Correction(val original: String, val corrected: String, val suffix: String)

    private lateinit var prefs: Prefs
    private var kb: KeyboardView? = null
    private val ui = Handler(Looper.getMainLooper())

    @Volatile private var engine: SpellEngine? = null
    internal val engineReady get() = engine != null
    internal val language get() = currentLang()
    internal val shiftState get() = shift
    internal val layerState get() = layer
    internal val clipHistory get() = clipStore.items
    internal val clipPinned get() = clipStore.pinned
    internal val splitGapState get() = kb?.splitGap ?: -1f
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

    private var lastLangShown: Lang? = null

    private lateinit var clipStore: ClipStore
    private var clipEpochSeen = 0
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { captureClip() }

    private fun clipboard() = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    /** Guarda el texto copiado. Solo el teclado activo puede leer el portapapeles en segundo plano (Android 10+). */
    private fun captureClip() {
        if (!prefs.clipboardHistory) return
        try {
            val clip = clipboard().primaryClip ?: return
            if (clip.itemCount == 0 || !clip.description.hasMimeType("text/*")) return
            // Los gestores de contraseñas marcan lo que copian como sensible: no se guarda.
            if (clip.description.extras?.getBoolean(SENSITIVE_EXTRA, false) == true) return
            val item = clip.getItemAt(0)
            if (item.text == null && item.uri != null) return
            val text = item.coerceToText(this)?.toString() ?: return
            if (clipStore.add(text)) pushClips()
        } catch (_: Exception) {
            // sin acceso al portapapeles (p. ej. la app no es el teclado activo): se ignora
        }
    }

    private fun pasteText(text: String) {
        val ic = currentInputConnection ?: return
        ic.commitText(text, 1)
        cursor += text.length
        lastAC = null
        pendingAutoSpace = false
        doubleSpaceArmed = false
        resync()
        updateShiftFromContext()
        refreshSuggestions()
    }

    override fun onPasteLatest() {
        val clip = try { clipboard().primaryClip } catch (_: Exception) { null }
        val sys = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        val text = sys?.takeIf { it.isNotEmpty() } ?: clipStore.items.firstOrNull() ?: return
        pasteText(text)
    }

    private fun pushClips() {
        kb?.setClips(clipStore.pinned.map { ClipEntry(it.text, it.title) }, clipStore.items)
    }

    override fun onClipboardOpened() = pushClips()

    override fun onClipboardAction(action: ClipAction, pinned: Boolean, index: Int) {
        when (action) {
            ClipAction.PICK -> {
                val t = (if (pinned) clipStore.pinned.getOrNull(index)?.text else clipStore.items.getOrNull(index)) ?: return
                kb?.closeClipboard()
                pasteText(t)
            }
            ClipAction.DELETE -> { clipStore.remove(index); pushClips() }
            ClipAction.UNPIN -> { clipStore.unpin(index); pushClips() }
            ClipAction.PIN -> if (clipStore.pin(index)) { pushClips(); startTitleEdit(0) }
            ClipAction.EDIT_TITLE -> startTitleEdit(index)
        }
    }

    override fun onClipboardClear() {
        clipStore.clear()
        pushClips()
    }

    // ---- títulos de las copias fijadas: se escriben con el propio teclado, en la barra superior ----
    private var titleIdx = -1
    private val titleBuf = StringBuilder()
    private fun editingTitle() = titleIdx >= 0

    private fun startTitleEdit(index: Int) {
        val p = clipStore.pinned.getOrNull(index) ?: return
        titleIdx = index
        titleBuf.setLength(0)
        titleBuf.append(p.title)
        kb?.closeClipboard()
        kb?.titleEdit = titleBuf.toString()
        if (titleBuf.isEmpty()) { shift = Shift.ONCE; kb?.shift = shift }
    }

    private fun titleAppend(t: String) {
        if (titleBuf.length + t.length <= ClipStore.MAX_TITLE) titleBuf.append(t)
        kb?.titleEdit = titleBuf.toString()
        if (shift == Shift.ONCE) { shift = Shift.OFF; kb?.shift = shift }
    }

    private fun titleDelete() {
        if (titleBuf.isNotEmpty()) titleBuf.setLength(titleBuf.length - 1)
        kb?.titleEdit = titleBuf.toString()
    }

    private fun endTitleEdit() {
        titleIdx = -1
        kb?.titleEdit = null
        pushClips()
        kb?.openClipboard()
    }

    override fun onTitleOk() {
        if (!editingTitle()) return
        clipStore.setTitle(titleIdx, titleBuf.toString())
        endTitleEdit()
    }

    override fun onTitleCancel() {
        if (editingTitle()) endTitleEdit()
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        clipStore = ClipStore(ClipStore.fileFor(filesDir))
        clipStore.load()
        clipEpochSeen = prefs.clipEpoch
        try { clipboard().addPrimaryClipChangedListener(clipListener) } catch (_: Exception) {}
        Thread({
            try {
                val loaded = HashMap<Lang, Lexicon>()
                for (l in Lang.values()) {
                    loaded[l] = Lexicon.parse(l, assets.open("dict/${l.code}.tsv").use { it.readBytes() }.toString(Charsets.UTF_8))
                }
                lexicons = loaded
                val eng = SpellEngine(loaded)
                eng.userWords.addAll(prefs.learnedWords)
                detector = LanguageDetector(loaded[Lang.EN], loaded[Lang.ES], prefs.defaultLang)
                engine = eng
                ui.post { refreshSuggestions(); updateSpaceLabel() }
                // índice de errores dobles: tarda un poco más, el teclado ya funciona mientras tanto
                for (lex in loaded.values) lex.buildD2Index()
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

    private fun systemDark(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private fun applyPrefs() {
        val v = kb ?: return
        v.theme = KbTheme.byName(prefs.theme, systemDark())
        v.bottomMarginDp = prefs.bottomMargin
        val cfg = resources.configuration
        // Tablet = el lado corto de la pantalla mide >= 500 dp (un móvil, ni girado, llega a eso)
        val tablet = cfg.smallestScreenWidthDp >= 500
        val split = when (prefs.splitMode) {
            "always" -> true
            "tablet" -> tablet
            else -> false
        }
        v.splitGap = if (split) prefs.splitGap / 100f else 0f
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
        if (prefs.clipEpoch != clipEpochSeen) {
            clipEpochSeen = prefs.clipEpoch
            clipStore.clear()
        }
        titleIdx = -1
        kb?.titleEdit = null
        kb?.closeClipboard()
        pushClips()
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

        layer = when {
            cls == InputType.TYPE_CLASS_NUMBER -> {
                kb?.setNumpad(Layouts.numpad(PadKind.NUMBER,
                    signed = (inputType and InputType.TYPE_NUMBER_FLAG_SIGNED) != 0,
                    decimal = (inputType and InputType.TYPE_NUMBER_FLAG_DECIMAL) != 0))
                Layer.NUMPAD
            }
            cls == InputType.TYPE_CLASS_PHONE -> {
                kb?.setNumpad(Layouts.numpad(PadKind.PHONE, signed = false, decimal = false)); Layer.NUMPAD
            }
            cls == InputType.TYPE_CLASS_DATETIME -> {
                kb?.setNumpad(Layouts.numpad(PadKind.DATETIME, signed = false, decimal = false)); Layer.NUMPAD
            }
            else -> Layer.LETTERS
        }
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

    override fun onDestroy() {
        try { clipboard().removePrimaryClipChangedListener(clipListener) } catch (_: Exception) {}
        worker.looper.quitSafely()
        super.onDestroy()
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

    private fun currentLang(): Lang = detector?.current ?: prefs.defaultLang

    private fun updateSpaceLabel() {
        val l = currentLang()
        kb?.spaceLabel = l.label
        lastLangShown = l
    }

    private fun observeWord(word: String) {
        // una corrección que parte una palabra pegada ("hola que") aporta cada parte por separado
        for (part in word.split(' ')) if (part.isNotEmpty()) detector?.observe(part)
        if (detector?.current != lastLangShown) updateSpaceLabel()
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
        if (editingTitle()) { titleAppend(text); return }
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
        if (editingTitle()) {
            when (type) {
                KeyType.SPACE -> { titleAppend(" "); return }
                KeyType.DELETE -> { titleDelete(); return }
                KeyType.ENTER -> { onTitleOk(); return }
                else -> {}
            }
        }
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
            KeyType.SPACE -> onSpace()
            KeyType.ENTER -> onEnter()
            else -> {}
        }
    }

    private fun setLayer(l: Layer) {
        layer = l
        kb?.layer = l
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
        if (editingTitle()) { titleDelete(); return }
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
        if (first) deleteRepeats = 0 else deleteRepeats++
        if (deleteRepeats > WORD_DELETE_AFTER) {
            // mantenido un rato: borra palabra a palabra, como en iOS
            val before = ic.getTextBeforeCursor(64, 0)?.toString() ?: ""
            if (before.isNotEmpty()) {
                var n = before.length
                while (n > 0 && before[n - 1].isWhitespace()) n--
                while (n > 0 && !before[n - 1].isWhitespace()) n--
                val del = (before.length - n).coerceAtLeast(1)
                ic.deleteSurroundingText(del, 0)
                cursor = (cursor - del).coerceAtLeast(0)
            }
            tokenStale = true
            scheduleResync()
            return
        }
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
            val c = eng.correction(lower, lang, detector?.confident ?: false)
            if (c == null) {
                noteKeptWord(eng, lower)
                return null
            }
            val before = ic.getTextBeforeCursor(400, 0)?.toString() ?: ""
            val ctx = if (before.endsWith(run)) before.dropLast(run.length) else before
            // Nombre propio: mayúscula a mitad de frase -> no se toca
            if (run[0].isUpperCase() && !sentenceStart(ctx)) return null
            // Ya la escribiste antes en este texto y la dejaste así -> es intencionada
            if (Regex("(?<![\\p{L}'])" + Regex.escape(lower) + "(?![\\p{L}'])").containsMatchIn(ctx.lowercase())) {
                noteKeptWord(eng, lower)
                return null
            }
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

    private var deleteRepeats = 0
    private val keptCounts = HashMap<String, Int>()

    /** Palabra desconocida que el usuario deja tal cual: a la segunda vez se aprende. */
    private fun noteKeptWord(eng: SpellEngine, lower: String) {
        if (lower.length < 3 || eng.isKnown(lower)) return
        val n = (keptCounts[lower] ?: 0) + 1
        keptCounts[lower] = n
        if (n >= 2) {
            eng.userWords.add(lower)
            prefs.learn(lower)
            keptCounts.remove(lower)
        }
    }

    private fun sentenceStart(ctx: String): Boolean {
        val t = ctx.trimEnd { it == ' ' || it == '¿' || it == '¡' || it == '"' || it == '(' || it == '“' }
        return t.isEmpty() || t.last() in ".!?\n…"
    }

    private val worker by lazy {
        val t = android.os.HandlerThread("teclado-sugerencias")
        t.start()
        Handler(t.looper)
    }
    private var suggSeq = 0

    private fun refreshSuggestions() {
        if (editingTitle()) return
        val v = kb ?: return
        suggSeq++
        java.util.Arrays.fill(current, null)
        if (!prefs.suggestions) { v.setSuggestions(emptyList()); return }
        val tok = token.toString()
        val sc = Prefs.match(shortcuts, tok)
        val eng = engine
        val run = if (noSuggest) "" else wordRun()
        val primary = ArrayList<Sugg>()
        if (sc != null) primary.add(Sugg(sc.expansion, Kind.SHORTCUT))
        val ac = lastAC
        if (ac != null && tok.isEmpty() && sc == null) {
            current[1] = Sugg(ac.original, Kind.REVERT)
            v.setSuggestions(listOf(SuggestionItem(""), SuggestionItem("↶ " + ac.original), SuggestionItem("")))
            return
        }
        if (run.isEmpty() || eng == null) {
            showSlots(primary, null)
            return
        }
        // El cálculo de candidatas va en otro hilo para no frenar el tecleo; solo se muestra si sigue vigente.
        val seq = ++suggSeq
        val lang = currentLang()
        worker.post {
            val list = ArrayList(primary)
            var lit: Sugg? = null
            val lower = run.lowercase()
            if (!eng.isKnown(lower)) {
                lit = Sugg(run, Kind.LITERAL)
                eng.suggest(lower, lang, 3).forEach { list.add(Sugg(SpellEngine.matchCase(run, it.word), Kind.CORRECTION)) }
            } else list.add(Sugg(run, Kind.COMPLETION))
            lexicons[lang]?.completions(lower, 4)?.forEach { w ->
                val s = SpellEngine.matchCase(run, w)
                if (list.none { it.text.equals(s, true) } && !w.equals(lower, true)) list.add(Sugg(s, Kind.COMPLETION))
            }
            ui.post { if (seq == suggSeq) showSlots(list, lit) }
        }
    }

    private fun showSlots(primary: List<Sugg>, literal: Sugg?) {
        val v = kb ?: return
        java.util.Arrays.fill(current, null)
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
            Kind.REVERT -> {
                val ac = lastAC
                if (ac != null) {
                    ic.deleteSurroundingText(ac.corrected.length + ac.suffix.length, 0)
                    ic.commitText(ac.original + ac.suffix, 1)
                    cursor += ac.original.length - ac.corrected.length
                    eng?.userWords?.add(ac.original.lowercase())
                    prefs.learn(ac.original)
                }
                ic.endBatchEdit()
                lastAC = null
                refreshSuggestions()
                return
            }
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
        private const val SENSITIVE_EXTRA = "android.content.extra.IS_SENSITIVE"
        private const val WORD_DELETE_AFTER = 14
        private const val BOUNDARY = "([{\"“«¿¡-"
    }
}
