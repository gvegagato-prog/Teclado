package app.pera.sync

import java.nio.ByteBuffer

/**
 * Troceado de mensajes para Bluetooth LE: una característica admite como mucho 512 bytes y cada
 * escritura/notificación cabe en (MTU - 3) bytes, así que los mensajes largos se parten en fragmentos.
 *
 * Fragmento: [id del mensaje u16][índice u16][total u16][datos…] (big endian).
 * Mismo formato en el Mac: mac/Sources/Pera/Framing.swift.
 */
object Framing {
    const val HEADER = 6
    /** Límite de un mensaje completo (un texto copiado muy largo no se sincroniza). */
    const val MAX_MESSAGE = 128 * 1024

    fun split(msgId: Int, data: ByteArray, maxFrame: Int): List<ByteArray> {
        require(maxFrame > HEADER) { "Fragmento demasiado pequeño" }
        val chunk = maxFrame - HEADER
        val count = maxOf(1, (data.size + chunk - 1) / chunk)
        require(count <= 0xFFFF) { "Mensaje demasiado grande" }
        return (0 until count).map { i ->
            val from = i * chunk
            val to = minOf(data.size, from + chunk)
            ByteBuffer.allocate(HEADER + (to - from))
                .putShort(msgId.toShort())
                .putShort(i.toShort())
                .putShort(count.toShort())
                .put(data, from, to - from)
                .array()
        }
    }

    /** Recompone mensajes a partir de fragmentos (de un único emisor). */
    class Assembler {
        private var id = -1
        private var count = 0
        private var parts = arrayOfNulls<ByteArray>(0)
        private var received = 0
        private var size = 0

        /** Devuelve el mensaje completo cuando llega su último fragmento; si no, null. */
        fun add(frame: ByteArray): ByteArray? {
            if (frame.size < HEADER) return null
            val b = ByteBuffer.wrap(frame)
            val msgId = b.short.toInt() and 0xFFFF
            val index = b.short.toInt() and 0xFFFF
            val total = b.short.toInt() and 0xFFFF
            if (total == 0 || index >= total) return null
            if (msgId != id || total != count) {
                // Empieza otro mensaje: se descarta el que estuviera a medias.
                id = msgId
                count = total
                parts = arrayOfNulls(total)
                received = 0
                size = 0
            }
            if (parts[index] == null) {
                val payload = frame.copyOfRange(HEADER, frame.size)
                size += payload.size
                if (size > MAX_MESSAGE) {
                    reset()
                    return null
                }
                parts[index] = payload
                received++
            }
            if (received < count) return null
            val out = ByteBuffer.allocate(size)
            parts.forEach { out.put(it!!) }
            reset()
            return out.array()
        }

        private fun reset() {
            id = -1
            count = 0
            parts = arrayOfNulls(0)
            received = 0
            size = 0
        }
    }
}
