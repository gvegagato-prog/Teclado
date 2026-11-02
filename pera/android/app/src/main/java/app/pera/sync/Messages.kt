package app.pera.sync

import app.pera.Protocol
import org.json.JSONArray
import org.json.JSONObject

/**
 * Mensajes entre dispositivos de la familia Pera (JSON UTF-8, troceado con [Framing]).
 * Mismo formato en el Mac: mac/Sources/Pera/Messages.swift.
 *
 *  - hello: lo manda un cliente al conectarse. «clip» = marca de tiempo del último texto que conoce.
 *  - clip:  último texto copiado en algún dispositivo.
 *  - nets:  redes Wi-Fi conocidas por la familia (nombre, contraseña, seguridad).
 *  - here:  la red a la que está conectado el móvil ahora mismo (o ninguna): los demás se unen a ella.
 *  - img:   última imagen copiada. Binario para no inflar con base64:
 *           [0x01][longitud de la cabecera u16][cabecera JSON {"t":"img","id","ts","from","mime"}][bytes].
 *  - imgref: «tengo una imagen; descárgala por la red local» (ver LanShare): direcciones, puerto,
 *           código de un solo uso, clave AES y huella SHA-256. Es lo normal: calidad completa y rápido.
 *  - imgreq: «no he podido descargarla por la red»: el otro la manda reducida por Bluetooth (img).
 *  - pins:  textos fijados del teclado ({"ts", "items":[{"x": texto, "t": título}]}); gana el más reciente.
 */
sealed class Msg {
    data class Hello(val device: String, val clipTs: Long) : Msg()
    data class Clip(val id: String, val ts: Long, val from: String, val text: String) : Msg()
    data class Nets(val nets: List<Protocol.Wifi>) : Msg()
    data class Here(val net: Protocol.Wifi?) : Msg()

    class Img(val id: String, val ts: Long, val from: String, val mime: String, val data: ByteArray) : Msg() {
        override fun equals(other: Any?) = other is Img && id == other.id && ts == other.ts &&
            from == other.from && mime == other.mime && data.contentEquals(other.data)
        override fun hashCode() = id.hashCode()
    }

    data class ImgRef(
        val id: String, val ts: Long, val from: String, val mime: String, val size: Long, val sha: String,
        val key: String, val addrs: List<String>, val port: Int, val token: String,
    ) : Msg()

    data class ImgReq(val id: String) : Msg()

    data class Pin(val text: String, val title: String)
    data class Pins(val ts: Long, val items: List<Pin>) : Msg()

    fun encode(): ByteArray {
        if (this is Img) {
            val hdr = JSONObject().put("t", "img").put("id", id).put("ts", ts).put("from", from).put("mime", mime)
                .toString().toByteArray(Charsets.UTF_8)
            return java.nio.ByteBuffer.allocate(3 + hdr.size + data.size)
                .put(BINARY).putShort(hdr.size.toShort()).put(hdr).put(data).array()
        }
        val o = JSONObject()
        when (this) {
            is Img -> Unit
            is ImgRef -> o.put("t", "imgref").put("id", id).put("ts", ts).put("from", from).put("mime", mime)
                .put("size", size).put("sha", sha).put("key", key).put("addrs", JSONArray(addrs)).put("port", port)
                .put("token", token)
            is ImgReq -> o.put("t", "imgreq").put("id", id)
            is Pins -> o.put("t", "pins").put("ts", ts).put(
                "items", JSONArray().apply { items.forEach { put(JSONObject().put("x", it.text).put("t", it.title)) } },
            )
            is Hello -> o.put("t", "hello").put("dev", device).put("clip", clipTs)
            is Clip -> o.put("t", "clip").put("id", id).put("ts", ts).put("from", from).put("text", text)
            is Nets -> o.put("t", "nets").put("nets", JSONArray().apply { nets.forEach { put(it.toJson()) } })
            is Here -> o.put("t", "here").put("net", net?.toJson() ?: JSONObject.NULL)
        }
        return o.toString().toByteArray(Charsets.UTF_8)
    }

