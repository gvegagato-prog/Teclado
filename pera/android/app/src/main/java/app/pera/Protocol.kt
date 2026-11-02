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
     * El anuncio lleva, como datos de fabricante, 4 bytes que identifican al móvil. Así cada cliente
     * se queda con SU móvil y no intenta emparejarse con el de otra persona. Va en el anuncio principal
     * (no en la respuesta al escaneo, que en escaneos de bajo consumo puede no llegar).
     * 0xFFFF es el identificador de fabricante reservado para usos internos / pruebas.
     */
    const val HUB_ID_BYTES = 4
    const val MANUFACTURER_ID = 0xFFFF

    /** Identificador del móvil en un resultado de escaneo (también el formato antiguo de Pera 0.2-0.3). */
    fun hubId(record: android.bluetooth.le.ScanRecord?): ByteArray? =
        record?.getManufacturerSpecificData(MANUFACTURER_ID)?.takeIf { it.size == HUB_ID_BYTES }
            ?: record?.getServiceData(android.os.ParcelUuid(SERVICE))

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

    /**
     * Red Wi-Fi. [pass] es la contraseña o, si la red se guardó así (QR, WPS…), la clave cruda de 64
     * caracteres hexadecimales. [hidden]: red oculta (no anuncia su nombre).
     */
    data class Wifi(val ssid: String, val pass: String?, val sec: String, val hidden: Boolean = false) {
        /** La contraseña es una clave cruda (64 hex), no una frase. */
        val rawPsk: Boolean get() = pass != null && pass.length == 64 && pass.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }

        fun encode(): ByteArray = JSONObject()
            .put("ssid", ssid)
            .put("pass", pass ?: JSONObject.NULL)
            .put("sec", sec)
            .apply { if (hidden) put("hid", true) }
            .toString().toByteArray(Charsets.UTF_8)

        companion object {
            fun decode(bytes: ByteArray): Wifi? = runCatching {
                val o = JSONObject(String(bytes, Charsets.UTF_8))
                Wifi(
                    ssid = o.getString("ssid"),
                    pass = if (o.isNull("pass")) null else o.getString("pass"),
                    sec = o.optString("sec", "wpa2"),
                    hidden = o.optBoolean("hid", false),
                )
            }.getOrNull()
        }
    }
}
