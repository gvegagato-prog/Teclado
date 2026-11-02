package app.pera

import android.content.Context

object Prefs {
    private fun prefs(c: Context) = c.getSharedPreferences("pera", Context.MODE_PRIVATE)

    /** Este dispositivo es el móvil que comparte sus datos. */
    fun hostEnabled(c: Context) = prefs(c).getBoolean("host", false)
    fun setHostEnabled(c: Context, v: Boolean) = prefs(c).edit().putBoolean("host", v).apply()

    /** Este dispositivo (tablet) se mantiene conectado al móvil para sincronizar. */
    fun syncEnabled(c: Context) = prefs(c).getBoolean("sync", false)
    fun setSyncEnabled(c: Context, v: Boolean) = prefs(c).edit().putBoolean("sync", v).apply()

    /** Identificador de este móvil en el anuncio Bluetooth (se crea una vez). */
    fun hubId(c: Context): ByteArray {
        val p = prefs(c)
        p.getString("hub_id", null)?.let { return hex(it) }
        val id = ByteArray(Protocol.HUB_ID_BYTES).also { java.security.SecureRandom().nextBytes(it) }
        p.edit().putString("hub_id", hex(id)).apply()
        return id
    }

    /** En clientes: identificador del móvil de este usuario (null hasta la primera conexión). */
    fun pairedHubId(c: Context): ByteArray? = prefs(c).getString("paired_hub", null)?.let(::hex)
    fun setPairedHubId(c: Context, id: ByteArray) = prefs(c).edit().putString("paired_hub", hex(id)).apply()
    fun forgetPairedHub(c: Context) = prefs(c).edit().remove("paired_hub").apply()

    fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /** Último estado del hotspot conocido por este cliente (para pintar el botón de ajustes rápidos). */
    fun clientLastOn(c: Context) = prefs(c).getBoolean("client_on", false)
    fun setClientLastOn(c: Context, v: Boolean) = prefs(c).edit().putBoolean("client_on", v).apply()

}
