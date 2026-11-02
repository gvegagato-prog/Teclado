package app.pera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProtocolTest {
    @Test
    fun wifiRoundTrip() {
        val w = Protocol.Wifi("Galaxy de Gon ñ", "clave \"rara\" 123", "wpa3")
        assertEquals(w, Protocol.Wifi.decode(w.encode()))
    }

    @Test
    fun openNetworkHasNoPassword() {
        val w = Protocol.Wifi("Abierta", null, "open")
        assertEquals(w, Protocol.Wifi.decode(w.encode()))
    }

    @Test
    fun garbageIsRejected() {
        assertNull(Protocol.Wifi.decode("no es json".toByteArray()))
    }

    /** El mismo JSON que produce el Mac (mac/Sources/Pera/Protocol.swift) se entiende aquí. */
    @Test
    fun decodesMacStyleJson() {
        val json = """{"sec":"wpa2","ssid":"Movil","pass":"12345678"}"""
        assertEquals(Protocol.Wifi("Movil", "12345678", "wpa2"), Protocol.Wifi.decode(json.toByteArray()))
    }
}
