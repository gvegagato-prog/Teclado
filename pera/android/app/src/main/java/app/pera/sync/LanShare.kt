package app.pera.sync

import android.util.Base64
import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Envío de imágenes a calidad completa por la red local (misma Wi-Fi, o un dispositivo en el hotspot
 * del otro), mucho más rápido que Bluetooth. Sin servidores de internet: va directa entre tus dispositivos.
 *
 * Quien tiene la imagen la ofrece aquí y manda por Bluetooth (canal ya cifrado) un [Msg.ImgRef] con sus
 * direcciones, el puerto, un código de un solo uso y una clave. El otro se conecta y la descarga.
 * Protocolo TCP: el cliente envía «código\n»; el servidor responde [longitud u64][nonce 12][datos+etiqueta]
 * cifrados con AES-256-GCM. El receptor comprueba la huella SHA-256. Igual en el Mac (LanShare.swift).
 */
object LanShare {
    private const val TAG = "PeraLan"
    private const val MAX_BYTES = 64L * 1024 * 1024
    private const val TTL_MS = 10 * 60 * 1000L

    private class Offer(val payload: ByteArray, val expires: Long)

    private val offers = ConcurrentHashMap<String, Offer>()
    private val byImage = ConcurrentHashMap<String, Msg.ImgRef>()
    private val random = SecureRandom()
    @Volatile private var server: ServerSocket? = null

    /** Ofrece la imagen y devuelve la referencia para enviarla por Bluetooth (null si no hay red). */
    @Synchronized
    fun offer(img: Msg.Img): Msg.ImgRef? {
        byImage[img.id]?.let { ref -> if (offers.containsKey(ref.token)) return ref.copy(addrs = addresses()) }
        val addrs = addresses()
        if (addrs.isEmpty()) return null
        val port = ensureServer() ?: return null
        val key = ByteArray(32).also(random::nextBytes)
        val token = hex(ByteArray(16).also(random::nextBytes))
        offers[token] = Offer(encrypt(key, img.data), System.currentTimeMillis() + TTL_MS)
        offers.entries.removeAll { it.value.expires < System.currentTimeMillis() }
        val ref = Msg.ImgRef(
            img.id, img.ts, img.from, img.mime, img.data.size.toLong(), sha256(img.data),
            Base64.encodeToString(key, Base64.NO_WRAP), addrs, port, token,
        )
        byImage[img.id] = ref
        return ref
    }

    /** Descarga la imagen de [ref]; null si no se pudo (sin red común, aislamiento de clientes…). */
    fun fetch(ref: Msg.ImgRef): Msg.Img? {
        if (ref.size > MAX_BYTES) return null
        val key = runCatching { Base64.decode(ref.key, Base64.NO_WRAP) }.getOrNull() ?: return null
        for (addr in ref.addrs) {
            val data = runCatching {
                Socket().use { s ->
                    s.connect(InetSocketAddress(addr, ref.port), 2_500)
                    s.soTimeout = 20_000
                    s.getOutputStream().apply { write("${ref.token}\n".toByteArray()); flush() }
                    val input = DataInputStream(s.getInputStream().buffered())
                    val len = input.readLong()
                    require(len in 1..(MAX_BYTES + 64)) { "longitud inválida" }
                    val payload = ByteArray(len.toInt()).also(input::readFully)
                    decrypt(key, payload)
                }
            }.onFailure { Log.i(TAG, "No se pudo descargar de $addr:${ref.port}: ${it.message}") }.getOrNull()
            if (data != null && sha256(data) == ref.sha) return Msg.Img(ref.id, ref.ts, ref.from, ref.mime, data)
        }
        return null
    }

    @Synchronized
    private fun ensureServer(): Int? {
        server?.takeIf { !it.isClosed }?.let { return it.localPort }
        return runCatching {
            val s = ServerSocket(0)
            server = s
            Thread({ acceptLoop(s) }, "pera-lan").apply { isDaemon = true }.start()
            s.localPort
        }.onFailure { Log.w(TAG, "No se pudo abrir el servidor", it) }.getOrNull()
    }

    private fun acceptLoop(s: ServerSocket) {
        while (!s.isClosed) {
            val c = runCatching { s.accept() }.getOrNull() ?: continue
            Thread({ serve(c) }, "pera-lan-envio").apply { isDaemon = true }.start()
        }
    }

    private fun serve(c: Socket) = runCatching {
        c.use {
            it.soTimeout = 10_000
            val input = it.getInputStream()
            val line = StringBuilder()
            while (line.length < 64) {
                val b = input.read()
                if (b < 0 || b == '\n'.code) break
                line.append(b.toChar())
            }
            val offer = offers[line.toString().trim()]?.takeIf { o -> o.expires > System.currentTimeMillis() } ?: return@use
            DataOutputStream(it.getOutputStream().buffered()).apply {
                writeLong(offer.payload.size.toLong())
                write(offer.payload)
                flush()
            }
        }
    }

    /** IPv4 de las interfaces activas (Wi-Fi, hotspot…), sin la de bucle ni las de enlace local. */
    fun addresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { !it.isLinkLocalAddress && !it.isLoopbackAddress }
            .map { it.hostAddress!! }
            .distinct()
    }.getOrDefault(emptyList())

    internal fun encrypt(key: ByteArray, data: ByteArray): ByteArray {
        val nonce = ByteArray(12).also(random::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        return nonce + c.doFinal(data)
    }

    internal fun decrypt(key: ByteArray, payload: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, payload, 0, 12))
        return c.doFinal(payload, 12, payload.size - 12)
    }

    fun sha256(data: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
}
