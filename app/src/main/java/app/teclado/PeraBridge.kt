package app.teclado

import android.content.BroadcastReceiver
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Comunicación con Pera (pera/ en este repo) para compartir con tu tablet y tu Mac:
 *  - cada texto copiado,
 *  - cada imagen copiada (se deja en [ImageProvider] y Pera la lee),
 *  - las copias fijadas (y recibir las de los demás teclados de la familia).
 * Teclado, como teclado activo, puede leer el portapapeles sin avisos; Pera no. Todo va protegido con
 * un permiso de firma: solo una app firmada con la misma clave puede hablar con la otra.
 */
object PeraBridge {
    private const val PERA_PACKAGE = "app.pera"
    const val PERMISSION = "app.pera.permission.SEND_CLIP"
    const val PINS_ACTION = "app.teclado.action.PINS"

    private fun peraInstalled(context: Context) =
        runCatching { context.packageManager.getPackageInfo(PERA_PACKAGE, 0) }.isSuccess

    private fun send(context: Context, intent: Intent) {
        if (!peraInstalled(context)) return
        intent.setPackage(PERA_PACKAGE)
            // Aunque Android haya detenido Pera, la despierta (y Pera vuelve a arrancar sus servicios).
            .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        runCatching { context.sendBroadcast(intent) }
    }

    fun send(context: Context, text: String) =
        send(context, Intent("app.pera.action.CLIP").putExtra("text", text))

    /**
     * Imagen copiada: se copia del portapapeles (Android da permiso de lectura al teclado activo) a un
     * archivo propio y se avisa a Pera, que la lee de [ImageProvider], la reduce y la reparte.
     * Bloqueante: llamar desde un hilo de fondo.
     */
    fun sendImage(context: Context, source: Uri) {
        if (!peraInstalled(context)) return
        val file = ImageProvider.file(context)
        val ok = runCatching {
            context.contentResolver.openInputStream(source)?.use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_IMAGE_BYTES) return@use false
                        out.write(buf, 0, n)
                    }
                    true
                }
            } ?: false
        }.getOrDefault(false)
        if (!ok) return
        // Uri con la hora para que Pera no confunda dos imágenes seguidas.
        val uri = Uri.parse("content://${ImageProvider.AUTHORITY}/img?t=${System.currentTimeMillis()}")
        send(context, Intent("app.pera.action.IMAGE").putExtra("uri", uri.toString()))
    }

    /** Fijadas de este teclado, para los demás de la familia (mismo formato que los mensajes de Pera). */
    fun sendPins(context: Context, store: ClipStore) {
        val json = JSONObject().put("t", "pins").put("ts", store.pinsTs).put(
            "items",
            JSONArray().also { a -> store.pinned.forEach { a.put(JSONObject().put("x", it.text).put("t", it.title)) } },
        )
        send(context, Intent("app.pera.action.PINS").putExtra("pins", json.toString()))
    }

    fun parsePins(json: String): Pair<Long, List<ClipStore.Pinned>>? = runCatching {
        val o = JSONObject(json)
        val a = o.getJSONArray("items")
        o.getLong("ts") to (0 until a.length()).map {
            val p = a.getJSONObject(it)
            ClipStore.Pinned(p.getString("x"), p.optString("t", ""))
        }
    }.getOrNull()

    /** Imágenes que pone Pera al recibirlas: no se reenvían (volverían a la familia). */
    fun isFromPera(uri: Uri) = uri.authority == "app.pera.clip"

    private const val MAX_IMAGE_BYTES = 40L * 1024 * 1024
}

/** Deja a Pera leer la última imagen copiada (solo con el permiso de firma, ver el manifiesto). */
class ImageProvider : ContentProvider() {
    override fun onCreate() = true

    /** Tipo real por los primeros bytes (la app donde se pega la imagen lo necesita). */
    override fun getType(uri: Uri): String {
        val head = ByteArray(12)
        val n = runCatching { fileFor(context!!, uri).inputStream().use { it.read(head) } }.getOrDefault(0)
        return sniff(head.copyOf(maxOf(0, n)))
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val f = fileFor(context!!, uri).takeIf { it.exists() } ?: return null
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0

    companion object {
        const val AUTHORITY = "app.teclado.pera"
        fun file(context: Context) = File(context.cacheDir, "pera_clip_image")

        /** Copia para pegar en la app actual (se da permiso de lectura solo a esa app, ver commitContent). */
        fun pasteFile(context: Context) = File(context.cacheDir, "paste_image")

        private fun fileFor(context: Context, uri: Uri) =
            if (uri.path?.startsWith("/paste") == true) pasteFile(context) else file(context)

        fun sniff(b: ByteArray): String = when {
            b.size >= 4 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() -> "image/png"
            b.size >= 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() -> "image/jpeg"
            b.size >= 4 && String(b, 0, 3) == "GIF" -> "image/gif"
            b.size >= 12 && String(b, 0, 4) == "RIFF" && String(b, 8, 4) == "WEBP" -> "image/webp"
            else -> "image/jpeg"
        }
    }
}

/** Fijadas que llegan de la familia (vía Pera). Si el teclado está abierto, se aplican al momento. */
class PinsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != PeraBridge.PINS_ACTION) return
        val (ts, items) = intent.getStringExtra("pins")?.let(PeraBridge::parsePins) ?: return
        val service = TecladoService.instance
        if (service != null) {
            Handler(Looper.getMainLooper()).post { service.applyRemotePins(items, ts) }
            return
        }
        val store = ClipStore(ClipStore.fileFor(context.filesDir))
        store.load()
        if (store.replacePinned(items, ts)) {
            store.flush()
            if (store.pinsTs != ts) PeraBridge.sendPins(context, store) // se combinaron: se reparte la unión
        }
    }
}
