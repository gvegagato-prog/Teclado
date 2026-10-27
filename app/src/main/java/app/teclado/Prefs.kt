package app.teclado

import android.content.Context
import android.content.SharedPreferences
import app.teclado.engine.Lang
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class Shortcut(val trigger: String, val expansion: String)

/** Ajustes del usuario, atajos de texto y palabras aprendidas. Todo local, en SharedPreferences. */
class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    var autoCorrect: Boolean
        get() = sp.getBoolean("autoCorrect", true)
        set(v) = sp.edit().putBoolean("autoCorrect", v).apply()
    var suggestions: Boolean
        get() = sp.getBoolean("suggestions", true)
        set(v) = sp.edit().putBoolean("suggestions", v).apply()
    var autoLanguage: Boolean
        get() = sp.getBoolean("autoLanguage", true)
        set(v) = sp.edit().putBoolean("autoLanguage", v).apply()
    var autoCapitalize: Boolean
        get() = sp.getBoolean("autoCapitalize", true)
        set(v) = sp.edit().putBoolean("autoCapitalize", v).apply()
    var doubleSpacePeriod: Boolean
        get() = sp.getBoolean("doubleSpacePeriod", true)
        set(v) = sp.edit().putBoolean("doubleSpacePeriod", v).apply()
    var swipeDownSymbols: Boolean
        get() = sp.getBoolean("swipeDownSymbols", true)
        set(v) = sp.edit().putBoolean("swipeDownSymbols", v).apply()
    var haptics: Boolean
        get() = sp.getBoolean("haptics", true)
        set(v) = sp.edit().putBoolean("haptics", v).apply()
    var sound: Boolean
        get() = sp.getBoolean("sound", false)
        set(v) = sp.edit().putBoolean("sound", v).apply()

    /** "system", "light" o "dark". */
    var theme: String
        get() = sp.getString("theme", "system") ?: "system"
        set(v) = sp.edit().putString("theme", v).apply()

    /** Altura de las teclas en %, 80..130. */
    var heightPercent: Int
        get() = sp.getInt("heightPercent", 100)
        set(v) = sp.edit().putInt("heightPercent", v.coerceIn(80, 130)).apply()

    /** Idioma por defecto antes de detectar nada: el del dispositivo. */
    val defaultLang: Lang
        get() = if (Locale.getDefault().language == "es") Lang.ES else Lang.EN

    // ---- atajos ----
    var shortcuts: List<Shortcut>
        get() {
            val raw = sp.getString("shortcuts", null) ?: return DEFAULT_SHORTCUTS
            return try {
                val arr = JSONArray(raw)
                (0 until arr.length()).map { arr.getJSONObject(it) }
                    .map { Shortcut(it.getString("t"), it.getString("e")) }
            } catch (_: Exception) {
                DEFAULT_SHORTCUTS
            }
        }
        set(list) {
            val arr = JSONArray()
            list.forEach { arr.put(JSONObject().put("t", it.trigger).put("e", it.expansion)) }
            sp.edit().putString("shortcuts", arr.toString()).apply()
        }

    // ---- palabras aprendidas ----
    var learnedWords: Set<String>
        get() = sp.getStringSet("learned", emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet("learned", HashSet(v)).apply()

    fun learn(word: String) {
        learnedWords = learnedWords + word.lowercase()
    }

    fun registerListener(l: SharedPreferences.OnSharedPreferenceChangeListener) =
        sp.registerOnSharedPreferenceChangeListener(l)

    fun unregisterListener(l: SharedPreferences.OnSharedPreferenceChangeListener) =
        sp.unregisterOnSharedPreferenceChangeListener(l)

    companion object {
        const val NAME = "teclado"
        val DEFAULT_SHORTCUTS = listOf(Shortcut("@@@", "gonvegato@gmail.com"))

        /** Atajo cuyo disparador coincide (sin distinguir mayúsculas) con [token]. */
        fun match(list: List<Shortcut>, token: String): Shortcut? {
            if (token.isEmpty()) return null
            return list.firstOrNull { it.trigger.equals(token, ignoreCase = true) }
        }
    }
}
