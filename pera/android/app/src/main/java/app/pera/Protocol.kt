package app.pera

import org.json.JSONObject
import java.util.UUID

/**
 * Protocolo Bluetooth LE de Pera (el mismo en Android y en Mac: ver mac/Sources/Pera/Protocol.swift).
 *
 * El móvil publica un servicio GATT. Todas las características exigen cifrado, así que el primer uso
 * obliga a emparejar los dispositivos (con confirmación en ambos): un desconocido no puede encender
 * tu hotspot ni leer la contraseña.
 */
object Protocol {
    val SERVICE: UUID = UUID.fromString("b49ae33f-05ff-43dc-832e-1e1cbc315a8c")

    /** Escritura: 1 byte. [CMD_ON] enciende el hotspot, [CMD_OFF] lo apaga. */
    val COMMAND: UUID = UUID.fromString("c28cdba2-6e89-4698-ab29-d5caf1d9b653")

    /** Lectura y notificación: 1 byte con uno de los STATE_*. */
    val STATE: UUID = UUID.fromString("e46e38d3-f5a3-469b-8476-d3a9740d2c89")

    /** Lectura: JSON UTF-8 {"ssid": "...", "pass": "...", "sec": "wpa2"|"wpa3"|"open"}. */
    val WIFI: UUID = UUID.fromString("5e1e2505-2ebf-487d-b745-36b2692ef8bb")

    /** Canal de mensajes cliente → móvil (escritura de fragmentos, ver sync/Framing.kt). */
    val MSG_IN: UUID = UUID.fromString("4af7edca-a0a3-49ed-b043-e555361dd8ee")

    /** Canal de mensajes móvil → cliente (notificación de fragmentos). */
    val MSG_OUT: UUID = UUID.fromString("f049b244-0074-4753-b89f-8c89ae98f59b")

    /**
     * En la respuesta al escaneo, los datos de servicio de [SERVICE] llevan 4 bytes que identifican al
     * móvil. Así cada cliente se queda con SU móvil y no intenta emparejarse con el de otra persona.
     */
    const val HUB_ID_BYTES = 4

    /** Descriptor estándar para activar notificaciones. */
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val CMD_OFF: Byte = 0
    const val CMD_ON: Byte = 1

    const val STATE_OFF = 0
    const val STATE_TURNING_ON = 1
    const val STATE_ON = 2
    const val STATE_TURNING_OFF = 3
    const val STATE_FAILED = 4
    /** Shizuku no está en marcha o Pera no tiene permiso: el móvil no puede encender el hotspot. */
    const val STATE_NO_SHIZUKU = 5

    fun stateText(state: Int): String = when (state) {
        STATE_OFF -> "apagado"
        STATE_TURNING_ON -> "encendiéndose"
        STATE_ON -> "encendido"
        STATE_TURNING_OFF -> "apagándose"
        STATE_FAILED -> "error al encender"
        STATE_NO_SHIZUKU -> "Shizuku no está activo en el móvil"
        else -> "desconocido ($state)"
    }

    data class Wifi(val ssid: String, val pass: String?, val sec: String) {
        fun encode(): ByteArray = JSONObject()
            .put("ssid", ssid)
            .put("pass", pass ?: JSONObject.NULL)
            .put("sec", sec)
            .toString().toByteArray(Charsets.UTF_8)

        companion object {
            fun decode(bytes: ByteArray): Wifi? = runCatching {
                val o = JSONObject(String(bytes, Charsets.UTF_8))
                Wifi(
                    ssid = o.getString("ssid"),
                    pass = if (o.isNull("pass")) null else o.getString("pass"),
                    sec = o.optString("sec", "wpa2"),
                )
            }.getOrNull()
        }
    }
}
