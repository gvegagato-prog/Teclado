package app.pera

import app.pera.sync.Framing
import app.pera.sync.Msg
import app.pera.sync.SyncRules
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SyncTest {

    @Test
    fun framingRoundTripAtSeveralSizes() {
        for (size in listOf(0, 1, 13, 14, 15, 500, 5000, 100_000)) {
            for (mtuPayload in listOf(20, 182, 244, 512)) {
                val data = Random(size).nextBytes(size)
                val frames = Framing.split(7, data, mtuPayload)
                frames.forEach { assertTrue(it.size <= mtuPayload) }
                val a = Framing.Assembler()
                var out: ByteArray? = null
                frames.forEachIndexed { i, f ->
                    out = a.add(f)
                    if (i < frames.lastIndex) assertNull(out)
                }
                assertArrayEquals("size=$size mtu=$mtuPayload", data, out)
            }
        }
    }

    /** Mismo caso fijo que mac/Tests/PeraTests/SyncTests.swift: ambos lados trocean igual. */
    @Test
    fun framingMatchesMac() {
        val frames = Framing.split(5, "hola mundo".toByteArray(), 10)
        val expected = listOf(
            byteArrayOf(0, 5, 0, 0, 0, 3) + "hola".toByteArray(),
            byteArrayOf(0, 5, 0, 1, 0, 3) + " mun".toByteArray(),
            byteArrayOf(0, 5, 0, 2, 0, 3) + "do".toByteArray(),
        )
        assertEquals(expected.map { it.toList() }, frames.map { it.toList() })
    }

    @Test
    fun framingOutOfOrderAndDuplicates() {
        val data = Random(1).nextBytes(1000)
        val frames = Framing.split(3, data, 100)
        val a = Framing.Assembler()
        val shuffled = (frames + frames.take(2)).shuffled(Random(2)).toMutableList()
        // El último en llegar debe completar el mensaje, sea cual sea.
        var result: ByteArray? = null
        for (f in shuffled) result = a.add(f) ?: result
        assertArrayEquals(data, result)
    }

    @Test
    fun framingNewMessageDiscardsHalfReceivedOne() {
        val a = Framing.Assembler()
        val first = Framing.split(1, ByteArray(300) { 1 }, 100)
        val second = Framing.split(2, ByteArray(50) { 2 }, 100)
        assertNull(a.add(first[0]))
        assertArrayEquals(ByteArray(50) { 2 }, a.add(second[0]))
    }

    @Test
    fun framingRejectsHugeMessages() {
        val a = Framing.Assembler()
        val frames = Framing.split(9, ByteArray(Framing.MAX_MESSAGE + 10), 500)
        assertTrue(frames.all { a.add(it) == null })
    }

    @Test
    fun messagesRoundTrip() {
        val msgs = listOf(
            Msg.Hello("Mac de Gon", 123L),
            Msg.Clip("abc", 1730000000000L, "Tablet", "Hola «mundo» ñ\n\"comillas\" 🍐"),
            Msg.Nets(listOf(Protocol.Wifi("Casa", "12345678", "wpa2"), Protocol.Wifi("Café", null, "open"))),
            Msg.Here(Protocol.Wifi("Casa", "12345678", "wpa2")),
            Msg.Here(null),
        )
        msgs.forEach { assertEquals(it, Msg.decode(it.encode())) }
    }

    @Test
    fun hiddenAndRawPskNetworksRoundTrip() {
        val raw = "0123456789abcdef".repeat(4)
        val w = Protocol.Wifi("VODAFONE-F038", raw, "wpa2", hidden = true)
        assertTrue(w.rawPsk)
        assertFalse(Protocol.Wifi("Casa", "12345678", "wpa2").rawPsk)
        assertEquals(Msg.Here(w), Msg.decode(Msg.Here(w).encode()))
        assertEquals(w, Protocol.Wifi.decode(w.encode()))
        // Un mensaje de una versión anterior (sin «hid») se entiende como red visible.
        val old = Msg.decode("""{"t":"here","net":{"ssid":"Casa","pass":"12345678","sec":"wpa2"}}""".toByteArray())
        assertEquals(Msg.Here(Protocol.Wifi("Casa", "12345678", "wpa2", hidden = false)), old)
    }

    @Test
    fun imageAndPinsRoundTrip() {
        val jpeg = Random(5).nextBytes(300_000)
        val img = Msg.Img("i1", 1730000000000L, "Mac", "image/jpeg", jpeg)
        val back = Msg.decode(img.encode())
        assertEquals(img, back)
        // Atraviesa el troceado como un mensaje cualquiera.
        val a = Framing.Assembler()
        var out: ByteArray? = null
        Framing.split(1, img.encode(), 244).forEach { out = a.add(it) ?: out }
        assertEquals(img, Msg.decode(out!!))

        val pins = Msg.Pins(42L, listOf(Msg.Pin("mi correo", "Correo"), Msg.Pin("línea\nnueva «ñ»", "")))
        assertEquals(pins, Msg.decode(pins.encode()))
    }

    /** Cabecera binaria fija: la misma que espera el Mac (SyncTests.swift). */
    @Test
    fun imageBinaryLayout() {
        val bytes = Msg.Img("a", 1, "b", "image/jpeg", byteArrayOf(9, 8)).encode()
        assertEquals(1.toByte(), bytes[0])
        val hdrLen = ((bytes[1].toInt() and 0xFF) shl 8) or (bytes[2].toInt() and 0xFF)
        val hdr = String(bytes, 3, hdrLen)
        assertTrue(hdr.contains("\"t\":\"img\""))
        assertArrayEquals(byteArrayOf(9, 8), bytes.copyOfRange(3 + hdrLen, bytes.size))
    }

    @Test
    fun latestCopyWinsAcrossTextAndImages() {
        assertTrue(SyncRules.acceptCopy(100, "x", 101, "y"))
        assertFalse(SyncRules.acceptCopy(100, "x", 99, "y"))
        assertFalse(SyncRules.acceptCopy(100, "x", 200, "x"))
        assertTrue(SyncRules.acceptPins(null, Msg.Pins(1, emptyList())))
        assertFalse(SyncRules.acceptPins(Msg.Pins(5, emptyList()), Msg.Pins(4, emptyList())))
    }

    @Test
    fun unknownMessagesAreIgnored() {
        assertNull(Msg.decode("""{"t":"futuro","x":1}""".toByteArray()))
        assertNull(Msg.decode("basura".toByteArray()))
    }

    @Test
    fun onlyPersonalNetworksAreSynced() {
        assertTrue(SyncRules.syncable(Protocol.Wifi("Casa", "12345678", "wpa2")))
        assertTrue(SyncRules.syncable(Protocol.Wifi("Café", null, "open")))
        assertFalse(SyncRules.syncable(Protocol.Wifi("Casa", null, "wpa2")))
        assertFalse(SyncRules.syncable(Protocol.Wifi("Oficina", "x", "eap")))
        assertFalse(SyncRules.syncable(Protocol.Wifi(" ", "12345678", "wpa2")))
    }

    @Test
    fun mergeAddsNewAndChangedNetworks() {
        val known = listOf(Protocol.Wifi("Casa", "vieja123", "wpa2"), Protocol.Wifi("Bar", "bar12345", "wpa2"))
        val incoming = listOf(
            Protocol.Wifi("Casa", "nueva123", "wpa2"), // contraseña cambiada
            Protocol.Wifi("Bar", "bar12345", "wpa2"), // igual
            Protocol.Wifi("Playa", "playa123", "wpa3"), // nueva
            Protocol.Wifi("Mala", null, "wpa2"), // no sincronizable
        )
        val (all, changed) = SyncRules.merge(known, incoming)
        assertEquals(listOf("Casa", "Bar", "Playa"), all.map { it.ssid })
        assertEquals("nueva123", all.first { it.ssid == "Casa" }.pass)
        assertEquals(listOf("Casa", "Playa"), changed.map { it.ssid })
    }

    @Test
    fun clipAcceptance() {
        val old = Msg.Clip("1", 100, "Mac", "a")
        assertTrue(SyncRules.acceptClip(null, old))
        assertTrue(SyncRules.acceptClip(old, Msg.Clip("2", 101, "Tablet", "b")))
        assertFalse(SyncRules.acceptClip(old, Msg.Clip("3", 99, "Tablet", "c")))
        assertFalse(SyncRules.acceptClip(old, old.copy()))
    }
}