    companion object {
        /** null si el mensaje no se entiende (p. ej. de una versión futura de Pera): se ignora. */
        private const val BINARY: Byte = 1

        fun decode(bytes: ByteArray): Msg? = runCatching {
            if (bytes.isNotEmpty() && bytes[0] == BINARY) return@runCatching decodeBinary(bytes)
            val o = JSONObject(String(bytes, Charsets.UTF_8))
            when (o.getString("t")) {
                "hello" -> Hello(o.optString("dev", "?"), o.optLong("clip", 0))
                "clip" -> Clip(o.getString("id"), o.getLong("ts"), o.optString("from", "?"), o.getString("text"))
                "nets" -> {
                    val a = o.getJSONArray("nets")
                    Nets((0 until a.length()).mapNotNull { wifiFromJson(a.getJSONObject(it)) })
                }
                "here" -> Here(if (o.isNull("net")) null else wifiFromJson(o.getJSONObject("net")))
                "imgref" -> {
                    val a = o.getJSONArray("addrs")
                    ImgRef(
                        o.getString("id"), o.getLong("ts"), o.optString("from", "?"), o.optString("mime", "image/jpeg"),
                        o.getLong("size"), o.getString("sha"), o.getString("key"),
                        (0 until a.length()).map { a.getString(it) }, o.getInt("port"), o.getString("token"),
                    )
                }
                "imgreq" -> ImgReq(o.getString("id"))
                "pins" -> {
                    val a = o.getJSONArray("items")
                    Pins(o.getLong("ts"), (0 until a.length()).map { a.getJSONObject(it).let { p -> Pin(p.getString("x"), p.optString("t", "")) } })
                }
                else -> null
            }
        }.getOrNull()

        private fun decodeBinary(bytes: ByteArray): Msg? {
            val b = java.nio.ByteBuffer.wrap(bytes, 1, bytes.size - 1)
            val len = b.short.toInt() and 0xFFFF
            if (3 + len > bytes.size) return null
            val h = JSONObject(String(bytes, 3, len, Charsets.UTF_8))
            if (h.optString("t") != "img") return null // otro binario de una versión futura
            return Img(h.getString("id"), h.getLong("ts"), h.optString("from", "?"), h.optString("mime", "image/jpeg"),
                bytes.copyOfRange(3 + len, bytes.size))
        }

        private fun Protocol.Wifi.toJson() = JSONObject()
            .put("ssid", ssid)
            .put("pass", pass ?: JSONObject.NULL)
            .put("sec", sec)
            .apply { if (hidden) put("hid", true) }

        private fun wifiFromJson(o: JSONObject): Protocol.Wifi? = runCatching {
            Protocol.Wifi(
                o.getString("ssid"), if (o.isNull("pass")) null else o.getString("pass"),
                o.optString("sec", "wpa2"), o.optBoolean("hid", false),
            )
        }.getOrNull()
    }
}

/** Reglas puras de sincronización (sin Android: probadas en NetsTest/SyncRulesTest). */
object SyncRules {
    /** Solo redes personales que se pueden sincronizar: con contraseña WPA2/WPA3, o abiertas. */
    fun syncable(w: Protocol.Wifi): Boolean = w.ssid.isNotBlank() && when (w.sec) {
        "open" -> true
        "wpa2", "wpa3" -> !w.pass.isNullOrEmpty()
        else -> false
    }

    /**
     * Une redes conocidas con las recibidas. Si una red ya existe con otra contraseña, gana la
     * recibida (alguien la ha cambiado y se ha vuelto a conectar). Devuelve (todas, nuevas o cambiadas).
     */
    fun merge(known: List<Protocol.Wifi>, incoming: List<Protocol.Wifi>): Pair<List<Protocol.Wifi>, List<Protocol.Wifi>> {
        val bySsid = LinkedHashMap<String, Protocol.Wifi>()
        known.forEach { bySsid[it.ssid] = it }
        val changed = mutableListOf<Protocol.Wifi>()
        incoming.filter(::syncable).forEach { w ->
            if (bySsid[w.ssid] != w) {
                bySsid[w.ssid] = w
                changed += w
            }
        }
        return bySsid.values.toList() to changed
    }

    /** ¿Hay que aceptar este texto? Solo si es más reciente y no es el mismo que ya tenemos. */
    fun acceptClip(current: Msg.Clip?, incoming: Msg.Clip): Boolean =
        current == null || (incoming.ts > current.ts && incoming.id != current.id)

    /** Lo copiado más reciente gana, sea texto o imagen ([latestTs]/[latestId] = lo último que tenemos). */
    fun acceptCopy(latestTs: Long, latestId: String?, ts: Long, id: String): Boolean =
        id != latestId && ts > latestTs

    /** Fijados: gana la lista editada más recientemente. */
    fun acceptPins(current: Msg.Pins?, incoming: Msg.Pins): Boolean = current == null || incoming.ts > current.ts
}
