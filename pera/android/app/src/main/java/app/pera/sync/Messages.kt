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
 */
sealed class Msg {
    data class Hello(val device: String, val clipTs: Long) : Msg()
    data class Clip(val id: String, val ts: Long, val from: String, val text: String) : Msg()
    data class Nets(val nets: List<Protocol.Wifi>) : Msg()
    data class Here(val net: Protocol.Wifi?) : Msg()

    fun encode(): ByteArray {
        val o = JSONObject()
        when (this) {
            is Hello -> o.put("t", "hello").put("dev", device).put("clip", clipTs)
            is Clip -> o.put("t", "clip").put("id", id).put("ts", ts).put("from", from).put("text", text)
            is Nets -> o.put("t", "nets").put("nets", JSONArray().apply { nets.forEach { put(it.toJson()) } })
            is Here -> o.put("t", "here").put("net", net?.toJson() ?: JSONObject.NULL)
        }
        return o.toString().toByteArray(Charsets.UTF_8)
    }

    companion object {
        /** null si el mensaje no se entiende (p. ej. de una versión futura de Pera): se ignora. */
        fun decode(bytes: ByteArray): Msg? = runCatching {
            val o = JSONObject(String(bytes, Charsets.UTF_8))
            when (o.getString("t")) {
                "hello" -> Hello(o.optString("dev", "?"), o.optLong("clip", 0))
                "clip" -> Clip(o.getString("id"), o.getLong("ts"), o.optString("from", "?"), o.getString("text"))
                "nets" -> {
                    val a = o.getJSONArray("nets")
                    Nets((0 until a.length()).mapNotNull { wifiFromJson(a.getJSONObject(it)) })
                }
                "here" -> Here(if (o.isNull("net")) null else wifiFromJson(o.getJSONObject("net")))
                else -> null
            }
        }.getOrNull()

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
}
