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
