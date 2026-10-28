package app.teclado

import org.json.JSONArray
import java.io.File
import java.util.concurrent.Executors

/**
 * Historial del portapapeles: las últimas [max] copias de texto, sin caducidad, la más reciente primero.
 * Se guarda en el almacenamiento privado de la app (fuera de las copias de seguridad, ver
 * data_extraction_rules.xml). Copiar algo que ya está en la lista lo sube arriba en vez de duplicarlo.
 */
class ClipStore(private val file: File?, val max: Int = 50) {
    private val list = ArrayList<String>()
    val items: List<String> get() = list

    private val io by lazy { Executors.newSingleThreadExecutor { r -> Thread(r, "teclado-clip").apply { isDaemon = true } } }

    /** Recarga desde disco (por si los ajustes han borrado el historial). */
    fun load() {
        list.clear()
        val f = file ?: return
        try {
            if (!f.exists()) return
            val arr = JSONArray(f.readText())
            for (i in 0 until minOf(arr.length(), max)) list.add(arr.getString(i))
        } catch (_: Exception) {
            list.clear()
        }
    }

    /** true si cambió algo. Ignora texto vacío o mayor de [MAX_CHARS]. */
    fun add(text: String): Boolean {
        if (text.isBlank() || text.length > MAX_CHARS) return false
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

    fun clear() {
        list.clear()
        save()
    }

    private fun save() {
        val f = file ?: return
        val json = JSONArray(list).toString()
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
        fun fileFor(dir: File) = File(dir, "clipboard.json")
    }
}
