package app.teclado

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * Portapapeles del teclado.
 *  - Historial: las últimas [max] copias de texto, sin caducidad, la más reciente primero.
 *  - Fijadas: copias guardadas aparte, con título opcional. No cuentan para el límite del historial
 *    ni se borran con «Borrar todo».
 * Se guarda en el almacenamiento privado de la app (fuera de las copias de seguridad, ver
 * data_extraction_rules.xml).
 */
class ClipStore(private val file: File?, val max: Int = 50, val maxPinned: Int = 100) {
    class Pinned(val text: String, val title: String)

    private val list = ArrayList<String>()
    private val pins = ArrayList<Pinned>()
    val items: List<String> get() = list
    val pinned: List<Pinned> get() = pins

    /** Cuándo se editaron por última vez las fijadas (0 = nunca). Gana la edición más reciente al
     *  sincronizarlas con los demás teclados de la familia Pera. */
    var pinsTs = 0L
        private set

    private val io by lazy { Executors.newSingleThreadExecutor { r -> Thread(r, "teclado-clip").apply { isDaemon = true } } }

    /** Recarga desde disco. Acepta también el formato anterior (una lista simple de textos). */
    fun load() {
        list.clear()
        pins.clear()
        pinsTs = 0
        val f = file ?: return
        try {
            if (!f.exists()) return
            val raw = f.readText().trim()
            if (raw.startsWith("[")) {
                val arr = JSONArray(raw)
                for (i in 0 until minOf(arr.length(), max)) list.add(arr.getString(i))
            } else {
                val o = JSONObject(raw)
                o.optJSONArray("history")?.let { a -> for (i in 0 until minOf(a.length(), max)) list.add(a.getString(i)) }
                pinsTs = o.optLong("pinnedTs", 0)
                o.optJSONArray("pinned")?.let { a ->
                    for (i in 0 until minOf(a.length(), maxPinned)) {
                        val p = a.getJSONObject(i)
                        pins.add(Pinned(p.getString("x"), p.optString("t", "")))
                    }
                }
            }
        } catch (_: Exception) {
            list.clear()
            pins.clear()
        }
    }

    /** true si cambió algo. Ignora texto vacío, demasiado largo o ya fijado. */
    fun add(text: String): Boolean {
        if (text.isBlank() || text.length > MAX_CHARS) return false
        if (pins.any { it.text == text }) return false
        if (list.firstOrNull() == text) return false
        list.remove(text)
        list.add(0, text)
        while (list.size > max) list.removeAt(list.size - 1)
        save()
        return true
    }

    fun remove(index: Int) {
        if (index in list.indices) { list.removeAt(index); save() }
    }

    /** Vacía el historial; las fijadas se conservan. */
    fun clear() {
        list.clear()
        save()
    }

    /** Pasa la copia [index] del historial a fijadas (arriba del todo). */
    fun pin(index: Int): Boolean {
        val t = list.getOrNull(index) ?: return false
        if (pins.size >= maxPinned) return false
        list.removeAt(index)
        pins.add(0, Pinned(t, ""))
        touchPins()
        save()
        return true
    }

    /** Devuelve la fijada [index] al historial (arriba); pierde el título. */
    fun unpin(index: Int) {
        val p = pins.getOrNull(index) ?: return
        pins.removeAt(index)
        list.remove(p.text)
        list.add(0, p.text)
        while (list.size > max) list.removeAt(list.size - 1)
        touchPins()
        save()
    }

    fun setTitle(index: Int, title: String) {
        val p = pins.getOrNull(index) ?: return
        pins[index] = Pinned(p.text, title.trim().take(MAX_TITLE))
        touchPins()
        save()
    }

    private fun touchPins() {
        pinsTs = maxOf(System.currentTimeMillis(), pinsTs + 1)
    }

    /**
     * Fijadas que llegan de otro teclado de la familia. Solo se aplican si son más recientes. Si este
     * teclado nunca las había sincronizado (pinsTs = 0) y tiene fijadas, se combinan en vez de
     * sustituirlas, y la combinación pasa a ser la más reciente (para repartirla).
     * Devuelve true si cambió algo.
     */
    fun replacePinned(remote: List<Pinned>, ts: Long): Boolean {
        if (ts <= pinsTs) return false
        val merged = if (pinsTs == 0L && pins.isNotEmpty()) {
            remote + pins.filter { p -> remote.none { it.text == p.text } }
        } else {
            remote
        }
        val first = pinsTs == 0L && pins.isNotEmpty()
        pins.clear()
        merged.distinctBy { it.text }.take(maxPinned).forEach { pins.add(Pinned(it.text, it.title.take(MAX_TITLE))) }
        list.removeAll { t -> pins.any { it.text == t } }
        pinsTs = if (first) maxOf(System.currentTimeMillis(), ts + 1) else ts
        save()
        return true
    }

    private fun save() {
        val f = file ?: return
        val o = JSONObject()
        o.put("history", JSONArray(list))
        o.put("pinned", JSONArray().also { a -> pins.forEach { a.put(JSONObject().put("x", it.text).put("t", it.title)) } })
        o.put("pinnedTs", pinsTs)
        val json = o.toString()
        io.execute {
            try {
                val tmp = File(f.path + ".tmp")
                tmp.writeText(json)
                if (!tmp.renameTo(f)) { f.writeText(json); tmp.delete() }
            } catch (_: Exception) {
            }
        }
    }

    /** Solo para pruebas: espera a que terminen las escrituras pendientes. */
    internal fun flush() {
        io.submit {}.get()
    }

    companion object {
        const val MAX_CHARS = 100_000
        const val MAX_TITLE = 40
        fun fileFor(dir: File) = File(dir, "clipboard.json")
    }
}
