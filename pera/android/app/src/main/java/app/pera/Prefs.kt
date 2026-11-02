package app.pera

import android.content.Context

object Prefs {
    private fun prefs(c: Context) = c.getSharedPreferences("pera", Context.MODE_PRIVATE)

    /** Este dispositivo es el móvil que comparte sus datos. */
    fun hostEnabled(c: Context) = prefs(c).getBoolean("host", false)
    fun setHostEnabled(c: Context, v: Boolean) = prefs(c).edit().putBoolean("host", v).apply()

    /** Último estado del hotspot conocido por este cliente (para pintar el botón de ajustes rápidos). */
    fun clientLastOn(c: Context) = prefs(c).getBoolean("client_on", false)
    fun setClientLastOn(c: Context, v: Boolean) = prefs(c).edit().putBoolean("client_on", v).apply()

    /** Última red Wi-Fi recibida del móvil (para no repetir la sugerencia al sistema). */
    fun clientSuggested(c: Context) = prefs(c).getString("client_suggested", null)
    fun setClientSuggested(c: Context, v: String) = prefs(c).edit().putString("client_suggested", v).apply()
}
