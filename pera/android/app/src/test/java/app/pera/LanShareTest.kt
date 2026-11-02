package app.pera

import app.pera.sync.LanShare
import app.pera.sync.Msg
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LanShareTest {

    private fun offerLocal(img: Msg.Img): Msg.ImgRef {
        // En el contenedor de pruebas puede no haber Wi-Fi: se fuerza la dirección local.
        val ref = LanShare.offer(img) ?: error("sin interfaz de red en el entorno de pruebas")
        return ref.copy(addrs = listOf("127.0.0.1"))
    }

    @Test
    fun fullQualityImageTravelsIntactOverTheNetwork() {
        val data = Random(1).nextBytes(5 * 1024 * 1024) // una foto grande, sin reducir
        val img = Msg.Img("foto1", 1730000000000L, "Tablet", "image/jpeg", data)
        val ref = offerLocal(img)
        assertEquals(data.size.toLong(), ref.size)
        val got = LanShare.fetch(ref)
        assertNotNull(got)
        assertArrayEquals(data, got!!.data)
        assertEquals("foto1", got.id)
        // El aviso por Bluetooth es diminuto comparado con la imagen.
        assertEquals(true, ref.encode().size < 600)
        assertEquals(ref, Msg.decode(ref.encode()))
    }

    @Test
    fun wrongTokenGetsNothing() {
        val ref = offerLocal(Msg.Img("foto2", 1, "Mac", "image/png", Random(2).nextBytes(1000)))
        assertNull(LanShare.fetch(ref.copy(token = "0".repeat(32))))
    }

    /** Vector fijo (generado con la biblioteca «cryptography» de Python): el Mac descifra el mismo. */
    @Test
    fun decryptsSharedVector() {
        val key = ByteArray(32) { it.toByte() }
        val payload = "000102030405060708090a0b1767a47ae588ad69e928f3eaa7e71f8441c7bee5f91480be5a4d96af"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertEquals("Pera mordida", String(LanShare.decrypt(key, payload)))
    }

    @Test
    fun encryptionDetectsTampering() {
        val key = Random(3).nextBytes(32)
        val payload = LanShare.encrypt(key, "hola".toByteArray())
        assertArrayEquals("hola".toByteArray(), LanShare.decrypt(key, payload))
        payload[payload.size - 1] = (payload[payload.size - 1].toInt() xor 1).toByte()
        assertThrows(Exception::class.java) { LanShare.decrypt(key, payload) }
    }
}
