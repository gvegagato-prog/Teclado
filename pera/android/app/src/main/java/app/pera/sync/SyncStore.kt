package app.pera.sync

import android.content.Context
import android.os.Build
import android.provider.Settings
import app.pera.Protocol

/** Estado de sincronización guardado en el dispositivo: último texto, redes de la familia y ajustes. */
class SyncStore(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("pera_sync", Context.MODE_PRIVATE)

    var clipEnabled: Boolean
        get() = prefs.getBoolean("clip_on", true)
        set(v) = prefs.edit().putBoolean("clip_on", v).apply()

    var wifiEnabled: Boolean
        get() = prefs.getBoolean("wifi_on", true)
        set(v) = prefs.edit().putBoolean("wifi_on", v).apply()

    /** Nombre con el que este dispositivo aparece ante los demás. */
    val deviceName: String
        get() = Settings.Global.getString(app.contentResolver, "device_name") ?: Build.MODEL

    @get:Synchronized @set:Synchronized
    var clip: Msg.Clip?
        get() = prefs.getString("clip", null)?.let { Msg.decode(it.toByteArray()) as? Msg.Clip }
        set(v) = prefs.edit().putString("clip", v?.let { String(it.encode()) }).apply()

    @get:Synchronized @set:Synchronized
    var nets: List<Protocol.Wifi>
        get() = (Msg.decode(prefs.getString("nets", "{\"t\":\"nets\",\"nets\":[]}")!!.toByteArray()) as? Msg.Nets)?.nets.orEmpty()
        set(v) = prefs.edit().putString("nets", String(Msg.Nets(v).encode())).apply()

    var pinsEnabled: Boolean
        get() = prefs.getBoolean("pins_on", true)
        set(v) = prefs.edit().putBoolean("pins_on", v).apply()

    /** Última imagen copiada en la familia (cabecera aquí; los bytes en Images). */
    @get:Synchronized
    val image: Msg.Img?
        get() {
            val id = prefs.getString("img_id", null) ?: return null
            val data = Images.load(app, id) ?: return null
            return Msg.Img(id, prefs.getLong("img_ts", 0), prefs.getString("img_from", "?")!!,
                prefs.getString("img_mime", "image/jpeg")!!, data)
        }

    @Synchronized
    fun setImage(img: Msg.Img) {
        Images.save(app, img)
        prefs.edit().putString("img_id", img.id).putLong("img_ts", img.ts).putString("img_from", img.from)
            .putString("img_mime", img.mime).apply()
    }

    /** Marca de tiempo e id de lo último copiado (texto o imagen): gana siempre lo más reciente. */
    val latestTs: Long get() = maxOf(clip?.ts ?: 0, prefs.getLong("img_ts", 0))
    val latestId: String?
        get() = if ((clip?.ts ?: 0) >= prefs.getLong("img_ts", 0)) clip?.id else prefs.getString("img_id", null)

    /** Lo último copiado, sea texto o imagen. */
    val latest: Msg?
        get() {
            val c = clip
            val imgTs = prefs.getLong("img_ts", 0)
            return if (c != null && c.ts >= imgTs) c else image ?: c
        }

    @get:Synchronized @set:Synchronized
    var pins: Msg.Pins?
        get() = prefs.getString("pins", null)?.let { Msg.decode(it.toByteArray()) as? Msg.Pins }
        set(v) = prefs.edit().putString("pins", v?.let { String(it.encode()) }).apply()

    /** Redes que este dispositivo ya ha aplicado (para no repetir). Clave: ssid|pass|sec. */
    fun applied(w: Protocol.Wifi) = prefs.getStringSet("applied", emptySet())!!.contains(key(w))

    @Synchronized
    fun markApplied(w: Protocol.Wifi) {
        val s = prefs.getStringSet("applied", emptySet())!!.toMutableSet()
        s.removeAll { it.startsWith(w.ssid + "|") }
        s += key(w)
        prefs.edit().putStringSet("applied", s).apply()
    }

    /** Redes guardadas de verdad en este dispositivo con la pantalla del sistema (no solo sugeridas). */
    fun saved(w: Protocol.Wifi) = prefs.getStringSet("saved", emptySet())!!.contains(key(w))

    @Synchronized
    fun markSaved(w: Protocol.Wifi) {
        val s = prefs.getStringSet("saved", emptySet())!!.toMutableSet()
        s.removeAll { it.startsWith(w.ssid + "|") }
        s += key(w)
        prefs.edit().putStringSet("saved", s).apply()
    }

    /** Red a la que está conectado el móvil (en el móvil: la suya; en clientes: la última recibida). */
    var here: Protocol.Wifi?
        get() = prefs.getString("here", null)?.let { (Msg.decode(it.toByteArray()) as? Msg.Here)?.net }
        set(v) = prefs.edit().putString("here", String(Msg.Here(v).encode())).apply()

    /** Diagnóstico para la pantalla de la app (texto corto). */
    var wifiDiag: String?
        get() = prefs.getString("wifi_diag", null)
        set(v) = prefs.edit().putString("wifi_diag", v).apply()

    /** Últimos movimientos del portapapeles (diagnóstico en la app). */
    val clipDiag: List<String>
        get() = prefs.getString("clip_diag", "")!!.split("\n").filter { it.isNotBlank() }

    @Synchronized
    fun noteClip(event: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date())
        val lines = (listOf("$time $event") + clipDiag).take(3)
        prefs.edit().putString("clip_diag", lines.joinToString("\n")).apply()
    }

    /** Últimos movimientos de los fijados (diagnóstico en la app). */
    val pinsDiag: List<String>
        get() = prefs.getString("pins_diag", "")!!.split("\n").filter { it.isNotBlank() }

    @Synchronized
    fun notePins(event: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date())
        prefs.edit().putString("pins_diag", (listOf("$time $event") + pinsDiag).take(4).joinToString("\n")).apply()
    }

    private fun key(w: Protocol.Wifi) = "${w.ssid}|${w.pass}|${w.sec}"

    companion object {
        /** Último texto que Pera ha escrito en el portapapeles (para no reenviarlo como copia nueva). */
        @Volatile var lastApplied: String? = null

        /** Huella de la última imagen que Pera ha puesto en el portapapeles (mismo motivo). */
        @Volatile var lastAppliedImage: String? = null

        fun newClip(text: String, from: String) =
            Msg.Clip(java.util.UUID.randomUUID().toString(), System.currentTimeMillis(), from, text)

        /** Textos más largos no se sincronizan (Bluetooth LE es lento para eso). */
        const val MAX_CLIP_CHARS = 32_000
    }
}
